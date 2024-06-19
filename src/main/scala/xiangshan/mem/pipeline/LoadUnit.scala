/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.mem

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import xs.utils._
import xiangshan.ExceptionNO._
import xiangshan._
import xiangshan.backend.execute.fu.FuConfigs.lduCfg
import xiangshan.backend.execute.fu._
import xiangshan.backend.execute.fu.csr.SdtrigExt
import xiangshan.backend.issue.{RSFeedback, RSFeedbackType, RsIdx}
import xiangshan.backend.rob.RobPtr
import xiangshan.cache._
import xiangshan.cache.mmu.{TlbCmd, TlbReq, TlbRequestIO, TlbResp}
import xs.utils.perf.HasPerfLogging

class LoadToLsqIO(implicit p: Parameters) extends XSBundle {
  val s1_lduMMIOPAddr = ValidIO(new LoadMMIOPaddrWriteBundle)
  val s2_lduUpdateLQ = ValidIO(new LqWriteBundle)
  val s2_load_data_forwarded = Output(Bool())
//  val s2_dcache_require_replay = Output(Bool())

//  val s3_lq_wb = Flipped(DecoupledIO(new ExuOutput))
//  val s3_lq_wbLdRawData = Input(new LoadDataFromLQBundle)
//  val s3_replay_from_fetch = Output(Bool()) // update uop.ctrl.replayInst in load queue in s3

  val forwardFromSQ = new PipeLoadForwardFromSQ
  val loadViolationQuery = new LoadViolationQueryIO
  val trigger = Flipped(new LqTriggerIO)
}

class LoadUnitTriggerIO(implicit p: Parameters) extends XSBundle {
  val tdata2 = Input(UInt(64.W))
  val matchType = Input(UInt(2.W))
  val tEnable = Input(Bool()) // timing is calculated before this
  val addrHit = Output(Bool())
  val lastDataHit = Output(Bool())
}

class LoadUnit(implicit p: Parameters) extends XSModule
  with HasLoadHelper
  with HasPerfEvents
  with HasDCacheParameters
  with SdtrigExt
  with HasPerfLogging
  with HasCircularQueuePtrHelper {
  val io = IO(new Bundle() {

    // S0: reservationStation issueIn
    val rsIssueIn = Flipped(Decoupled(new ExuInput))
    val rsIdx = Input(new RsIdx)
    // S0: replayQueue issueIn
    val replayQIssueIn = Flipped(Decoupled(new ReplayQueueIssueBundle))
    // S0: specialLoad for timing
    val auxValid = Input(Bool())
    val vmEnable = Input(Bool())
    // S0/S1: tlb query and response in next cycle
    val tlb = new TlbRequestIO(nRespDups=2)

    // S1/S2: cache query and response in next cycle
    val dcache = new DCacheLoadIO
    // S1/S2: forward query to sbuffer and response in next cycle
    val forwardFromSBuffer = new LoadForwardQueryIO
    // S1/S2: lpv cancel feedback
    val cancel = Output(Bool())
    // S1/S2: FDI req and response in next cycle
    val fdiReq = ValidIO(new  FDIReqBundle())
    val fdiResp = Flipped(new FDIRespBundle())
    // S1/S2/S3 : forward query to lsq, update lsq, writeback from lsq
    val lsq = new LoadToLsqIO

    // S2: mshrID which handled load miss req
    val loadReqHandledResp = Flipped(ValidIO(UInt(log2Up(cfg.nMissEntries).W)) )
    // S2: pmp query response
    val pmp = Flipped(new PMPRespBundle()) // arrive same to tlb now
    // S2: preftech train output
    val prefetch_train = ValidIO(new LsPipelineBundle())
    // S2: load enq LoadRAWQueue
    val enqRAWQueue = new LoadEnqRAWBundle
    // S2,S3: store violation query
    val storeViolationQuery = Vec(StorePipelineWidth, Flipped(Valid(new storeRAWQueryBundle)))

    // S3: feedback reservationStation to replay
    val feedbackSlow = ValidIO(new RSFeedback)
    val feedbackFast = ValidIO(new RSFeedback) // todo: will be deleted soon
    // S3: replay inst enq replayQueue
    val s3_enq_replayQueue = DecoupledIO(new LoadToReplayQueueBundle)
    // S3: load writeback
    val ldout = Decoupled(new ExuOutput)
    // S3: mmio writeback
    val mmioWb = Flipped(DecoupledIO(new ExuOutput))

    // Global: redirect flush all pipeline
    val redirect = Flipped(ValidIO(new Redirect))
    // Global: debug trigger
    val trigger = Vec(TriggerNum, new LoadUnitTriggerIO)
    // Global: csr control
    val csrCtrl = Flipped(new CustomCSRCtrlIO)
  })

  /*
    LOAD S0: arb 2 input; generate vaddr; req to TLB
  */
  val rsIssueIn = WireInit(io.rsIssueIn)
  val replayIssueIn = WireInit(io.replayQIssueIn)
  io.rsIssueIn.ready := true.B
  io.replayQIssueIn.ready := true.B
  assert(!(rsIssueIn.valid && replayIssueIn.valid))
  def fromRsToS0Bundle(input: ExuInput,inRsIdx: RsIdx): LoadPipelineBundleS0 = {
    val out = WireInit(0.U.asTypeOf(new LoadPipelineBundleS0))
    out.uop := input.uop
    out.src := input.src
    out.vm := input.vm
    out.rsIdx := inRsIdx
    out.vaddr := input.src(0) + SignExt(input.uop.ctrl.imm(11,0), VAddrBits)
    out.replayCause.foreach(_ := false.B)
    out.schedIndex := 0.U
    out.isReplayQReplay := false.B
    out
  }
  def fromRQToS0Bundle(input: ReplayQueueIssueBundle): LoadPipelineBundleS0 = {
    val out = WireInit(0.U.asTypeOf(new LoadPipelineBundleS0))
    out.uop := input.uop
    out.src.foreach(_ := 0.U)
    out.vm := 0.U
    out.rsIdx := DontCare
    out.replayCause.foreach(_ := false.B)
    out.vaddr := input.vaddr
    out.schedIndex := input.schedIndex
    out.isReplayQReplay := true.B
    out
  }

  val s0_src_selector = Seq(
    replayIssueIn.valid,
    rsIssueIn.valid)
  val s0_src = Seq(
    fromRQToS0Bundle(replayIssueIn.bits),
    fromRsToS0Bundle(rsIssueIn.bits, io.rsIdx)
  )
  val s0_sel_src = Wire(new LoadPipelineBundleS0)
  s0_sel_src := ParallelPriorityMux(s0_src_selector, s0_src)

  val s0_imm12 = s0_sel_src.uop.ctrl.imm(11,0)
  val s0_vaddr = s0_sel_src.vaddr
  val s0_mask  = WireInit(genWmask(s0_vaddr, s0_sel_src.uop.ctrl.fuOpType(1,0)))
  val s0_uop   = WireInit(s0_sel_src.uop)
  val s0_auxValid = s0_src_selector.reduce(_ || _)
  val s0_valid = s0_src_selector.reduce(_ || _)
  val s0_EnableMem = s0_sel_src.uop.loadStoreEnable

  val s0_req_tlb = io.tlb.req
  s0_req_tlb := DontCare
  s0_req_tlb.valid := s0_auxValid
  s0_req_tlb.bits.vaddr := s0_vaddr
  s0_req_tlb.bits.cmd := TlbCmd.read
  s0_req_tlb.bits.size := LSUOpType.size(s0_uop.ctrl.fuOpType)
  s0_req_tlb.bits.robIdx := s0_uop.robIdx
  s0_req_tlb.bits.debug.pc := s0_uop.cf.pc

  val s0_req_dcache = io.dcache.req
  val s0_isSoftPrefetch = Mux(replayIssueIn.valid,false.B,LSUOpType.isPrefetch(s0_uop.ctrl.fuOpType))
  val s0_isSoftPrefetchRead = s0_uop.ctrl.fuOpType === LSUOpType.prefetch_r
  val s0_isSoftPrefetchWrite = s0_uop.ctrl.fuOpType === LSUOpType.prefetch_w
  s0_req_dcache.valid := s0_auxValid
  when (s0_isSoftPrefetchRead) {
    s0_req_dcache.bits.cmd  := MemoryOpConstants.M_PFR
  }.elsewhen (s0_isSoftPrefetchWrite) {
    s0_req_dcache.bits.cmd  := MemoryOpConstants.M_PFW
  }.otherwise {
    s0_req_dcache.bits.cmd  := MemoryOpConstants.M_XRD
  }
  s0_req_dcache.bits.robIdx := s0_sel_src.uop.robIdx
  s0_req_dcache.bits.addr := s0_vaddr
  s0_req_dcache.bits.mask := s0_mask
  s0_req_dcache.bits.data := DontCare
  s0_req_dcache.bits.instrtype := Mux(s0_isSoftPrefetch,SOFT_PREFETCH.U,LOAD_SOURCE.U)
  s0_req_dcache.bits.id := DontCare
  val s0_cancel = !s0_req_dcache.ready

  val s0_addrAligned = LookupTree(s0_uop.ctrl.fuOpType(1, 0), List(
    "b00".U   -> true.B,                   //b
    "b01".U   -> (s0_vaddr(0)    === 0.U), //h
    "b10".U   -> (s0_vaddr(1, 0) === 0.U), //w
    "b11".U   -> (s0_vaddr(2, 0) === 0.U)  //d
  ))

  val s0_out = Wire(Decoupled(new LsPipelineBundle))
  s0_out.valid := s0_valid
  s0_out.bits := DontCare
  s0_out.bits.replay.schedIndex := s0_sel_src.schedIndex
  s0_out.bits.replay.isReplayQReplay := s0_sel_src.isReplayQReplay
  s0_out.bits.replay.schedIndex := s0_sel_src.schedIndex
  s0_out.bits.vaddr := s0_vaddr
  s0_out.bits.mask := s0_mask
  s0_out.bits.uop := s0_uop

  private val s0_vaddr2 = SignExt(s0_sel_src.vaddr, XLEN)
  dontTouch(s0_vaddr)
  private val illegalAddr = s0_vaddr2(XLEN - 1, VAddrBits - 1) =/= 0.U && s0_vaddr2(XLEN - 1, VAddrBits - 1) =/= Fill(XLEN - VAddrBits + 1, 1.U(1.W))
  s0_out.bits.uop.cf.exceptionVec(loadAddrMisaligned) := !s0_addrAligned && s0_EnableMem && !s0_isSoftPrefetch
  s0_out.bits.uop.cf.exceptionVec(loadPageFault) := illegalAddr && s0_EnableMem & io.vmEnable && !s0_isSoftPrefetch
  s0_out.bits.rsIdx := io.rsIdx
  s0_out.bits.replay.replayCause.foreach(_ := false.B)
  s0_out.bits.isSoftPrefetch := s0_isSoftPrefetch

  /*
    LOAD S1: process TLB response data; sbuffer/lsq forward query; ld-ld violation query
  */
  val s1_in = Wire(Decoupled(new LsPipelineBundle))
  val s1_out = Wire(Decoupled(new LsPipelineBundle))

  PipelineConnect(s0_out, s1_in, true.B, s0_out.bits.uop.robIdx.needFlush(io.redirect))

  s1_out.bits := s1_in.bits // todo: replace this way of coding!
  //store load violation from storeUnit S1
  val s1_stldViolationVec = Wire(Vec(StorePipelineWidth, Bool()))
  s1_stldViolationVec := io.storeViolationQuery.map({ case req =>
    s1_out.valid && req.valid &&
    isAfter(s1_out.bits.uop.robIdx, req.bits.robIdx) &&
    s1_out.bits.paddr(PAddrBits - 1, 3) === req.bits.paddr &&
      (s1_out.bits.mask & req.bits.mask).orR
  })
  val s1_hasStLdViolation = s1_stldViolationVec.reduce(_ | _)
  dontTouch(s1_hasStLdViolation)

  val s1_dtlbResp = io.tlb.resp
  s1_dtlbResp.ready := true.B

  val s1_uop = s1_in.bits.uop
  val s1_paddr_dup_lsu = s1_dtlbResp.bits.paddr(0)
  val s1_paddr_dup_dcache = s1_dtlbResp.bits.paddr(1)
  io.dcache.s1_paddr_dup_lsu := s1_paddr_dup_lsu
  io.dcache.s1_paddr_dup_dcache := s1_paddr_dup_dcache

  val s1_enableMem = s1_in.bits.uop.loadStoreEnable
  val s1_hasException = Mux(s1_enableMem && s1_in.valid, ExceptionNO.selectByFu(s1_out.bits.uop.cf.exceptionVec, lduCfg).asUInt.orR, false.B)
  val s1_tlb_miss = s1_dtlbResp.bits.miss
  val s1_bank_conflict = io.dcache.s1_bank_conflict
  val s1_cancel_inner = RegEnable(s0_cancel,s0_out.fire)
  val s1_isSoftPrefetch = s1_in.bits.isSoftPrefetch
  val s1_dcacheKill = s1_in.valid && (s1_tlb_miss || s1_hasException || s1_cancel_inner || (!s1_enableMem))
  io.dcache.s1_kill := s1_dcacheKill

  val s1_sbufferForwardReq = io.forwardFromSBuffer
  val s1_lsqForwardReq = io.lsq.forwardFromSQ
  val s1_ldViolationQueryReq = io.lsq.loadViolationQuery.s1_req
  val s1_fdiReq = io.fdiReq

  s1_sbufferForwardReq.valid := s1_in.valid && !(s1_hasException || s1_tlb_miss) && s1_enableMem
  s1_sbufferForwardReq.vaddr := s1_in.bits.vaddr
  s1_sbufferForwardReq.paddr := s1_paddr_dup_lsu
  s1_sbufferForwardReq.uop := s1_uop
  s1_sbufferForwardReq.sqIdx := s1_uop.sqIdx
  s1_sbufferForwardReq.mask := s1_in.bits.mask
  s1_sbufferForwardReq.pc := s1_uop.cf.pc

  s1_lsqForwardReq.valid := s1_in.valid && !(s1_hasException || s1_tlb_miss) && s1_enableMem
  s1_lsqForwardReq.vaddr := s1_in.bits.vaddr
  s1_lsqForwardReq.paddr := s1_paddr_dup_lsu
  s1_lsqForwardReq.uop := s1_uop
  s1_lsqForwardReq.sqIdx := s1_uop.sqIdx
  s1_lsqForwardReq.sqIdxMask := DontCare
  s1_lsqForwardReq.mask := s1_in.bits.mask
  s1_lsqForwardReq.pc := s1_uop.cf.pc
  io.lsq.forwardFromSQ.sqIdxMask := UIntToMask(s1_uop.sqIdx.value, StoreQueueSize)

  s1_ldViolationQueryReq.valid := s1_in.valid && !(s1_hasException || s1_tlb_miss) && s1_enableMem
  s1_ldViolationQueryReq.bits.paddr := s1_paddr_dup_lsu
  s1_ldViolationQueryReq.bits.uop := s1_uop

  s1_fdiReq.valid := s1_out.fire
  s1_fdiReq.bits.addr := s1_out.bits.vaddr
  s1_fdiReq.bits.inUntrustedZone := s1_out.bits.uop.fdiUntrusted
  s1_fdiReq.bits.operation := FDIOp.read

  /* Generate feedback signal caused by:    1.dcache bank conflict    2.need redo ld-ld violation check */
  val s1_csrCtrl_ldld_vio_check_enable = io.csrCtrl.ldld_vio_check_enable
  val s1_needLdVioCheckRedo = s1_ldViolationQueryReq.valid && !s1_ldViolationQueryReq.ready &&
    RegNext(s1_csrCtrl_ldld_vio_check_enable)

  s1_out.valid        := s1_in.valid && s1_enableMem
  s1_out.bits.paddr   := s1_paddr_dup_lsu
  s1_out.bits.tlbMiss := s1_tlb_miss
  val s1_exceptionVec = WireInit(s1_in.bits.uop.cf.exceptionVec)
  s1_out.bits.uop.cf.exceptionVec(loadPageFault)   := (s1_dtlbResp.bits.excp(0).pf.ld || s1_exceptionVec(loadPageFault)) && s1_enableMem && !s1_isSoftPrefetch
  s1_out.bits.uop.cf.exceptionVec(loadAccessFault) := (s1_dtlbResp.bits.excp(0).af.ld || s1_exceptionVec(loadAccessFault)) && s1_enableMem && !s1_isSoftPrefetch
  s1_out.bits.uop.cf.exceptionVec := s1_exceptionVec
  s1_out.bits.ptwBack := s1_dtlbResp.bits.ptwBack
  s1_out.bits.rsIdx   := s1_in.bits.rsIdx
  s1_out.bits.isSoftPrefetch := s1_isSoftPrefetch
  s1_in.ready := !s1_in.valid || s1_out.ready

  val debug_s1_cause = WireInit(0.U.asTypeOf(new ReplayInfo))
  val s1_cause_can_transfer = !(ExceptionNO.selectByFu(s1_out.bits.uop.cf.exceptionVec, lduCfg).asUInt.orR) && (!s1_isSoftPrefetch) && s1_enableMem
  debug_s1_cause.schedIndex := s1_out.bits.replay.schedIndex
  debug_s1_cause.isReplayQReplay := s1_out.bits.replay.isReplayQReplay
  debug_s1_cause.full_fwd := false.B
  debug_s1_cause.fwd_data_sqIdx := 0.U.asTypeOf(new SqPtr)
  debug_s1_cause.tlb_miss := s1_tlb_miss  // tlb resp miss
  debug_s1_cause.raw_nack := s1_hasStLdViolation
  debug_s1_cause.rar_nack := s1_needLdVioCheckRedo  // rar query fail
  debug_s1_cause.bank_conflict := s1_bank_conflict || s1_cancel_inner  // bank read has conflict
  dontTouch(debug_s1_cause)
  /*
    LOAD S2: cache miss control; forward data merge; mmio check; feedback to reservationStation
  */
  val s2_in = Wire(Decoupled(new LsPipelineBundle))
  val s2_out = Wire(Decoupled(new LsPipelineBundle))
  PipelineConnect(s1_out, s2_in, true.B, s1_out.bits.uop.robIdx.needFlush(io.redirect))

  s2_out.valid := s2_in.valid && !s2_in.bits.uop.robIdx.needFlush(io.redirect)
  s2_out.bits := s2_in.bits

  //store load violation from storeUnit S1
  val s2_stldViolationVec = Wire(Vec(StorePipelineWidth, Bool()))
  s2_stldViolationVec := io.storeViolationQuery.map({ case req =>
    s2_in.valid && req.valid &&
      isAfter(s2_in.bits.uop.robIdx, req.bits.robIdx) &&
      s2_in.bits.paddr(PAddrBits - 1, 3) === req.bits.paddr &&
      (s2_in.bits.mask & req.bits.mask).orR
  })
  val s2_hasStLdViolation = s2_stldViolationVec.reduce(_ | _)
  val s2_enqRAWFail = io.enqRAWQueue.s2_enq.valid && !io.enqRAWQueue.s2_enqSuccess
  val s2_allStLdViolation = s2_hasStLdViolation | RegNext(s1_hasStLdViolation,false.B)
  dontTouch(s2_hasStLdViolation)

  val s2_pmp = WireInit(io.pmp)
  val s2_static_pm = RegEnable(io.tlb.resp.bits.static_pm, io.tlb.resp.valid)
  when(s2_static_pm.valid) {
    s2_pmp.ld := false.B
    s2_pmp.st := false.B
    s2_pmp.instr := false.B
    s2_pmp.mmio := s2_static_pm.bits
  }

  val s2_cancel_inner = RegEnable(s1_cancel_inner,s1_out.fire)
  val s2_enableMem = s2_in.bits.uop.loadStoreEnable && s2_in.valid
  val s2_isSoftPrefetch = s2_in.bits.isSoftPrefetch
  val s2_exceptionVec = WireInit(s2_in.bits.uop.cf.exceptionVec)
  s2_out.bits.uop.cf.exceptionVec(loadAccessFault) := (s2_exceptionVec(loadAccessFault) || s2_pmp.ld) && s2_enableMem && !s2_isSoftPrefetch
  s2_out.bits.uop.cf.exceptionVec(fdiULoadAccessFault) := (io.fdiResp.fdi_fault === FDICheckFault.UReadDascisFault) && s2_enableMem  //FDI load access fault
  val s2_hasException = Mux(s2_enableMem, ExceptionNO.selectByFu(s2_exceptionVec, lduCfg).asUInt.orR,false.B)

  val s2_dcacheResp = io.dcache.resp
  val s2_dcacheMshrID = io.loadReqHandledResp
  dontTouch(s2_dcacheMshrID)

  val s2_LSQ_LoadForwardQueryIO = Wire(new LoadForwardQueryIO)
  val s2_SB_LoadForwardQueryIO = Wire(new LoadForwardQueryIO)
  val s2_loadViolationQueryResp = Wire(ValidIO(new LoadViolationQueryResp))

  s2_LSQ_LoadForwardQueryIO := DontCare
  s2_SB_LoadForwardQueryIO := DontCare
  s2_dcacheResp.ready := true.B

  s2_LSQ_LoadForwardQueryIO.forwardData := io.lsq.forwardFromSQ.forwardData
  s2_LSQ_LoadForwardQueryIO.forwardMask := io.lsq.forwardFromSQ.forwardMask
  s2_LSQ_LoadForwardQueryIO.dataInvalid := io.lsq.forwardFromSQ.dataInvalid
  s2_LSQ_LoadForwardQueryIO.matchInvalid := io.lsq.forwardFromSQ.matchInvalid

  s2_SB_LoadForwardQueryIO.forwardData := io.forwardFromSBuffer.forwardData
  s2_SB_LoadForwardQueryIO.forwardMask := io.forwardFromSBuffer.forwardMask
  s2_SB_LoadForwardQueryIO.dataInvalid := io.forwardFromSBuffer.dataInvalid // always false
  s2_SB_LoadForwardQueryIO.matchInvalid := io.forwardFromSBuffer.matchInvalid

  s2_loadViolationQueryResp := io.lsq.loadViolationQuery.s2_resp

  val s2_tlb_miss = s2_in.bits.tlbMiss
  val s2_actually_mmio = s2_pmp.mmio && !s2_tlb_miss
  val s2_mmio = !s2_isSoftPrefetch && s2_actually_mmio && !s2_hasException
  val s2_cache_miss = s2_dcacheResp.bits.miss
  val s2_cache_replay = s2_dcacheResp.bits.replay

  val s2_ldld_violation = s2_loadViolationQueryResp.valid && s2_loadViolationQueryResp.bits.have_violation &&
    RegNext(io.csrCtrl.ldld_vio_check_enable)
  val s2_data_invalid = s2_LSQ_LoadForwardQueryIO.dataInvalid && !s2_ldld_violation && !s2_hasException

  val s2_dcache_kill = s2_pmp.ld || s2_pmp.mmio // move pmp resp kill to outside
  // to kill mmio resp which are redirected
  io.dcache.s2_kill := s2_dcache_kill

  val s2_dcacheShouldResp = !(s2_tlb_miss || s2_hasException || s2_mmio || s2_isSoftPrefetch)
  assert(!(s2_enableMem && (!s2_cancel_inner && s2_dcacheShouldResp && !s2_dcacheResp.valid)), "DCache response got lost")

  // merge forward result, lsq has higher priority than sbuffer
  val s2_forwardMask = Wire(Vec(8, Bool()))
  val s2_forwardData = Wire(Vec(8, UInt(8.W)))
  for (i <- 0 until XLEN / 8) {
    s2_forwardMask(i) := s2_LSQ_LoadForwardQueryIO.forwardMask(i) || s2_SB_LoadForwardQueryIO.forwardMask(i)
    s2_forwardData(i) := Mux(s2_LSQ_LoadForwardQueryIO.forwardMask(i), s2_LSQ_LoadForwardQueryIO.forwardData(i), s2_SB_LoadForwardQueryIO.forwardData(i))
  }
  val s2_fullForward = s2_out.valid && !s2_tlb_miss && ((~s2_forwardMask.asUInt).asUInt & s2_in.bits.mask) === 0.U && !s2_LSQ_LoadForwardQueryIO.dataInvalid


  // dcache load data
  val s2_loadDataFromDcache = Wire(new LoadDataFromDcacheBundle)
  s2_loadDataFromDcache.load_data := Mux(s2_dcacheResp.bits.miss, 0.U, s2_dcacheResp.bits.load_data) //to cut X-prop
  s2_loadDataFromDcache.forwardMask := s2_forwardMask
  s2_loadDataFromDcache.forwardData := s2_forwardData
  s2_loadDataFromDcache.uop := s2_out.bits.uop
  s2_loadDataFromDcache.addrOffset := s2_in.bits.paddr(2, 0)

  // real miss: dcache miss as well as can't forward
  s2_out.bits.miss := s2_cache_miss && !s2_hasException && !s2_fullForward && !s2_ldld_violation &&
    !s2_isSoftPrefetch && s2_enableMem
  s2_out.bits.uop.ctrl.fpWen := s2_in.bits.uop.ctrl.fpWen && !s2_hasException
  s2_out.bits.mmio := s2_mmio && s2_enableMem

  s2_out.bits.forwardMask := s2_forwardMask
  s2_out.bits.forwardData := s2_forwardData // data from dcache is not included in io.out.bits.forwardData
  s2_in.ready := s2_out.ready || !s2_in.valid

  val s2_dataForwarded = (s2_cache_miss || s2_cache_replay) && !s2_hasException && s2_fullForward

  val s2_rsFeedback = Wire(ValidIO(new RSFeedback))
  s2_rsFeedback.valid := s2_in.valid && (!s2_in.bits.replay.isReplayQReplay)
  s2_rsFeedback.bits.rsIdx := s2_in.bits.rsIdx
  s2_rsFeedback.bits.sourceType := Mux(!io.s3_enq_replayQueue.ready, RSFeedbackType.replayQFull,RSFeedbackType.success)
  dontTouch(s2_rsFeedback)

  // provide paddr for lq
  io.lsq.s1_lduMMIOPAddr.valid := s1_out.valid && !s1_tlb_miss
  io.lsq.s1_lduMMIOPAddr.bits.lqIdx := s1_out.bits.uop.lqIdx
  io.lsq.s1_lduMMIOPAddr.bits.paddr := s1_paddr_dup_lsu
  io.lsq.s2_load_data_forwarded := s2_dataForwarded

  // provide prefetcher train data
  io.prefetch_train.bits := s2_in.bits
  io.prefetch_train.bits.miss := io.dcache.resp.bits.miss
  io.prefetch_train.valid := s2_in.fire && !s2_out.bits.mmio && !s2_in.bits.tlbMiss

  // todo: delete feedback fast
  io.feedbackFast.valid := false.B
  io.feedbackFast.bits := DontCare

  // writeback to LSQ, Load queue will be updated at s2 for both hit/miss int/fp load
  io.lsq.s2_lduUpdateLQ.valid := s2_out.valid
  io.lsq.s2_lduUpdateLQ.bits.fromLsPipelineBundle(s2_out.bits) // generate LqWriteBundle from LsPipelineBundle
  // generate duplicated load queue data wen
  val s2_wen_dup = RegInit(VecInit(Seq.fill(6)(false.B)))
  s2_wen_dup.foreach(_ := s1_out.valid && (!s1_out.bits.uop.robIdx.needFlush(io.redirect)))
  io.lsq.s2_lduUpdateLQ.bits.lq_data_wen_dup := s2_wen_dup

//  val s2_dcache_require_replay = s2_cache_replay && s2_rsFeedback.valid && !s2_dataForwarded && !s2_isSoftPrefetch && s2_out.bits.miss
//  io.lsq.s2_dcache_require_replay := s2_dcache_require_replay

  val exceptionWb = s2_hasException
  val normalWb = !s2_tlb_miss &&
    (!(s2_cache_miss || s2_cache_replay) || s2_fullForward) &&
    !s2_data_invalid && !s2_mmio &&
    !s2_allStLdViolation &&
    !s2_enqRAWFail &&
    !RegNext(s1_bank_conflict)

  val s2_wb_valid = !s2_cancel_inner && s2_in.valid && !s2_in.bits.uop.robIdx.needFlush(io.redirect) && (exceptionWb ||
    normalWb)

  when(s2_in.valid) {
    assert(!(s2_tlb_miss && s2_fullForward),"when s2_tlb_miss,s2_fullForward must be false!!")
  }

  // Int load, if hit, will be writebacked at s2
  val hitLoadOut = Wire(Valid(new ExuOutput))
  hitLoadOut := DontCare
  hitLoadOut.valid := s2_wb_valid
  hitLoadOut.bits.uop := s2_out.bits.uop
  hitLoadOut.bits.data := s2_out.bits.data
  hitLoadOut.bits.redirectValid := false.B
  hitLoadOut.bits.redirect := DontCare
  hitLoadOut.bits.debug.isMMIO := s2_out.bits.mmio
  hitLoadOut.bits.debug.isPerfCnt := false.B
  hitLoadOut.bits.debug.paddr := s2_out.bits.paddr
  hitLoadOut.bits.debug.vaddr := s2_out.bits.vaddr
  hitLoadOut.bits.fflags := DontCare

  s2_out.ready := true.B
  val debug_s2_cause = WireInit(0.U.asTypeOf(new ReplayInfo))
  val debugS2CauseReg = RegInit(0.U.asTypeOf(new ReplayInfo))
  debugS2CauseReg := Mux(s1_cause_can_transfer && s1_out.valid, debug_s1_cause, 0.U.asTypeOf(new ReplayInfo))
  val s2_cause_can_transfer = (!ExceptionNO.selectByFu(s2_out.bits.uop.cf.exceptionVec, lduCfg).asUInt.orR) && !s2_in.bits.isSoftPrefetch && !s2_mmio && s2_enableMem
  debug_s2_cause := debugS2CauseReg
  debug_s2_cause.full_fwd := s2_fullForward
  debug_s2_cause.fwd_data_sqIdx := Mux(s2_data_invalid, io.lsq.forwardFromSQ.dataInvalidSqIdx, 0.U.asTypeOf(new SqPtr))
  debug_s2_cause.dcache_miss := (s2_cache_miss && !RegNext(s1_bank_conflict)) || debugS2CauseReg.dcache_miss
  debug_s2_cause.fwd_fail    := s2_data_invalid || debugS2CauseReg.fwd_fail
  debug_s2_cause.dcache_rep  := s2_cache_replay || debugS2CauseReg.dcache_rep
  debug_s2_cause.raw_nack := s2_hasStLdViolation || debugS2CauseReg.raw_nack || s2_enqRAWFail
  dontTouch(debug_s2_cause)

  /*
    LOAD S3: writeback data merge; writeback control
  */
  val s3_in = Wire(Decoupled(new LsPipelineBundle))
  s3_in.ready := true.B
  PipelineConnect(s2_out, s3_in, true.B, s2_out.bits.uop.robIdx.needFlush(io.redirect))

  // mmio data from load queue
  val s3_mmioDataFromLq = RegEnable(io.mmioWb.bits.data, io.mmioWb.valid)
  // data from dcache hit
  val s3_loadDataFromDcache = RegEnable(s2_loadDataFromDcache, s2_in.valid)
  val s3_rdataDcache = s3_loadDataFromDcache.mergedData()

  private val hitLoadOutValidReg = RegNext(hitLoadOut.valid, false.B)
  val s3_uop = s3_loadDataFromDcache.uop
  val s3_offset = s3_loadDataFromDcache.addrOffset
  val s3_rdata_dup = WireInit(VecInit(List.fill(8)(0.U(64.W))))
  s3_rdata_dup.zipWithIndex.foreach({case(d,i) => {
    d := s3_rdataDcache
  }})

  val s3_sel_rdata = LookupTree(s3_offset,List(
    "b000".U -> s3_rdata_dup(0)(63, 0),
    "b001".U -> s3_rdata_dup(1)(63, 8),
    "b010".U -> s3_rdata_dup(2)(63, 16),
    "b011".U -> s3_rdata_dup(3)(63, 24),
    "b100".U -> s3_rdata_dup(4)(63, 32),
    "b101".U -> s3_rdata_dup(5)(63, 40),
    "b110".U -> s3_rdata_dup(6)(63, 48),
    "b111".U -> s3_rdata_dup(7)(63, 56)
  ))
  val s3_rdataPartialLoad = rdataHelper(s3_uop,s3_sel_rdata)

  private val s3_lsqMMIOOutputValid = RegNext(io.mmioWb.valid && (!io.mmioWb.bits.uop.robIdx.needFlush(io.redirect)),false.B)
  io.ldout.valid := hitLoadOutValidReg || s3_lsqMMIOOutputValid
  val s3_load_wb_meta_reg = RegEnable(Mux(hitLoadOut.valid,hitLoadOut.bits,io.mmioWb.bits), hitLoadOut.valid | io.mmioWb.valid)
  io.ldout.bits := s3_load_wb_meta_reg
  io.ldout.bits.data := Mux(hitLoadOutValidReg, s3_rdataPartialLoad, s3_load_wb_meta_reg.data)

  // feedback tlb miss / dcache miss queue full
  io.feedbackSlow.valid := RegNext(s2_rsFeedback.valid && !s2_out.bits.uop.robIdx.needFlush(io.redirect), false.B)
  io.feedbackSlow.bits.rsIdx := RegNext(s2_rsFeedback.bits.rsIdx)
  io.feedbackSlow.bits.sourceType := RegNext(s2_rsFeedback.bits.sourceType)

  assert(!(RegNext(io.feedbackFast.valid) && io.feedbackSlow.valid))

  // load forward_fail/ldld_violation check
  // check for inst in load pipeline
  val s3_forward_fail = RegNext(io.lsq.forwardFromSQ.matchInvalid || io.forwardFromSBuffer.matchInvalid)
  val s3_ldld_violation = RegNext(s2_ldld_violation)
  val s3_need_replay_from_fetch = s3_forward_fail || s3_ldld_violation
  val s3_can_replay_from_fetch = RegEnable(s2_out.bits.mmio && !s2_out.bits.isSoftPrefetch && s2_out.bits.tlbMiss, s2_out.valid)

  when (RegNext(hitLoadOut.valid)) {
    io.ldout.bits.uop.ctrl.replayInst := s3_need_replay_from_fetch
  }

  io.mmioWb.ready := !hitLoadOut.valid

  val lastValidData = RegEnable(io.ldout.bits.data, io.ldout.fire)
  val hitLoadAddrTriggerHitVec = Wire(Vec(TriggerNum, Bool()))
  val lqLoadAddrTriggerHitVec = io.lsq.trigger.lqLoadAddrTriggerHitVec
  (0 until TriggerNum).foreach{ i => {
    val tdata2 = io.trigger(i).tdata2
    val matchType = io.trigger(i).matchType
    val tEnable = io.trigger(i).tEnable

    hitLoadAddrTriggerHitVec(i) := TriggerCmp(s2_out.bits.vaddr, tdata2, matchType, tEnable)
    io.trigger(i).addrHit := RegNext(Mux(hitLoadOut.valid, hitLoadAddrTriggerHitVec(i), lqLoadAddrTriggerHitVec(i)))
    io.trigger(i).lastDataHit := TriggerCmp(lastValidData, tdata2, matchType, tEnable)
  }}
  io.lsq.trigger.hitLoadAddrTriggerHitVec := hitLoadAddrTriggerHitVec

  // lvp cancel feedback to reservationStation
  private val s1_cancel = RegInit(false.B)
  s1_cancel := s1_in.valid && (!s1_out.valid)
  val s2_lpvCancel = s2_in.valid && (s2_tlb_miss || s2_mmio || s2_LSQ_LoadForwardQueryIO.dataInvalid || s2_cache_miss || s2_cache_replay)
  io.cancel := s1_cancel || s2_lpvCancel

  val debugS3CauseReg = RegInit(0.U.asTypeOf(new ReplayInfo))
  debugS3CauseReg := Mux( s2_cause_can_transfer && s2_out.fire && !s2_wb_valid, debug_s2_cause, 0.U.asTypeOf(new ReplayInfo))
  dontTouch(debugS3CauseReg)
  val temp_other_cause = debugS3CauseReg.replayCause
  .updated(LoadReplayCauses.C_TM, false.B)
  .updated(LoadReplayCauses.C_DM, false.B)
  .updated(LoadReplayCauses.C_FF, false.B)
  .updated(LoadReplayCauses.C_DR, false.B)
  .reduce(_ || _)
  val s3_dcacheMshrID = RegEnable(s2_dcacheMshrID.bits, s2_dcacheMshrID.valid)

  dontTouch(s3_dcacheMshrID)
  //write back control info to replayQueue in S3
//  val hasOtherCause = s2_out.valid && (s2_need_replay_from_rs)
  io.s3_enq_replayQueue.valid := s3_in.valid && !s3_in.bits.uop.robIdx.needFlush(io.redirect)
  io.s3_enq_replayQueue.bits.vaddr := s3_in.bits.vaddr
  io.s3_enq_replayQueue.bits.paddr := DontCare
  io.s3_enq_replayQueue.bits.replay.isReplayQReplay := s3_in.bits.replay.isReplayQReplay
  io.s3_enq_replayQueue.bits.replay.replayCause := DontCare
  io.s3_enq_replayQueue.bits.replay.replayCause(LoadReplayCauses.C_TM) := debugS3CauseReg.tlb_miss
  io.s3_enq_replayQueue.bits.replay.replayCause(LoadReplayCauses.C_FF) := debugS3CauseReg.fwd_fail
  io.s3_enq_replayQueue.bits.replay.replayCause(LoadReplayCauses.C_DR) := debugS3CauseReg.dcache_rep
  io.s3_enq_replayQueue.bits.replay.replayCause(LoadReplayCauses.C_DM) := debugS3CauseReg.dcache_miss
  io.s3_enq_replayQueue.bits.replay.replayCause(LoadReplayCauses.C_BC) := temp_other_cause
  io.s3_enq_replayQueue.bits.replay.schedIndex := s3_in.bits.replay.schedIndex
  io.s3_enq_replayQueue.bits.replay.fwd_data_sqIdx := debugS3CauseReg.fwd_data_sqIdx
  io.s3_enq_replayQueue.bits.replay.full_fwd := RegNext(s2_dataForwarded)
  io.s3_enq_replayQueue.bits.mshrMissIDResp := s3_dcacheMshrID
  io.s3_enq_replayQueue.bits.uop := s3_in.bits.uop
  io.s3_enq_replayQueue.bits.mask := s3_in.bits.mask
  io.s3_enq_replayQueue.bits.tlbMiss := false.B


  assert(!(RegNext(hitLoadOut.valid,false.B) && io.s3_enq_replayQueue.bits.replay.replayCause.reduce(_|_)),"when load" +
    " wb," + "replayCause must be 0!!")


  val s3_needReplay = io.s3_enq_replayQueue.valid && io.s3_enq_replayQueue.bits.replay.replayCause.reduce(_|_)

  io.enqRAWQueue.s2_enq.valid := s2_wb_valid
  io.enqRAWQueue.s2_enq.bits.paddr := s2_out.bits.paddr(PAddrBits - 1, 3)
  io.enqRAWQueue.s2_enq.bits.mask := s2_out.bits.mask
  io.enqRAWQueue.s2_enq.bits.sqIdx := s2_out.bits.uop.sqIdx
  io.enqRAWQueue.s2_enq.bits.robIdx := s2_out.bits.uop.robIdx
  io.enqRAWQueue.s2_enq.bits.ftqPtr := s2_out.bits.uop.cf.ftqPtr
  io.enqRAWQueue.s2_enq.bits.ftqOffset := s2_out.bits.uop.cf.ftqOffset
  io.enqRAWQueue.s3_cancel := RegNext(io.enqRAWQueue.s2_enq.valid && io.enqRAWQueue.s2_enqSuccess,false.B) && s3_needReplay

  val perfEvents = Seq(
    ("load_s0_in_fire         ", s0_valid),
    ("load_to_load_forward    ", s1_out.valid),
    ("stall_dcache            ", s0_out.valid && s0_out.ready && !s0_req_dcache.ready),
    ("load_s1_in_fire         ", s1_in.fire),
    ("load_s1_tlb_miss        ", s1_in.fire && s1_dtlbResp.bits.miss),
    ("load_s2_in_fire         ", s2_in.fire),
    ("load_s2_dcache_miss     ", s2_in.fire && s2_dcacheResp.bits.miss),
    ("load_s2_replay          ", s2_rsFeedback.valid),
    ("load_s2_replay_tlb_miss ", s2_rsFeedback.valid && s2_in.bits.tlbMiss),
    ("load_s2_replay_cache    ", s2_rsFeedback.valid && !s2_in.bits.tlbMiss && s2_dcacheResp.bits.miss),
  )
  generatePerfEvent()

  when(io.ldout.fire){
    XSDebug("ldout %x\n", io.ldout.bits.uop.cf.pc)
  }

  val debugModule = Module(new LoadUnitDebugInfo)
  debugModule.io.infoIn.s0_valid := s0_valid
  debugModule.io.infoIn.s1_valid := s1_in.valid
  debugModule.io.infoIn.s2_valid := s2_in.valid
  debugModule.io.infoIn.s3_valid := s3_in.valid
  debugModule.io.infoIn.wb_valid := io.ldout.valid
  debugModule.io.infoIn.rsIdx := io.rsIdx
  debugModule.io.infoIn.robIdx := s0_sel_src.uop.robIdx
}

class LdDebugBundle(implicit p: Parameters) extends XSBundle {
  val s0_valid = Bool()
  val s1_valid = Bool()
  val s2_valid = Bool()
  val s3_valid = Bool()
  val wb_valid = Bool()
  val rsIdx = new RsIdx()
  val robIdx = new RobPtr()
}

class LoadUnitDebugInfo(implicit p: Parameters) extends XSModule{
  val io = IO(new Bundle(){
    val infoIn = Input(new LdDebugBundle)
  })

  val debugReg = RegInit(0.U.asTypeOf(new LdDebugBundle))
  debugReg := io.infoIn
  dontTouch(debugReg)
}

