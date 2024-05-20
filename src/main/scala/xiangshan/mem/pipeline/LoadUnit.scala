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
import xiangshan.cache._
import xiangshan.cache.mmu.{TlbCmd, TlbReq, TlbRequestIO, TlbResp}
import xs.utils.perf.HasPerfLogging

class LoadToLsqIO(implicit p: Parameters) extends XSBundle {
  val loadIn = ValidIO(new LqWriteBundle)
  val loadPaddrIn = ValidIO(new LqPaddrWriteBundle)
  val ldout = Flipped(DecoupledIO(new ExuOutput))
  val ldRawData = Input(new LoadDataFromLQBundle)
  val s2_load_data_forwarded = Output(Bool())
  val s3_delayed_load_error = Output(Bool())
  val s2_dcache_require_replay = Output(Bool())
  val s3_replay_from_fetch = Output(Bool()) // update uop.ctrl.replayInst in load queue in s3
  val forward = new PipeLoadForwardQueryIO
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

// Load Pipeline Stage 0
// Generate addr, use addr to query DCache and DTLB
class LoadUnit_S0(implicit p: Parameters) extends XSModule with HasDCacheParameters  with HasPerfLogging{
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new ExuInput))
    val auxValid = Input(Bool())
    val out = Decoupled(new LsPipelineBundle)
    val dtlbReq = DecoupledIO(new TlbReq)
    val dcacheReq = DecoupledIO(new DCacheWordReq)
    val rsIdx = Input(new RsIdx)
    val s0_kill = Input(Bool())
    val s0_cancel = Output(Bool())
    val vmEnable = Input(Bool())
  })
  require(LoadPipelineWidth == exuParameters.LduCnt)

  val imm12 = io.in.bits.uop.ctrl.imm(11, 0)
  val s0_vaddr = WireInit(io.in.bits.src(0) + SignExt(imm12, VAddrBits))
  val s0_mask = WireInit(genWmask(s0_vaddr, io.in.bits.uop.ctrl.fuOpType(1,0)))
  val s0_uop = WireInit(io.in.bits.uop)


  val isSoftPrefetch = LSUOpType.isPrefetch(s0_uop.ctrl.fuOpType)
  val isSoftPrefetchRead = s0_uop.ctrl.fuOpType === LSUOpType.prefetch_r
  val isSoftPrefetchWrite = s0_uop.ctrl.fuOpType === LSUOpType.prefetch_w
  val EnableMem = io.in.bits.uop.loadStoreEnable

  // query DTLB
  io.dtlbReq := DontCare
  io.dtlbReq.valid := io.auxValid
  io.dtlbReq.bits.vaddr := s0_vaddr
  io.dtlbReq.bits.cmd := TlbCmd.read
  io.dtlbReq.bits.size := LSUOpType.size(s0_uop.ctrl.fuOpType)
  io.dtlbReq.bits.robIdx := s0_uop.robIdx
  io.dtlbReq.bits.debug.pc := s0_uop.cf.pc

  // query DCache
  io.dcacheReq.valid := io.auxValid
  when (isSoftPrefetchRead) {
    io.dcacheReq.bits.cmd  := MemoryOpConstants.M_PFR
  }.elsewhen (isSoftPrefetchWrite) {
    io.dcacheReq.bits.cmd  := MemoryOpConstants.M_PFW
  }.otherwise {
    io.dcacheReq.bits.cmd  := MemoryOpConstants.M_XRD
  }
  io.dcacheReq.bits.robIdx := io.in.bits.uop.robIdx
  io.dcacheReq.bits.addr := s0_vaddr
  io.dcacheReq.bits.mask := s0_mask
  io.dcacheReq.bits.data := DontCare
  when(isSoftPrefetch) {
    io.dcacheReq.bits.instrtype := SOFT_PREFETCH.U
  }.otherwise {
    io.dcacheReq.bits.instrtype := LOAD_SOURCE.U
  }

  // TODO: update cache meta
  io.dcacheReq.bits.id   := DontCare

  val addrAligned = LookupTree(s0_uop.ctrl.fuOpType(1, 0), List(
    "b00".U   -> true.B,                   //b
    "b01".U   -> (s0_vaddr(0)    === 0.U), //h
    "b10".U   -> (s0_vaddr(1, 0) === 0.U), //w
    "b11".U   -> (s0_vaddr(2, 0) === 0.U)  //d
  ))

  io.out.valid := io.in.valid

  io.out.bits := DontCare
  io.out.bits.vaddr := s0_vaddr
  io.out.bits.mask := s0_mask
  io.out.bits.uop := s0_uop

  private val vaddr = io.in.bits.src(0) + SignExt(imm12, XLEN)
  dontTouch(vaddr)
  private val illegalAddr = vaddr(XLEN - 1, VAddrBits - 1) =/= 0.U && vaddr(XLEN - 1, VAddrBits - 1) =/= Fill(XLEN - VAddrBits + 1, 1.U(1.W))
  io.out.bits.uop.cf.exceptionVec(loadAddrMisaligned) := Mux(EnableMem, !addrAligned, false.B)
  io.out.bits.uop.cf.exceptionVec(loadPageFault) := Mux(EnableMem & io.vmEnable, illegalAddr, false.B)

  io.out.bits.rsIdx := io.rsIdx
  io.out.bits.isSoftPrefetch := isSoftPrefetch

  io.in.ready := !io.in.valid || io.out.ready
  io.s0_cancel := (!io.dcacheReq.ready) || io.s0_kill

  XSDebug(io.dcacheReq.fire,
    p"[DCACHE LOAD REQ] pc ${Hexadecimal(s0_uop.cf.pc)}, vaddr ${Hexadecimal(s0_vaddr)}\n"
  )
  XSPerfAccumulate("in_valid", io.in.valid)
  XSPerfAccumulate("in_fire", io.in.fire)
  XSPerfAccumulate("stall_out", io.out.valid && !io.out.ready && io.dcacheReq.ready)
  XSPerfAccumulate("stall_dcache", io.out.valid && io.out.ready && !io.dcacheReq.ready)
  XSPerfAccumulate("addr_spec_success", io.out.fire && s0_vaddr(VAddrBits-1, 12) === io.in.bits.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("addr_spec_failed", io.out.fire && s0_vaddr(VAddrBits-1, 12) =/= io.in.bits.src(0)(VAddrBits-1, 12))
}


class LoadUnit_S1(implicit p: Parameters) extends XSModule with HasPerfLogging{
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val s1_kill = Input(Bool())
    val out = Decoupled(new LsPipelineBundle)
    val dtlbResp = Flipped(DecoupledIO(new TlbResp(2)))
    val lsuPAddr = Output(UInt(PAddrBits.W))
    val dcachePAddr = Output(UInt(PAddrBits.W))
    val dcacheKill = Output(Bool())
    val dcacheBankConflict = Input(Bool())
    val fullForwardFast = Output(Bool())
    val sbuffer = new LoadForwardQueryIO
    val lsq = new PipeLoadForwardQueryIO
    val loadViolationQueryReq = Decoupled(new LoadViolationQueryReq)
    val rsFeedback = ValidIO(new RSFeedback)
    val csrCtrl = Flipped(new CustomCSRCtrlIO)
    val needLdVioCheckRedo = Output(Bool())
    val s1_cancel = Input(Bool())
    val bankConflictAvoidIn = Input(UInt(1.W))
    val fdiReq = ValidIO(new FDIReqBundle())
  })

  val s1_uop = io.in.bits.uop
  val s1_paddr_dup_lsu = io.dtlbResp.bits.paddr(0)
  val s1_paddr_dup_dcache = io.dtlbResp.bits.paddr(1)
  val EnableMem = io.in.bits.uop.loadStoreEnable
  // af & pf exception were modified below.
  val s1_exception = Mux(EnableMem && io.in.valid, ExceptionNO.selectByFu(io.out.bits.uop.cf.exceptionVec, lduCfg).asUInt.orR, false.B)
  val s1_tlb_miss = io.dtlbResp.bits.miss
  val s1_mask = io.in.bits.mask
  val s1_bank_conflict = io.dcacheBankConflict

  io.out.bits := io.in.bits // forwardXX field will be updated in s1

  io.dtlbResp.ready := true.B

  io.lsuPAddr := s1_paddr_dup_lsu
  io.dcachePAddr := s1_paddr_dup_dcache
  io.dcacheKill := io.in.valid && (s1_tlb_miss || s1_exception || io.s1_kill || io.s1_cancel || (!EnableMem))
  // load forward query datapath
  io.sbuffer.valid := io.in.valid && !(s1_exception || s1_tlb_miss || io.s1_kill) && EnableMem
  io.sbuffer.vaddr := io.in.bits.vaddr
  io.sbuffer.paddr := s1_paddr_dup_lsu
  io.sbuffer.uop := s1_uop
  io.sbuffer.sqIdx := s1_uop.sqIdx
  io.sbuffer.mask := s1_mask
  io.sbuffer.pc := s1_uop.cf.pc // FIXME: remove it

  io.lsq.valid := io.in.valid && !(s1_exception || s1_tlb_miss || io.s1_kill) && EnableMem
  io.lsq.vaddr := io.in.bits.vaddr
  io.lsq.paddr := s1_paddr_dup_lsu
  io.lsq.uop := s1_uop
  io.lsq.sqIdx := s1_uop.sqIdx
  io.lsq.sqIdxMask := DontCare // will be overwritten by sqIdxMask pre-generated in s0
  io.lsq.mask := s1_mask
  io.lsq.pc := s1_uop.cf.pc // FIXME: remove it

  // ld-ld violation query
  io.loadViolationQueryReq.valid := io.in.valid && !(s1_exception || s1_tlb_miss || io.s1_kill) && EnableMem
  io.loadViolationQueryReq.bits.paddr := s1_paddr_dup_lsu
  io.loadViolationQueryReq.bits.uop := s1_uop

  //FDI check
  io.fdiReq.valid := io.out.fire //TODO: temporarily assignment
  io.fdiReq.bits.addr := io.out.bits.vaddr //TODO: need for alignment?
  io.fdiReq.bits.inUntrustedZone := io.out.bits.uop.fdiUntrusted
  io.fdiReq.bits.operation := FDIOp.read

  // Generate forwardMaskFast to wake up insts earlier
  val forwardMaskFast = io.lsq.forwardMaskFast.asUInt | io.sbuffer.forwardMaskFast.asUInt
  io.fullForwardFast := ((~forwardMaskFast).asUInt & s1_mask) === 0.U

  // Generate feedback signal caused by:
  // * dcache bank conflict
  // * need redo ld-ld violation check
  val needLdVioCheckRedo = io.loadViolationQueryReq.valid &&
    !io.loadViolationQueryReq.ready &&
    RegNext(io.csrCtrl.ldld_vio_check_enable)
  io.needLdVioCheckRedo := needLdVioCheckRedo
  io.rsFeedback.valid := io.in.valid && (s1_bank_conflict || needLdVioCheckRedo || io.s1_cancel) && !io.s1_kill && EnableMem
  io.rsFeedback.bits.rsIdx := io.in.bits.rsIdx
  io.rsFeedback.bits.flushState := io.in.bits.ptwBack
  io.rsFeedback.bits.sourceType := Mux(s1_bank_conflict, RSFeedbackType.bankConflict, RSFeedbackType.ldVioCheckRedo)

  // if replay is detected in load_s1,
  // load inst will be canceled immediately
  io.out.valid := io.in.valid && (!io.rsFeedback.valid && !io.s1_kill || !EnableMem)
  io.out.bits.paddr := s1_paddr_dup_lsu
  io.out.bits.tlbMiss := s1_tlb_miss

  // current ori test will cause the case of ldest == 0, below will be modifeid in the future.
  // af & pf exception were modified
  io.out.bits.uop.cf.exceptionVec(loadPageFault) := (io.dtlbResp.bits.excp(0).pf.ld || io.in.bits.uop.cf.exceptionVec(loadPageFault)) && EnableMem
  io.out.bits.uop.cf.exceptionVec(loadAccessFault) := io.dtlbResp.bits.excp(0).af.ld && EnableMem

  io.out.bits.ptwBack := io.dtlbResp.bits.ptwBack
  io.out.bits.rsIdx := io.in.bits.rsIdx

  io.out.bits.isSoftPrefetch := io.in.bits.isSoftPrefetch

  io.in.ready := !io.in.valid || io.out.ready

  XSPerfAccumulate("in_valid", io.in.valid)
  XSPerfAccumulate("in_fire", io.in.fire)
  XSPerfAccumulate("replay",  io.rsFeedback.valid)
  XSPerfAccumulate("replay_bankconflict",  io.rsFeedback.valid && s1_bank_conflict)
  XSPerfAccumulate("tlb_miss", io.in.fire && s1_tlb_miss)
  XSPerfAccumulate("stall_out", io.out.valid && !io.out.ready)
}

// Load Pipeline Stage 2
// DCache resp
class LoadUnit_S2(implicit p: Parameters) extends XSModule with HasLoadHelper with HasPerfLogging {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val out = Decoupled(new LsPipelineBundle)
    val rsFeedback = ValidIO(new RSFeedback)
    val dcacheResp = Flipped(DecoupledIO(new BankedDCacheWordResp))
    val pmpResp = Flipped(new PMPRespBundle())
    val lsq = new LoadForwardQueryIO
    val dataInvalidSqIdx = Input(UInt())  //not use
    val sbuffer = new LoadForwardQueryIO
    val dataForwarded = Output(Bool())
    val s2_dcache_require_replay = Output(Bool())
    val fullForward = Output(Bool())
    val dcache_kill = Output(Bool())
    val s3_delayed_load_error = Output(Bool())
    val loadViolationQueryResp = Flipped(Valid(new LoadViolationQueryResp))
    val csrCtrl = Flipped(new CustomCSRCtrlIO)
    val static_pm = Input(Valid(Bool())) // valid for static, bits for mmio
    val s2_can_replay_from_fetch = Output(Bool()) // dirty code
    val loadDataFromDcache = Output(new LoadDataFromDcacheBundle)
    val lpvCancel = Output(Bool())
    val fdiResp = Flipped(new FDIRespBundle)
  })

  val pmp = WireInit(io.pmpResp)
  when (io.static_pm.valid) {
    pmp.ld := false.B
    pmp.st := false.B
    pmp.instr := false.B
    pmp.mmio := io.static_pm.bits
  }

  val EnableMem = io.in.bits.uop.loadStoreEnable && io.in.valid
  val s2_is_prefetch = io.in.bits.isSoftPrefetch

  // exception that may cause load addr to be invalid / illegal
  //
  // if such exception happen, that inst and its exception info
  // will be force writebacked to rob
  val s2_exception_vec = WireInit(io.in.bits.uop.cf.exceptionVec)
  s2_exception_vec(loadAccessFault) := (io.in.bits.uop.cf.exceptionVec(loadAccessFault) || pmp.ld) && EnableMem
  // soft prefetch will not trigger any exception (but ecc error interrupt may be triggered)
  when (s2_is_prefetch) {
    s2_exception_vec := 0.U.asTypeOf(s2_exception_vec.cloneType)
  }
  val s2_exception = Mux(EnableMem, ExceptionNO.selectByFu(s2_exception_vec, lduCfg).asUInt.orR,false.B)

  //FDI load access fault
  s2_exception_vec(fdiULoadAccessFault) := io.fdiResp.fdi_fault === FDICheckFault.UReadDascisFault

  io.s3_delayed_load_error := false.B //not use

  val actually_mmio = pmp.mmio
  val s2_uop = io.in.bits.uop
  val s2_mask = io.in.bits.mask
  val s2_paddr = io.in.bits.paddr
  val s2_tlb_miss = io.in.bits.tlbMiss
  val s2_mmio = !s2_is_prefetch && actually_mmio && !s2_exception
  val s2_cache_miss = io.dcacheResp.bits.miss
  val s2_cache_replay = io.dcacheResp.bits.replay
  val s2_cache_tag_error = 0.U.asTypeOf(io.dcacheResp.bits.tag_error.cloneType)
  val s2_forward_fail = io.lsq.matchInvalid || io.sbuffer.matchInvalid
  val s2_ldld_violation = io.loadViolationQueryResp.valid &&
    io.loadViolationQueryResp.bits.have_violation &&
    RegNext(io.csrCtrl.ldld_vio_check_enable)
  val s2_data_invalid = io.lsq.dataInvalid && !s2_ldld_violation && !s2_exception

  io.dcache_kill := pmp.ld || pmp.mmio // move pmp resp kill to outside
  io.dcacheResp.ready := true.B
  val dcacheShouldResp = !(s2_tlb_miss || s2_exception || s2_mmio || s2_is_prefetch)
  assert(!(EnableMem && (dcacheShouldResp && !io.dcacheResp.valid)), "DCache response got lost")

  // merge forward result
  // lsq has higher priority than sbuffer
  val forwardMask = Wire(Vec(8, Bool()))
  val forwardData = Wire(Vec(8, UInt(8.W)))

  val fullForward = ((~forwardMask.asUInt).asUInt & s2_mask) === 0.U && !io.lsq.dataInvalid
  io.lsq := DontCare
  io.sbuffer := DontCare
  io.fullForward := fullForward

  // generate XLEN/8 Muxs
  for (i <- 0 until XLEN / 8) {
    forwardMask(i) := io.lsq.forwardMask(i) || io.sbuffer.forwardMask(i)
    forwardData(i) := Mux(io.lsq.forwardMask(i), io.lsq.forwardData(i), io.sbuffer.forwardData(i))
  }

  XSDebug(io.out.fire, "[FWD LOAD RESP] pc %x fwd %x(%b) + %x(%b)\n",
    s2_uop.cf.pc,
    io.lsq.forwardData.asUInt, io.lsq.forwardMask.asUInt,
    io.in.bits.forwardData.asUInt, io.in.bits.forwardMask.asUInt
  )

  when(EnableMem){
    io.out.valid := io.in.valid && !s2_tlb_miss && !s2_data_invalid
  }.otherwise {
    io.out.valid := io.in.valid
  }

  // Inst will be canceled in store queue / lsq,
  // so we do not need to care about flush in load / store unit's out.valid
  io.out.bits := io.in.bits
  // io.out.bits.data := rdataPartialLoad
  io.out.bits.data := 0.U // data will be generated in load_s3
  // when exception occurs, set it to not miss and let it write back to rob (via int port)
  if (EnableFastForward) {
    io.out.bits.miss := s2_cache_miss &&
      !s2_exception &&
      !fullForward &&
      !s2_ldld_violation &&
      !s2_is_prefetch && EnableMem
  } else {
    io.out.bits.miss := s2_cache_miss &&
      !s2_exception &&
      !s2_ldld_violation &&
      !s2_is_prefetch && EnableMem
  }
  io.out.bits.uop.ctrl.fpWen := io.in.bits.uop.ctrl.fpWen && !s2_exception

  io.loadDataFromDcache.load_data := Mux(io.dcacheResp.bits.miss,0.U,io.dcacheResp.bits.load_data)  //to cut X-prop
  io.loadDataFromDcache.forwardMask := forwardMask
  io.loadDataFromDcache.forwardData := forwardData
  io.loadDataFromDcache.uop := io.out.bits.uop
  io.loadDataFromDcache.addrOffset := s2_paddr(2, 0)

  io.s2_can_replay_from_fetch := !s2_mmio && !s2_is_prefetch && !s2_tlb_miss
  // if forward fail, replay this inst from fetch
  val debug_forwardFailReplay = s2_forward_fail && !s2_mmio && !s2_is_prefetch && !s2_tlb_miss
  // if ld-ld violation is detected, replay from this inst from fetch
  val debug_ldldVioReplay = s2_ldld_violation && !s2_mmio && !s2_is_prefetch && !s2_tlb_miss
  // io.out.bits.uop.ctrl.replayInst := false.B
  io.lpvCancel := io.in.valid && (s2_tlb_miss || s2_mmio || io.lsq.dataInvalid || s2_cache_miss)
  io.out.bits.mmio := s2_mmio && EnableMem
  io.out.bits.uop.ctrl.flushPipe := false.B  ///flushPipe logic is useless
  io.out.bits.uop.cf.exceptionVec := s2_exception_vec // cache error not included

  // For timing reasons, sometimes we can not let
  // io.out.bits.miss := s2_cache_miss && !s2_exception && !fullForward
  // We use io.dataForwarded instead. It means:
  // 1. Forward logic have prepared all data needed,
  //    and dcache query is no longer needed.
  // 2. ... or data cache tag error is detected, this kind of inst
  //    will not update miss queue. That is to say, if miss, that inst
  //    may not be refilled
  // Such inst will be writebacked from load queue.
  io.dataForwarded := s2_cache_miss && !s2_exception &&
    (fullForward || io.csrCtrl.cache_error_enable && s2_cache_tag_error)
  // io.out.bits.forwardX will be send to lq
  io.out.bits.forwardMask := forwardMask
  // data from dcache is not included in io.out.bits.forwardData
  io.out.bits.forwardData := forwardData

  io.in.ready := io.out.ready || !io.in.valid

  val s2_need_replay_from_rs = Wire(Bool())
  if (EnableFastForward) {
    s2_need_replay_from_rs :=
      s2_tlb_miss || // replay if dtlb miss
      s2_cache_replay && !s2_is_prefetch && !s2_mmio && !s2_exception && !fullForward || // replay if dcache miss queue full / busy
      s2_data_invalid && !s2_is_prefetch // replay if store to load forward data is not ready
  } else {
    // Note that if all parts of data are available in sq / sbuffer, replay required by dcache will not be scheduled
    s2_need_replay_from_rs :=
      s2_tlb_miss || // replay if dtlb miss
      s2_cache_replay && !s2_is_prefetch && !s2_mmio && !s2_exception && !io.dataForwarded || // replay if dcache miss queue full / busy
      s2_data_invalid && !s2_is_prefetch // replay if store to load forward data is not ready
  }
  io.rsFeedback.valid := io.in.valid && s2_need_replay_from_rs && EnableMem
  io.rsFeedback.bits.rsIdx := io.in.bits.rsIdx
  io.rsFeedback.bits.flushState := io.in.bits.ptwBack
  io.rsFeedback.bits.sourceType := Mux(s2_tlb_miss, RSFeedbackType.tlbMiss,
    Mux(s2_cache_replay,
      RSFeedbackType.mshrFull,
      RSFeedbackType.dataInvalid
    )
  )

  // s2_cache_replay is quite slow to generate, send it separately to LQ
  if (EnableFastForward) {
    io.s2_dcache_require_replay := s2_cache_replay && !fullForward
  } else {
    io.s2_dcache_require_replay := s2_cache_replay &&
      io.rsFeedback.valid &&
      !io.dataForwarded &&
      !s2_is_prefetch &&
      io.out.bits.miss
  }

  XSPerfAccumulate("in_valid", io.in.valid)
  XSPerfAccumulate("in_fire", io.in.fire)
  XSPerfAccumulate("dcache_miss", io.in.fire && s2_cache_miss)
  XSPerfAccumulate("full_forward", io.in.valid && fullForward)
  XSPerfAccumulate("dcache_miss_full_forward", io.in.valid && s2_cache_miss && fullForward)
  XSPerfAccumulate("replay",  io.rsFeedback.valid)
  XSPerfAccumulate("replay_tlb_miss", io.rsFeedback.valid && s2_tlb_miss)
  XSPerfAccumulate("replay_cache", io.rsFeedback.valid && !s2_tlb_miss && s2_cache_replay)
  XSPerfAccumulate("stall_out", io.out.valid && !io.out.ready)
  XSPerfAccumulate("replay_from_fetch_forward", io.out.valid && debug_forwardFailReplay)
  XSPerfAccumulate("replay_from_fetch_load_vio", io.out.valid && debug_ldldVioReplay)
}

class LoadUnit(implicit p: Parameters) extends XSModule with HasLoadHelper with HasPerfEvents with HasDCacheParameters with SdtrigExt with HasPerfLogging {
  val io = IO(new Bundle() {
    val ldin = Flipped(Decoupled(new ExuInput))
    val auxValid = Input(Bool())
    val ldout = Decoupled(new ExuOutput)
    val redirect = Flipped(ValidIO(new Redirect))
    val feedbackSlow = ValidIO(new RSFeedback)
    val feedbackFast = ValidIO(new RSFeedback)
    val rsIdx = Input(new RsIdx)
    val dcache = new DCacheLoadIO
    val sbuffer = new LoadForwardQueryIO
    val lsq = new LoadToLsqIO
    val trigger = Vec(TriggerNum, new LoadUnitTriggerIO)
    val vmEnable = Input(Bool())
    val tlb = new TlbRequestIO(2)
    val pmp = Flipped(new PMPRespBundle()) // arrive same to tlb now

    //FDI
    val fdiReq = ValidIO(new  FDIReqBundle())
    val fdiResp = Flipped(new FDIRespBundle())

    // provide prefetch info
    val prefetch_train = ValidIO(new LsPipelineBundle())

    val s3_delayed_load_error = Output(Bool()) // load ecc error
    // Note that io.s3_delayed_load_error and io.lsq.s3_delayed_load_error is different

    val csrCtrl = Flipped(new CustomCSRCtrlIO)
    val cancel = Output(Bool())
    val bankConflictAvoidIn = Input(UInt(1.W))
  })

  val s0_req_tlb = io.tlb.req
  val s0_req_dcache = io.dcache.req
  val s0_in = io.ldin
  val s0_out = Wire(Decoupled(new LsPipelineBundle))

  val s0_imm12 = s0_in.bits.uop.ctrl.imm(11,0)
  val s0_vaddr = WireInit(s0_in.bits.src(0) + SignExt(s0_imm12, VAddrBits))
  val s0_mask = WireInit(genWmask(s0_vaddr, s0_in.bits.uop.ctrl.fuOpType(1,0)))
  val s0_uop = WireInit(s0_in.bits.uop)

  val s0_auxValid = io.auxValid

  val s0_isSoftPrefetch = LSUOpType.isPrefetch(s0_uop.ctrl.fuOpType)
  val s0_isSoftPrefetchRead = s0_uop.ctrl.fuOpType === LSUOpType.prefetch_r
  val s0_isSoftPrefetchWrite = s0_uop.ctrl.fuOpType === LSUOpType.prefetch_w
  val s0_EnableMem = s0_in.bits.uop.loadStoreEnable

  s0_req_tlb := DontCare
  s0_req_tlb.valid := s0_auxValid
  s0_req_tlb.bits.vaddr := s0_vaddr
  s0_req_tlb.bits.cmd := TlbCmd.read
  s0_req_tlb.bits.size := LSUOpType.size(s0_uop.ctrl.fuOpType)
  s0_req_tlb.bits.robIdx := s0_uop.robIdx
  s0_req_tlb.bits.debug.pc := s0_uop.cf.pc

  s0_req_dcache.valid := s0_auxValid
  when (s0_isSoftPrefetchRead) {
    s0_req_dcache.bits.cmd  := MemoryOpConstants.M_PFR
  }.elsewhen (s0_isSoftPrefetchWrite) {
    s0_req_dcache.bits.cmd  := MemoryOpConstants.M_PFW
  }.otherwise {
    s0_req_dcache.bits.cmd  := MemoryOpConstants.M_XRD
  }

  s0_req_dcache.bits.robIdx := s0_in.bits.uop.robIdx
  s0_req_dcache.bits.addr := s0_vaddr
  s0_req_dcache.bits.mask := s0_mask
  s0_req_dcache.bits.data := DontCare
  s0_req_dcache.bits.instrtype := Mux(s0_isSoftPrefetch,SOFT_PREFETCH.U,LOAD_SOURCE.U)
  s0_req_dcache.bits.id := DontCare

  val s0_addrAligned = LookupTree(s0_uop.ctrl.fuOpType(1, 0), List(
    "b00".U   -> true.B,                   //b
    "b01".U   -> (s0_vaddr(0)    === 0.U), //h
    "b10".U   -> (s0_vaddr(1, 0) === 0.U), //w
    "b11".U   -> (s0_vaddr(2, 0) === 0.U)  //d
  ))

  s0_out.valid := s0_in.valid
  s0_out.bits := DontCare
  s0_out.bits.vaddr := s0_vaddr
  s0_out.bits.mask := s0_mask
  s0_out.bits.uop := s0_uop

  private val s0_vaddr2 = s0_in.bits.src(0) + SignExt(s0_imm12, XLEN)
  dontTouch(s0_vaddr)
  private val illegalAddr = s0_vaddr2(XLEN - 1, VAddrBits - 1) =/= 0.U && s0_vaddr2(XLEN - 1, VAddrBits - 1) =/= Fill(XLEN - VAddrBits + 1, 1.U(1.W))
  s0_out.bits.uop.cf.exceptionVec(loadAddrMisaligned) := Mux(s0_EnableMem, !s0_addrAligned, false.B)
  s0_out.bits.uop.cf.exceptionVec(loadPageFault) := Mux(s0_EnableMem & io.vmEnable, illegalAddr, false.B)

  s0_out.bits.rsIdx := io.rsIdx
  s0_out.bits.isSoftPrefetch := s0_isSoftPrefetch

  s0_in.ready := !s0_in.valid || s0_out.ready

  val s0_cancel = !s0_req_dcache.ready


//  val load_s1 = Module(new LoadUnit_S1)
  val s1_in = Wire(Decoupled(new LsPipelineBundle))
  val s1_out = Wire(Decoupled(new LsPipelineBundle))

  PipelineConnect(s0_out, s1_in, true.B, s0_out.bits.uop.robIdx.needFlush(io.redirect))

  s1_out.bits := s1_in.bits
  val s1_dtlbResp = io.tlb.resp
  val s1_kill_inner = false.B
  val s1_cancel_inner = RegEnable(s0_cancel,s0_out.fire)

  s1_dtlbResp.ready := true.B

  val s1_uop = s1_in.bits.uop
  val s1_paddr_dup_lsu = s1_dtlbResp.bits.paddr(0)
  val s1_paddr_dup_dcache = s1_dtlbResp.bits.paddr(1)

  io.dcache.s1_paddr_dup_lsu := s1_paddr_dup_lsu
  io.dcache.s1_paddr_dup_dcache := s1_paddr_dup_dcache
  val s1_enableMem = s1_in.bits.uop.loadStoreEnable


  // af & pf exception were modified below.
  val s1_exception = Mux(s1_enableMem && s1_in.valid, ExceptionNO.selectByFu(s1_out.bits.uop.cf.exceptionVec, lduCfg).asUInt.orR, false.B)
  val s1_tlb_miss = s1_dtlbResp.bits.miss
  val s1_mask = s1_in.bits.mask
  val s1_bank_conflict = io.dcache.s1_bank_conflict

  val s1_dcacheKill = s1_in.valid && (s1_tlb_miss || s1_exception || s1_kill_inner || s1_cancel_inner || (!s1_enableMem))

  val s1_sbufferForwardReq = io.sbuffer
  val s1_lsqForwardReq = io.lsq.forward
  val s1_ldViolationQueryReq = io.lsq.loadViolationQuery.req
  val s1_fdiReq = io.fdiReq

  s1_sbufferForwardReq.valid := s1_in.valid && !(s1_exception || s1_tlb_miss || s1_kill_inner) && s1_enableMem
  s1_sbufferForwardReq.vaddr := s1_in.bits.vaddr
  s1_sbufferForwardReq.paddr := s1_paddr_dup_lsu
  s1_sbufferForwardReq.uop := s1_uop
  s1_sbufferForwardReq.sqIdx := s1_uop.sqIdx
  s1_sbufferForwardReq.mask := s1_mask
  s1_sbufferForwardReq.pc := s1_uop.cf.pc

  s1_lsqForwardReq.valid := s1_in.valid && !(s1_exception || s1_tlb_miss || s1_kill_inner) && s1_enableMem
  s1_lsqForwardReq.vaddr := s1_in.bits.vaddr
  s1_lsqForwardReq.paddr := s1_paddr_dup_lsu
  s1_lsqForwardReq.uop := s1_uop
  s1_lsqForwardReq.sqIdx := s1_uop.sqIdx
  s1_lsqForwardReq.sqIdxMask := DontCare
  s1_lsqForwardReq.mask := s1_mask
  s1_lsqForwardReq.pc := s1_uop.cf.pc

  s1_ldViolationQueryReq.valid := s1_in.valid && !(s1_exception || s1_tlb_miss || s1_kill_inner) && s1_enableMem
  s1_ldViolationQueryReq.bits.paddr := s1_paddr_dup_lsu
  s1_ldViolationQueryReq.bits.uop := s1_uop

  s1_fdiReq.valid := s1_out.fire
  s1_fdiReq.bits.addr := s1_out.bits.vaddr
  s1_fdiReq.bits.inUntrustedZone := s1_out.bits.uop.fdiUntrusted
  s1_fdiReq.bits.operation := FDIOp.read

  //no use
//  val s1_forwardMaskFast = s1_lsqForwardReq.forwardMaskFast.asUInt | s1_sbufferForwardReq.forwardMaskFast.asUInt
//  val s1_fullForwardFast = ((~s1_forwardMaskFast).asUInt & s1_mask) === 0.U

  // Generate feedback signal caused by:
  // * dcache bank conflict
  // * need redo ld-ld violation check
  val s1_csrCtrl_ldld_vio_check_enable = io.csrCtrl.ldld_vio_check_enable
  val s1_needLdVioCheckRedo = s1_ldViolationQueryReq.valid &&
    !s1_ldViolationQueryReq.ready &&
    RegNext(s1_csrCtrl_ldld_vio_check_enable)
  val s1_rsFeedback = Wire(ValidIO(new RSFeedback))
  s1_rsFeedback.valid := s1_in.valid && (s1_bank_conflict || s1_needLdVioCheckRedo || s1_cancel_inner) && !s1_kill_inner && s1_enableMem
  s1_rsFeedback.bits.rsIdx := s1_in.bits.rsIdx
  s1_rsFeedback.bits.flushState := s1_in.bits.ptwBack
  s1_rsFeedback.bits.sourceType := Mux(s1_bank_conflict, RSFeedbackType.bankConflict, RSFeedbackType.ldVioCheckRedo)

  // if replay is detected in load_s1,
  // load inst will be canceled immediately
  s1_out.valid := s1_in.valid && (!s1_rsFeedback.valid && !s1_kill_inner || !s1_enableMem)
  s1_out.bits.paddr := s1_paddr_dup_lsu
  s1_out.bits.tlbMiss := s1_tlb_miss

  // current ori test will cause the case of ldest == 0, below will be modifeid in the future.
  // af & pf exception were modified
  s1_out.bits.uop.cf.exceptionVec(loadPageFault) := (s1_dtlbResp.bits.excp(0).pf.ld || s1_in.bits.uop.cf.exceptionVec(loadPageFault)) && s1_enableMem
  s1_out.bits.uop.cf.exceptionVec(loadAccessFault) := s1_dtlbResp.bits.excp(0).af.ld && s1_enableMem
  s1_out.bits.ptwBack := s1_dtlbResp.bits.ptwBack
  s1_out.bits.rsIdx := s1_in.bits.rsIdx
  s1_out.bits.isSoftPrefetch := s1_in.bits.isSoftPrefetch

  s1_in.ready := !s1_in.valid || s1_out.ready


  assert(s0_in.ready)

  //  val load_s2 = Module(new LoadUnit_S2)
  val s2_in = Wire(Decoupled(new LsPipelineBundle))
  val s2_out = Wire(Decoupled(new LsPipelineBundle))


  val s2_pmp = WireInit(io.pmp)
  val s2_static_pm = RegEnable(io.tlb.resp.bits.static_pm,io.tlb.resp.valid)
  when(s2_static_pm.valid) {
    s2_pmp.ld := false.B
    s2_pmp.st := false.B
    s2_pmp.instr := false.B
    s2_pmp.mmio := s2_static_pm.bits
  }

  val s2_enableMem = s2_in.bits.uop.loadStoreEnable && s2_in.valid
  val s2_is_prefetch = s2_in.bits.isSoftPrefetch

  val s2_exception_vec = WireInit(s2_in.bits.uop.cf.exceptionVec)
  s2_exception_vec(loadAccessFault) := (s2_in.bits.uop.cf.exceptionVec(loadAccessFault) || s2_pmp.ld) && s2_enableMem
  when(s2_is_prefetch) {
    s2_exception_vec := 0.U.asTypeOf(s2_exception_vec.cloneType)
  }
  val s2_exception = Mux(s2_enableMem, ExceptionNO.selectByFu(s2_exception_vec, lduCfg).asUInt.orR,false.B)
  //FDI load access fault
  s2_exception_vec(fdiULoadAccessFault) := io.fdiResp.fdi_fault === FDICheckFault.UReadDascisFault

  ///more resp input:
  val s2_dcacheResp = io.dcache.resp
  val s2_LSQ_LoadForwardQueryIO = Wire(new LoadForwardQueryIO)
  val s2_SB_LoadForwardQueryIO = Wire(new LoadForwardQueryIO)
  val s2_loadViolationQueryResp = Wire(ValidIO(new LoadViolationQueryResp))

  s2_LSQ_LoadForwardQueryIO := DontCare
  s2_SB_LoadForwardQueryIO := DontCare
  s2_dcacheResp.ready := true.B

  s2_LSQ_LoadForwardQueryIO.forwardData := io.lsq.forward.forwardData
  s2_LSQ_LoadForwardQueryIO.forwardMask := io.lsq.forward.forwardMask
  s2_LSQ_LoadForwardQueryIO.forwardMaskFast := io.lsq.forward.forwardMaskFast
  s2_LSQ_LoadForwardQueryIO.dataInvalid := io.lsq.forward.dataInvalid
  s2_LSQ_LoadForwardQueryIO.matchInvalid := io.lsq.forward.matchInvalid

  s2_SB_LoadForwardQueryIO.forwardData := io.sbuffer.forwardData
  s2_SB_LoadForwardQueryIO.forwardMask := io.sbuffer.forwardMask
  s2_SB_LoadForwardQueryIO.forwardMaskFast := io.sbuffer.forwardMaskFast
  s2_SB_LoadForwardQueryIO.dataInvalid := io.sbuffer.dataInvalid // always false
  s2_SB_LoadForwardQueryIO.matchInvalid := io.sbuffer.matchInvalid

  s2_loadViolationQueryResp := io.lsq.loadViolationQuery.resp

  ///more output: dataForwarded

  val s2_actually_mmio = s2_pmp.mmio
//  val s2_uop = s2_in.bits.uop
  val s2_mask = s2_in.bits.mask
  val s2_paddr = s2_in.bits.paddr
  val s2_tlb_miss = s2_in.bits.tlbMiss
  val s2_mmio = !s2_is_prefetch && s2_actually_mmio && !s2_exception
  val s2_cache_miss = s2_dcacheResp.bits.miss
  val s2_cache_replay = s2_dcacheResp.bits.replay
  val s2_cache_tag_error = 0.U.asTypeOf(s2_dcacheResp.bits.tag_error.cloneType)
//  val s2_forward_fail = s2_LSQ_LoadForwardQueryIO.matchInvalid || s2_SB_LoadForwardQueryIO.matchInvalid
  val s2_ldld_violation = s2_loadViolationQueryResp.valid &&
    s2_loadViolationQueryResp.bits.have_violation &&
    RegNext(io.csrCtrl.ldld_vio_check_enable)
  val s2_data_invalid = s2_LSQ_LoadForwardQueryIO.dataInvalid && !s2_ldld_violation && !s2_exception


  ///more output info:
  val s2_dcache_kill = s2_pmp.ld || s2_pmp.mmio // move pmp resp kill to outside

  val s2_dcacheShouldResp = !(s2_tlb_miss || s2_exception || s2_mmio || s2_is_prefetch)
  assert(!(s2_enableMem && (s2_dcacheShouldResp && !s2_dcacheResp.valid)), "DCache response got lost")

  // merge forward result
  // lsq has higher priority than sbuffer
  val s2_forwardMask = Wire(Vec(8, Bool()))
  val s2_forwardData = Wire(Vec(8, UInt(8.W)))
  for (i <- 0 until XLEN / 8) {
    s2_forwardMask(i) := s2_LSQ_LoadForwardQueryIO.forwardMask(i) || s2_SB_LoadForwardQueryIO.forwardMask(i)
    s2_forwardData(i) := Mux(s2_LSQ_LoadForwardQueryIO.forwardMask(i), s2_LSQ_LoadForwardQueryIO.forwardData(i), s2_SB_LoadForwardQueryIO.forwardData(i))
  }

  val s2_fullForward = ((~s2_forwardMask.asUInt).asUInt & s2_mask) === 0.U && !s2_LSQ_LoadForwardQueryIO.dataInvalid

  when(s2_enableMem) {
    s2_out.valid := s2_in.valid && !s2_tlb_miss && !s2_data_invalid
  }.otherwise {
    s2_out.valid := s2_in.valid
  }
  s2_out.bits := s2_in.bits
  s2_out.bits.data := 0.U

  //EnableFastForward is false
  s2_out.bits.miss := s2_cache_miss &&
    !s2_exception &&
    !s2_ldld_violation &&
    !s2_is_prefetch && s2_enableMem

  s2_out.bits.uop.ctrl.fpWen := s2_in.bits.uop.ctrl.fpWen && !s2_exception

  ///output
  val s2_loadDataFromDcache = Wire(new LoadDataFromDcacheBundle)
  s2_loadDataFromDcache.load_data := Mux(s2_dcacheResp.bits.miss, 0.U, s2_dcacheResp.bits.load_data) //to cut X-prop
  s2_loadDataFromDcache.forwardMask := s2_forwardMask
  s2_loadDataFromDcache.forwardData := s2_forwardData
  s2_loadDataFromDcache.uop := s2_out.bits.uop
  s2_loadDataFromDcache.addrOffset := s2_paddr(2, 0)

  val s2_can_replay_from_fetch = !s2_mmio && !s2_is_prefetch && !s2_tlb_miss

  val s2_lpvCancel = s2_in.valid && (s2_tlb_miss || s2_mmio || s2_LSQ_LoadForwardQueryIO.dataInvalid || s2_cache_miss)
  s2_out.bits.mmio := s2_mmio && s2_enableMem
  s2_out.bits.uop.ctrl.flushPipe := false.B ///flushPipe logic is useless
  s2_out.bits.uop.cf.exceptionVec := s2_exception_vec // cache error not included

  val s2_dataForwarded = s2_cache_miss && !s2_exception &&
    (s2_fullForward || io.csrCtrl.cache_error_enable && s2_cache_tag_error)
  // io.out.bits.forwardX will be send to lq
  s2_out.bits.forwardMask := s2_forwardMask
  // data from dcache is not included in io.out.bits.forwardData
  s2_out.bits.forwardData := s2_forwardData

  s2_in.ready := s2_out.ready || !s2_in.valid

  val s2_need_replay_from_rs = Wire(Bool())

  s2_need_replay_from_rs := s2_tlb_miss || // replay if dtlb miss
      s2_cache_replay && !s2_is_prefetch && !s2_mmio && !s2_exception && !s2_dataForwarded || // replay if dcache miss queue full / busy
      s2_data_invalid && !s2_is_prefetch // replay if store to load forward data is not ready

  val s2_rsFeedback = Wire(ValidIO(new RSFeedback))
  s2_rsFeedback.valid := s2_in.valid && s2_need_replay_from_rs && s2_enableMem
  s2_rsFeedback.bits.rsIdx := s2_in.bits.rsIdx
  s2_rsFeedback.bits.flushState := s2_in.bits.ptwBack
  s2_rsFeedback.bits.sourceType := Mux(s2_tlb_miss, RSFeedbackType.tlbMiss,
    Mux(s2_cache_replay,
      RSFeedbackType.mshrFull,
      RSFeedbackType.dataInvalid
    )
  )

  val s2_dcache_require_replay = s2_cache_replay &&
    s2_rsFeedback.valid &&
    !s2_dataForwarded &&
    !s2_is_prefetch &&
    s2_out.bits.miss

  //  val s2_fullForward = ((~s2_forwardMask.asUInt).asUInt & s2_mask) === 0.U && !s2_LQ_LoadForwardQueryIO.dataInvalid
//  io.lsq := DontCare
//  io.sbuffer := DontCare

//  io.fullForward := fullForward //not use

  //  PipelineConnect(s0_out, load_s1.io.in, true.B, s0_out.bits.uop.robIdx.needFlush(io.redirect))



  // load s1
//  load_s1.io.s1_kill := false.B
//  io.tlb.req_kill := load_s1.io.s1_kill
  io.tlb.req_kill := s1_kill_inner
//  load_s1.io.dtlbResp <> io.tlb.resp
//  io.dcache.s1_paddr_dup_lsu <> load_s1.io.lsuPAddr
//  io.dcache.s1_paddr_dup_dcache <> load_s1.io.dcachePAddr
//  io.dcache.s1_kill <> load_s1.io.dcacheKill
  io.dcache.s1_kill := s1_dcacheKill


//  load_s1.io.sbuffer <> io.sbuffer
//  load_s1.io.lsq <> io.lsq.forward

//  load_s1.io.loadViolationQueryReq <> io.lsq.loadViolationQuery.req
//  load_s1.io.dcacheBankConflict <> io.dcache.s1_bank_conflict
//  load_s1.io.csrCtrl <> io.csrCtrl
//  load_s1.io.s1_cancel := RegEnable(s0_cancel, s0_out.fire)
//  load_s1.io.bankConflictAvoidIn := io.bankConflictAvoidIn
//  assert(load_s1.io.in.ready)
//  io.fdiReq := load_s1.io.fdiReq
  assert(s1_in.ready)

  // provide paddr for lq
  io.lsq.loadPaddrIn.valid := s1_out.valid
  io.lsq.loadPaddrIn.bits.lqIdx := s1_out.bits.uop.lqIdx
  io.lsq.loadPaddrIn.bits.paddr := s1_paddr_dup_lsu

//  // provide paddr for lq
//  io.lsq.loadPaddrIn.valid := load_s1.io.out.valid
//  io.lsq.loadPaddrIn.bits.lqIdx := load_s1.io.out.bits.uop.lqIdx
//  io.lsq.loadPaddrIn.bits.paddr := load_s1.io.lsuPAddr

  PipelineConnect(s1_out, s2_in, true.B, s1_out.bits.uop.robIdx.needFlush(io.redirect))

  // load s2
//  io.s2IsPointerChasing := DontCare
  io.prefetch_train.bits := s2_in.bits
  // override miss bit
  io.prefetch_train.bits.miss := io.dcache.resp.bits.miss
  io.prefetch_train.valid := s2_in.fire && !s2_out.bits.mmio && !s2_in.bits.tlbMiss
  io.dcache.s2_kill := s2_dcache_kill // to kill mmio resp which are redirected
//  load_s2.io.dcacheResp <> io.dcache.resp
//  load_s2.io.pmpResp <> io.pmp
//  load_s2.io.static_pm := RegEnable(io.tlb.resp.bits.static_pm,io.tlb.resp.valid)

//  load_s2.io.lsq.forwardData <> io.lsq.forward.forwardData
//  load_s2.io.lsq.forwardMask <> io.lsq.forward.forwardMask
//  load_s2.io.lsq.forwardMaskFast <> io.lsq.forward.forwardMaskFast // should not be used in load_s2
//  load_s2.io.lsq.dataInvalid <> io.lsq.forward.dataInvalid
//  load_s2.io.lsq.matchInvalid <> io.lsq.forward.matchInvalid

//  load_s2.io.sbuffer.forwardData <> io.sbuffer.forwardData
//  load_s2.io.sbuffer.forwardMask <> io.sbuffer.forwardMask
//  load_s2.io.sbuffer.forwardMaskFast <> io.sbuffer.forwardMaskFast // should not be used in load_s2
//  load_s2.io.sbuffer.dataInvalid <> io.sbuffer.dataInvalid // always false
//  load_s2.io.sbuffer.matchInvalid <> io.sbuffer.matchInvalid

  io.lsq.s2_load_data_forwarded := s2_dataForwarded
//  load_s2.io.dataForwarded <> io.lsq.s2_load_data_forwarded
  //not use:
//  load_s2.io.dataInvalidSqIdx := io.lsq.forward.dataInvalidSqIdx // provide dataInvalidSqIdx to make wakeup faster
//  load_s2.io.loadViolationQueryResp <> io.lsq.loadViolationQuery.resp
//  load_s2.io.csrCtrl <> io.csrCtrl
  assert(s2_in.ready)
//  load_s2.io.fdiResp := io.fdiResp

//  // feedback bank conflict / ld-vio check struct hazard to rs
//  io.feedbackFast.bits := RegNext(load_s1.io.rsFeedback.bits)   //remove clock-gating for timing
//  io.feedbackFast.valid := RegNext(load_s1.io.rsFeedback.valid && !load_s1.io.out.bits.uop.robIdx.needFlush(io.redirect), false.B)

  // feedback bank conflict / ld-vio check struct hazard to rs
  io.feedbackFast.valid := RegNext(s1_rsFeedback.valid && !s1_out.bits.uop.robIdx.needFlush(io.redirect), false.B)
  io.feedbackFast.bits := RegNext(s1_rsFeedback.bits) //remove clock-gating for timing


  // pre-calcuate sqIdx mask in s0, then send it to lsq in s1 for forwarding
  val sqIdxMaskReg = RegEnable(UIntToMask(s0_in.bits.uop.sqIdx.value, StoreQueueSize), s0_in.valid)
  // to enable load-load, sqIdxMask must be calculated based on ldin.uop
  // If the timing here is not OK, load-load forwarding has to be disabled.
  // Or we calculate sqIdxMask at RS??
  io.lsq.forward.sqIdxMask := sqIdxMaskReg

//  XSDebug(load_s1.io.out.valid,
//    p"S1: pc ${Hexadecimal(load_s1.io.out.bits.uop.cf.pc)}, lId ${Hexadecimal(load_s1.io.out.bits.uop.lqIdx.asUInt)}, tlb_miss ${io.tlb.resp.bits.miss}, " +
//    p"paddr ${Hexadecimal(load_s1.io.out.bits.paddr)}, mmio ${load_s1.io.out.bits.mmio}\n")

  // writeback to LSQ
  // Current dcache use MSHR
  // Load queue will be updated at s2 for both hit/miss int/fp load
  io.lsq.loadIn.valid := s2_out.valid
  // generate LqWriteBundle from LsPipelineBundle
  io.lsq.loadIn.bits.fromLsPipelineBundle(s2_out.bits)
  // generate duplicated load queue data wen
  val load_s2_valid_vec = RegInit(0.U(6.W))
//  val load_s2_leftFire = load_s1.io.out.valid && load_s2.io.in.ready
  val load_s2_leftFire = s1_out.valid && s2_in.ready
  load_s2_valid_vec := 0x0.U(6.W)
  when (load_s2_leftFire) { load_s2_valid_vec := 0x3f.U(6.W)}
  when (s1_out.bits.uop.robIdx.needFlush(io.redirect)) { load_s2_valid_vec := 0x0.U(6.W) }
  assert(RegNext(s2_in.valid === load_s2_valid_vec(0)))
  io.lsq.loadIn.bits.lq_data_wen_dup := load_s2_valid_vec.asBools

  // s2_dcache_require_replay signal will be RegNexted, then used in s3
  io.lsq.s2_dcache_require_replay := s2_dcache_require_replay

  // write to rob and writeback bus
  val s2_wb_valid = s2_out.valid &&
    (!s2_out.bits.miss &&
      !s2_out.bits.mmio &&
      !s2_out.bits.uop.robIdx.needFlush(io.redirect))

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

  // load s3
  val s3_load_wb_meta_reg = RegEnable(Mux(hitLoadOut.valid, hitLoadOut.bits, io.lsq.ldout.bits), hitLoadOut.valid | io.lsq.ldout.valid)

  // data from load queue refill
  val s3_loadDataFromLQ = RegEnable(io.lsq.ldRawData, io.lsq.ldout.valid)
  val s3_rdataLQ = s3_loadDataFromLQ.mergedData()

  // data from dcache hit
  val s3_loadDataFromDcache = RegEnable(s2_loadDataFromDcache, s2_in.valid)
  val s3_rdataDcache = s3_loadDataFromDcache.mergedData()

  private val hitLoadOutValidReg = RegNext(hitLoadOut.valid, false.B)
  val hitLoadOutValidReg_dup = Seq.fill(8)(RegNext(hitLoadOut.valid, false.B))

  val s3_uop = Mux(hitLoadOutValidReg,s3_loadDataFromDcache.uop,s3_loadDataFromLQ.uop)
  val s3_offset = Mux(hitLoadOutValidReg,s3_loadDataFromDcache.addrOffset,s3_loadDataFromLQ.addrOffset)
  val s3_rdata_dup = WireInit(VecInit(List.fill(8)(0.U(64.W))))
  s3_rdata_dup.zipWithIndex.foreach({case(d,i) => {
    d := Mux(hitLoadOutValidReg_dup(i),s3_rdataDcache,s3_rdataLQ)
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

  io.ldout.bits := s3_load_wb_meta_reg
  io.ldout.bits.data := s3_rdataPartialLoad
  private val lsqOutputValidReg = RegNext(io.lsq.ldout.valid && (!io.lsq.ldout.bits.uop.robIdx.needFlush(io.redirect)),false.B)
  io.ldout.valid := hitLoadOutValidReg || lsqOutputValidReg

  io.ldout.bits.uop.cf.exceptionVec(loadAccessFault) := s3_load_wb_meta_reg.uop.cf.exceptionVec(loadAccessFault) //||


  // feedback tlb miss / dcache miss queue full
  io.feedbackSlow.bits := RegNext(s2_rsFeedback.bits) //remove clock-gating for timing
  io.feedbackSlow.valid := RegNext(s2_rsFeedback.valid && !s2_out.bits.uop.robIdx.needFlush(io.redirect), false.B)
  // If replay is reported at load_s1, inst will be canceled (will not enter load_s2),
  // in that case:
  // * replay should not be reported twice
  assert(!(RegNext(io.feedbackFast.valid) && io.feedbackSlow.valid))
  // * io.fastUop.valid should not be reported
//  assert(!RegNext(io.feedbackFast.valid && io.fastUop.valid))

  // load forward_fail/ldld_violation check
  // check for inst in load pipeline
  val s3_forward_fail = RegNext(io.lsq.forward.matchInvalid || io.sbuffer.matchInvalid)
  val s3_ldld_violation = RegNext(
    io.lsq.loadViolationQuery.resp.valid &&
    io.lsq.loadViolationQuery.resp.bits.have_violation &&
    RegNext(io.csrCtrl.ldld_vio_check_enable)
  )
  val s3_need_replay_from_fetch = s3_forward_fail || s3_ldld_violation
//  val s3_can_replay_from_fetch = RegEnable(load_s2.io.s2_can_replay_from_fetch, load_s2.io.out.valid)
  val s3_can_replay_from_fetch = RegEnable(s2_can_replay_from_fetch, s2_out.valid)


  // 1) use load pipe check result generated in load_s3 iff load_hit
  when (RegNext(hitLoadOut.valid)) {
    io.ldout.bits.uop.ctrl.replayInst := s3_need_replay_from_fetch
  }
  // 2) otherwise, write check result to load queue
  io.lsq.s3_replay_from_fetch := s3_need_replay_from_fetch && s3_can_replay_from_fetch

  // s3_delayed_load_error path is not used for now, as we writeback load result in load_s3
  // but we keep this path for future use
  io.s3_delayed_load_error := false.B
  io.lsq.s3_delayed_load_error := false.B //load_s2.io.s3_delayed_load_error

  io.lsq.ldout.ready := !hitLoadOut.valid

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

  private val s1_cancel = RegInit(false.B)
  s1_cancel := s1_in.valid && (!s1_out.valid)
  io.cancel := s1_cancel || s2_lpvCancel

  val perfEvents = Seq(
    ("load_s0_in_fire         ", s0_in.fire),
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
}
