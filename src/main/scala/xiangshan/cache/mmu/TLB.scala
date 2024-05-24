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

package xiangshan.cache.mmu

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend.execute.fu.csr.HasCSRConst
import xiangshan.backend.execute.fu.fence.SfenceBundle
import xs.utils._
import xs.utils.perf.HasPerfLogging



class TLB(Width: Int, nRespDups: Int = 1, q: TLBParameters)(implicit p: Parameters) extends TlbModule
  with HasCSRConst with HasPerfEvents with HasPerfLogging {
  val io = IO(new TlbIO(Width, nRespDups, q))

  // require(q.superAssociative == "fa")
  // if (q.sameCycle || q.missSameCycle) {
  //   require(q.normalAssociative == "fa")
  // }

  val req = io.requestor.map(_.req)
  val resp = io.requestor.map(_.resp)
  val ptw = io.ptw
  val pmp = io.pmp
  val ptw_resp = if (q.sameCycle) RegEnable(ptw.resp.bits,ptw.resp.valid) else ptw.resp.bits
  //  val ptw_resp = if (q.sameCycle) RegNext(ptw.resp.bits) else ptw.resp.bits
  val ptw_resp_v = if (q.sameCycle) RegNext(ptw.resp.valid, init = false.B) else ptw.resp.valid

  val mode_tmp = if (q.useDmode) io.csr.priv.dmode else io.csr.priv.imode
  val mode_dup = Seq.fill(Width)(RegNext(mode_tmp))
  val vmEnable_tmp = if (EnbaleTlbDebug) (io.csr.satp.mode === 8.U)
    else (io.csr.satp.mode === 8.U && (mode_tmp < ModeM))
  val vmEnable_dup = Seq.fill(Width)(RegNext(vmEnable_tmp))
//  val sfence_dup = Seq.fill(2)(RegNext(io.sfence))
  val csr_dup = Seq.fill(Width)(RegNext(io.csr))
  val csr_dup_2 = RegNext(io.csr)

  val sfence_valid = RegNext(io.sfence.valid)
  val sfence_bits = RegEnable(io.sfence.bits, io.sfence.valid)
  val sfence = Wire(new SfenceBundle)
  sfence.valid := sfence_valid
  sfence.bits := sfence_bits

  val satp = csr_dup.head.satp
  val priv = csr_dup.head.priv
  val ifecth = if (q.fetchi) true.B else false.B

  val reqAddr = req.map(_.bits.vaddr.asTypeOf(new VaBundle))
  val reqValid = req.map(_.valid)
  val vpn = reqAddr.map(_.vpn)
  val cmd = req.map(_.bits.cmd)

  def widthMapSeq[T <: Seq[Data]](f: Int => T) = (0 until Width).map(f)

  def widthMap[T <: Data](f: Int => T) = (0 until Width).map(f)


  val tlbfa = TlbStorage(
    name = "tlbfa",
    sameCycle = q.sameCycle,
    ports = Width,
    nDups = nRespDups,
    nSets = 1,
    nWays = q.nWays,
    saveLevel = q.saveLevel,
    normalPage = true,
    superPage = true,
  )


  for (i <- 0 until Width) {
    tlbfa.r_req_apply(
      valid = io.requestor(i).req.valid,
      vpn = vpn(i),
      asid = csr_dup(i).satp.asid,
      i = i,
    )
  }

  tlbfa.sfence <> sfence
  tlbfa.csr <> csr_dup_2

  val refill_now = ptw_resp_v
  def TLBRead(i: Int) = {
    val (hit_sameCycle, hit, ppn_, perm_) = tlbfa.r_resp_apply(i)
    // assert(!(normal_hit && super_hit && vmEnable_dup(i) && RegNext(req(i).valid, init = false.B)))

    val cmdReg = if (!q.sameCycle) RegEnable(cmd(i), reqValid(i)) else cmd(i)
    val validReg = if (!q.sameCycle) RegNext(reqValid(i)) else reqValid(i)
    val offReg = if (!q.sameCycle) RegEnable(reqAddr(i).off, reqValid(i)) else reqAddr(i).off
    val sizeReg = if (!q.sameCycle) RegEnable(req(i).bits.size, reqValid(i)) else req(i).bits.size



    /** *************** next cycle when two cycle is false******************* */
    val miss = !hit && vmEnable_dup(i)
    val miss_sameCycle = (!hit_sameCycle || refill_now) && vmEnable_dup(i)
    hit.suggestName(s"hit_${i}")
    miss.suggestName(s"miss_${i}")

    val vaddr = SignExt(req(i).bits.vaddr, PAddrBits)
    req(i).ready := resp(i).ready
    resp(i).valid := validReg
    resp(i).bits.miss := { if (q.missSameCycle) miss_sameCycle else miss }
    resp(i).bits.ptwBack := ptw.resp.fire

    // for timing optimization, pmp check is divided into dynamic and static
    // dynamic: superpage (or full-connected reg entries) -> check pmp when translation done
    // static: 4K pages (or sram entries) -> check pmp with pre-checked results
//    val pmp_paddr = Mux(vmEnable_dup(i), Cat(super_ppn(0), offReg), if (!q.sameCycle) RegNext(vaddr) else vaddr)
    val pmp_paddr = Mux(vmEnable_dup(i), Cat(ppn_(0), offReg), if (!q.sameCycle) RegEnable(vaddr,reqValid(i)) else vaddr)
    pmp(i).valid := resp(i).valid
    pmp(i).bits.addr := pmp_paddr
    pmp(i).bits.size := sizeReg
    pmp(i).bits.cmd := cmdReg

    // duplicate resp part
    for (d <- 0 until nRespDups) {
      // ppn and perm from tlbfa resp
      val ppn = ppn_(d)
      val perm = perm_(d)

      val pf = perm.pf
      val af = perm.af
      val paddr = Cat(ppn, offReg)
      resp(i).bits.paddr(d) := Mux(vmEnable_dup(i), paddr, if (!q.sameCycle) RegEnable(vaddr,reqValid(i)) else vaddr)

      val ldUpdate = !perm.a && TlbCmd.isRead(cmdReg) && !TlbCmd.isAmo(cmdReg) // update A/D through exception
      val stUpdate = (!perm.a || !perm.d) && (TlbCmd.isWrite(cmdReg) || TlbCmd.isAmo(cmdReg)) // update A/D through exception
      val instrUpdate = !perm.a && TlbCmd.isExec(cmdReg) // update A/D through exception
      val modeCheck = !(mode_dup(i) === ModeU && !perm.u || mode_dup(i) === ModeS && perm.u && (!priv.sum || ifecth))
      val ldPermFail = !(modeCheck && (perm.r || priv.mxr && perm.x))
      val stPermFail = !(modeCheck && perm.w)
      val instrPermFail = !(modeCheck && perm.x)
      val ldPf = (ldPermFail || pf) && (TlbCmd.isRead(cmdReg) && !TlbCmd.isAmo(cmdReg))
      val stPf = (stPermFail || pf) && (TlbCmd.isWrite(cmdReg) || TlbCmd.isAmo(cmdReg))
      val instrPf = (instrPermFail || pf) && TlbCmd.isExec(cmdReg)
      val fault_valid = vmEnable_dup(i)
      resp(i).bits.excp(d).pf.ld := (ldPf || ldUpdate) && fault_valid && !af
      resp(i).bits.excp(d).pf.st := (stPf || stUpdate) && fault_valid && !af
      resp(i).bits.excp(d).pf.instr := (instrPf || instrUpdate) && fault_valid && !af
      // NOTE: pf need && with !af, page fault has higher priority than access fault
      // but ptw may also have access fault, then af happens, the translation is wrong.
      // In this case, pf has lower priority than af

      // for tlb without sram, tlb will miss, pm should be ignored outsize
      resp(i).bits.excp(d).af.ld    := af && TlbCmd.isRead(cmdReg) && fault_valid
      resp(i).bits.excp(d).af.st    := af && TlbCmd.isWrite(cmdReg) && fault_valid
      resp(i).bits.excp(d).af.instr := af && TlbCmd.isExec(cmdReg) && fault_valid
    }

    (hit, miss, validReg)
  }

  val readResult = (0 until Width).map(TLBRead(_))
  val hitVec = readResult.map(_._1)
  val missVec = readResult.map(_._2)
  val validRegVec = readResult.map(_._3)

  // replacement
  def get_access(one_hot: UInt, valid: Bool): Valid[UInt] = {
    val res = Wire(Valid(UInt(log2Up(one_hot.getWidth).W)))
    res.valid := Cat(one_hot).orR && valid
    res.bits := OHToUInt(one_hot)
    res
  }


  val refill_idx = if (q.outReplace) {
    io.replace.page.access <> tlbfa.access
    io.replace.page.chosen_set := DontCare  // only fa
    io.replace.page.refillIdx
  } else {
    val re = ReplacementPolicy.fromString(q.replacer, q.nWays)
    re.access(tlbfa.access.map(_.touch_ways))
    re.way
  }

  val refill = ptw_resp_v && !sfence.valid && !satp.changed

  tlbfa.w_apply(
    valid = refill,
    wayIdx = refill_idx,
    data = ptw_resp,
  )

  // if sameCycle, just req.valid
  // if !sameCycle, add one more RegNext based on !sameCycle's RegNext
  // because sram is too slow and dtlb is too distant from dtlbRepeater
  for (i <- 0 until Width) {
    io.ptw.req(i).valid :=  need_RegNextInit(!q.sameCycle, validRegVec(i) && missVec(i), false.B) &&
      !RegNext(refill, init = false.B) &&
      param_choose(!q.sameCycle, !RegNext(RegNext(refill, init = false.B), init = false.B), true.B)
    when (RegEnable(io.requestor(i).req_kill, RegNext(io.requestor(i).req.fire))) {
      io.ptw.req(i).valid := false.B
    }
//    io.ptw.req(i).bits.vpn := need_RegNext(!q.sameCycle, need_RegNext(!q.sameCycle, reqAddr(i).vpn))
    io.ptw.req(i).bits.vpn := need_RegNext(!q.sameCycle, need_RegEnable(!q.sameCycle, reqAddr(i).vpn, reqValid(i)))
  }
  io.ptw.resp.ready := true.B

  def need_RegNext[T <: Data](need: Boolean, data: T): T = {
    if (need) RegNext(data)
    else data
  }
  def need_RegEnable[T <: Data](need: Boolean, data: T, enable: Bool): T = {
    if (need) RegEnable(data,enable)
    else data
  }
  def need_RegNextInit[T <: Data](need: Boolean, data: T, init_value: T): T = {
    if (need) RegNext(data, init = init_value)
    else data
  }

  def param_choose[T <: Data](need: Boolean, truedata: T, falsedata: T): T = {
    if (need) truedata
    else falsedata
  }

  if (!q.shouldBlock) {
    for (i <- 0 until Width) {
      XSPerfAccumulate("first_access" + Integer.toString(i, 10), validRegVec(i) && vmEnable_dup.head && RegNext(req(i).bits.debug.isFirstIssue))
      XSPerfAccumulate("access" + Integer.toString(i, 10), validRegVec(i) && vmEnable_dup.head)
    }
    for (i <- 0 until Width) {
      XSPerfAccumulate("first_miss" + Integer.toString(i, 10), validRegVec(i) && vmEnable_dup.head && missVec(i) && RegNext(req(i).bits.debug.isFirstIssue))
      XSPerfAccumulate("miss" + Integer.toString(i, 10), validRegVec(i) && vmEnable_dup.head && missVec(i))
    }
  } else {
    // NOTE: ITLB is blocked, so every resp will be valid only when hit
    // every req will be ready only when hit
    for (i <- 0 until Width) {
      XSPerfAccumulate(s"access${i}", io.requestor(i).req.fire && vmEnable_dup.head)
      XSPerfAccumulate(s"miss${i}", ptw.req(i).fire)
    }

  }
  //val reqCycleCnt = Reg(UInt(16.W))
  //reqCycleCnt := reqCycleCnt + BoolStopWatch(ptw.req(0).fire, ptw.resp.fire || sfence.valid)
  //XSPerfAccumulate("ptw_req_count", ptw.req.fire)
  //XSPerfAccumulate("ptw_req_cycle", Mux(ptw.resp.fire, reqCycleCnt, 0.U))
  XSPerfAccumulate("ptw_resp_count", ptw.resp.fire)
  XSPerfAccumulate("ptw_resp_pf_count", ptw.resp.fire && ptw.resp.bits.pf)
  XSPerfAccumulate("ptw_resp_sp_count", ptw.resp.fire && !ptw.resp.bits.pf && (ptw.resp.bits.entry.level.get === 0.U || ptw.resp.bits.entry.level.get === 1.U))

  // Log
  for(i <- 0 until Width) {
    XSDebug(req(i).valid, p"req(${i.U}): (${req(i).valid} ${req(i).ready}) ${req(i).bits}\n")
    XSDebug(resp(i).valid, p"resp(${i.U}): (${resp(i).valid} ${resp(i).ready}) ${resp(i).bits}\n")
  }

  XSDebug(sfence.valid, p"Sfence: ${sfence}\n")
  XSDebug(ParallelOR(reqValid)|| ptw.resp.valid, p"CSR: ${csr_dup.head}\n")
  XSDebug(ParallelOR(reqValid) || ptw.resp.valid, p"vmEnable:${vmEnable_dup.head} hit:${Binary(VecInit(hitVec).asUInt)} miss:${Binary(VecInit(missVec).asUInt)}\n")
  for (i <- ptw.req.indices) {
    XSDebug(ptw.req(i).fire, p"L2TLB req:${ptw.req(i).bits}\n")
  }
  XSDebug(ptw.resp.valid, p"L2TLB resp:${ptw.resp.bits} (v:${ptw.resp.valid}r:${ptw.resp.ready}) \n")

  println(s"${q.name}: page: ${q.nWays} Ways fully-associative ${q.replacer.get}")

//   // NOTE: just for simple tlb debug, comment it after tlb's debug
  // assert(!io.ptw.resp.valid || io.ptw.resp.bits.entry.tag === io.ptw.resp.bits.entry.ppn, "Simple tlb debug requires vpn === ppn")

  val perfEvents = if(!q.shouldBlock) {
    Seq(
      ("access", PopCount((0 until Width).map(i => vmEnable_dup.head && validRegVec(i)))              ),
      ("miss  ", PopCount((0 until Width).map(i => vmEnable_dup.head && validRegVec(i) && missVec(i)))),
    )
  } else {
    Seq(
      ("access", PopCount((0 until Width).map(i => io.requestor(i).req.fire))),
      ("miss  ", PopCount((0 until Width).map(i => ptw.req(i).fire))         ),
    )
  }
  generatePerfEvent()
}

class TlbReplace(Width: Int, q: TLBParameters)(implicit p: Parameters) extends TlbModule {
  val io = IO(new TlbReplaceIO(Width, q))

  // no sa
  val re = ReplacementPolicy.fromString(q.replacer, q.nWays)
  re.access(io.page.access.map(_.touch_ways))
  io.page.refillIdx := re.way
}

object TLB {
  def apply
  (
    in: Seq[BlockTlbRequestIO],
    sfence: SfenceBundle,
    csr: TlbCsrBundle,
    width: Int,
    nRespDups: Int = 1,
    shouldBlock: Boolean,
    q: TLBParameters
  )(implicit p: Parameters) = {
    require(in.length == width)

    val tlb = Module(new TLB(width, nRespDups, q))

    tlb.io.sfence <> sfence
    tlb.io.csr <> csr
    tlb.suggestName(s"tlb_${q.name}")

    if (!shouldBlock) { // dtlb
      for (i <- 0 until width) {
        tlb.io.requestor(i) <> in(i)
        // tlb.io.requestor(i).req.valid := in(i).req.valid
        // tlb.io.requestor(i).req.bits := in(i).req.bits
        // in(i).req.ready := tlb.io.requestor(i).req.ready

        // in(i).resp.valid := tlb.io.requestor(i).resp.valid
        // in(i).resp.bits := tlb.io.requestor(i).resp.bits
        // tlb.io.requestor(i).resp.ready := in(i).resp.ready
      }
    } else { // itlb
      //require(width == 1)
      (0 until width).map{ i =>
        tlb.io.requestor(i).req.valid := in(i).req.valid
        tlb.io.requestor(i).req.bits := in(i).req.bits
        tlb.io.requestor(i).req_kill := false.B
        in(i).req.ready := !tlb.io.requestor(i).resp.bits.miss && in(i).resp.ready && tlb.io.requestor(i).req.ready

        require(q.missSameCycle || q.sameCycle)
        // NOTE: the resp.valid seems to be useless, it must be true when need
        //       But don't know what happens when true but not need, so keep it correct value, not just true.B
        if (q.missSameCycle && !q.sameCycle) {
          in(i).resp.valid := tlb.io.requestor(i).resp.valid && !RegNext(tlb.io.requestor(i).resp.bits.miss)
        } else {
          in(i).resp.valid := tlb.io.requestor(i).resp.valid && !tlb.io.requestor(i).resp.bits.miss
        }
        in(i).resp.bits := tlb.io.requestor(i).resp.bits
        tlb.io.requestor(i).resp.ready := in(i).resp.ready
      }
    }
    tlb.io.ptw
  }
}
