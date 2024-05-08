/***************************************************************************************
 * Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
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
/***************************************************************************************
 * Author: Liang Sen
 * E-mail: liangsen20z@ict.ac.cn
 * Date: 2023-06-19
 ****************************************************************************************/
package xiangshan.backend.execute.exu
import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan.backend.execute.fu.csr.{CSR, CSRFileIO}
import xiangshan.backend.execute.fu.fence.{SfenceBundle, _}
import xiangshan.backend.execute.fu.jmp._
import xiangshan.backend.execute.fu.{FUWithRedirect, FuConfigs, FunctionUnit}
import xiangshan._
import xs.utils.{DelayN, ParallelMux}

class FenceIO(implicit p: Parameters) extends XSBundle {
  val sfence = Output(new SfenceBundle)
  val fencei = new FenceIBundle
  val sbuffer = new FenceToSbuffer
}

class MiscExu (id:Int, complexName:String, val bypassInNum:Int)(implicit p:Parameters) extends BasicExu{
  private val cfg = ExuConfig(
    name = "MiscExu",
    id = id,
    complexName = complexName,
    fuConfigs = Seq(FuConfigs.fenceCfg, FuConfigs.mouCfg, FuConfigs.csrCfg),
    exuType = ExuType.misc,
    writebackToRob = true,
    writebackToVms = false
  )
  val issueNode = new ExuInputNode(cfg)
  val writebackNode = new ExuOutputNode(cfg)
  override lazy val module = new MiscExuImpl(this, cfg)
}
class FakeMou()(implicit p:Parameters) extends FunctionUnit(p(XSCoreParamsKey).XLEN) {
  val issueToMou = IO(Decoupled(new ExuInput))
  val writebackFromMou = IO(Flipped(Decoupled(new ExuOutput)))
  io.in.ready := issueToMou.ready
  issueToMou.valid := io.in.valid
  issueToMou.bits.src := io.in.bits.src
  issueToMou.bits.uop := io.in.bits.uop
  issueToMou.bits.vm := DontCare
  io.out.valid := writebackFromMou.valid
  io.out.bits := writebackFromMou.bits
  writebackFromMou.ready := io.out.ready
}

class MiscExuImpl(outer:MiscExu, exuCfg:ExuConfig)(implicit p:Parameters) extends BasicExuImpl(outer) {
  val io = IO(new Bundle{
    val bypassIn = Input(Vec(outer.bypassInNum, Valid(new ExuOutput)))
    val issueToMou = Decoupled(new ExuInput)
    val writebackFromMou = Flipped(Decoupled(new ExuOutput))
    val fenceio = new FenceIO
    val csrio = new CSRFileIO
  })
  private val issuePort = outer.issueNode.in.head._1
  private val writebackPort = outer.writebackNode.out.head._1
  private val fence = Module(new Fence)
  private val mou = Module(new FakeMou)
  private val csr = Module(new CSR)

  issuePort.issue.ready := true.B

  private val finalIssueSignals = bypassSigGen(io.bypassIn, issuePort, outer.bypassInNum > 0)
  private val fuSeq = Seq(fence, mou, csr)
  fuSeq.zip(exuCfg.fuConfigs).foreach({ case (m, cfg) =>
    m.io.redirectIn := redirectIn
    m.io.in.valid := finalIssueSignals.valid && finalIssueSignals.bits.uop.ctrl.fuType === cfg.fuType
    m.io.in.bits.uop := finalIssueSignals.bits.uop
    m.io.in.bits.src := finalIssueSignals.bits.src
    m.io.out.ready := true.B

    val isCsr = finalIssueSignals.bits.uop.ctrl.fuType === FuType.csr
    val isExclusive = finalIssueSignals.bits.uop.ctrl.noSpecExec && finalIssueSignals.bits.uop.ctrl.blockBackward
    when(m.io.in.valid){
      assert(m.io.in.ready)
      assert(isCsr || isExclusive)
    }
  })

  private val fuOut = fuSeq.map(_.io.out)
  private val outSel = fuOut.map(_.fire)
  private val outData = fuOut.map(_.bits)
  private val finalData = Mux1H(outSel, outData)

  writebackPort := DontCare
  writebackPort.valid := outSel.reduce(_ || _)
  writebackPort.bits.wakeupValid := true.B
  writebackPort.bits.uop := finalData.uop
  writebackPort.bits.data := finalData.data

  assert(PopCount(outSel) === 1.U || PopCount(outSel) === 0.U)

  io.issueToMou <> mou.issueToMou
  io.writebackFromMou <> mou.writebackFromMou

  writebackPort.bits.fflags := DontCare
  writebackPort.bits.redirect := Mux(csr.redirectOutValid, csr.redirectOut, fence.redirectOut)
  writebackPort.bits.redirectValid := csr.redirectOutValid || fence.redirectOutValid
  writebackPort.bits.debug.isMMIO := false.B
  writebackPort.bits.debug.isPerfCnt := csr.csrio.isPerfCnt
  writebackPort.bits.debug.paddr := 0.U
  writebackPort.bits.debug.vaddr := 0.U

  io.fenceio.sfence := fence.sfence
  io.fenceio.fencei <> fence.fencei
  io.fenceio.sbuffer <> fence.toSbuffer
  fence.toSbuffer.sbIsEmpty := io.fenceio.sbuffer.sbIsEmpty
  fence.disableSfence := csr.csrio.disableSfence
  fence.priviledgeMode := csr.csrio.priviledgeMode
  csr.csrio <> io.csrio
  csr.csrio.vcsr.robWb.vxsat := io.csrio.vcsr.robWb.vxsat
  csr.csrio.vcsr.robWb.vstart := io.csrio.vcsr.robWb.vstart
  io.csrio.vcsr.vtype.vtypeRead.readEn := RegNext(csr.csrio.vcsr.vtype.vtypeRead.readEn, false.B)
  io.csrio.vcsr.vtype.vlRead.readEn := RegNext(csr.csrio.vcsr.vtype.vlRead.readEn, false.B)
  csr.csrio.vcsr.vtype.vlUpdate := Pipe(io.csrio.vcsr.vtype.vlUpdate)
  io.csrio.tlb := DelayN(csr.csrio.tlb, 2)
  io.csrio.customCtrl := DelayN(csr.csrio.customCtrl, 2)
  csr.csrio.exception := Pipe(io.csrio.exception)
}
