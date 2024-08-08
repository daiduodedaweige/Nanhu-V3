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
package xiangshan.backend.issue
import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan.FuType.ldu

class SelectRespArbiter(bankNum:Int, entryNum:Int, inNum:Int, haveEqual:Boolean)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val in = Vec(inNum, Flipped(Decoupled(new SelectResp(bankNum, entryNum))))
    val out = Decoupled(new SelectResp(bankNum, entryNum))
    val chosen = Output(UInt(inNum.W))
  })

  private val selector = Module(new SelectPolicy(inNum, false, haveEqual))
  selector.io.in.zip(io.in).foreach({case(si, in) =>
    si.valid := in.valid
    si.bits := in.bits.info.robPtr
  })
  io.out.valid := selector.io.out.valid
  io.out.bits := Mux1H(selector.io.out.bits, io.in.map(_.bits))
  io.chosen := selector.io.out.bits
  io.in.map(_.ready).zipWithIndex.foreach({ case(r, i) =>
    if(i == 0){
      r := io.out.ready
    } else {
      r := io.out.ready && !io.in.take(i).map(_.valid).reduce(_|_)
    }
  })
}

class SelectRespArbiterOldest(bankNum:Int, entryNum:Int, inNum:Int, haveEqual:Boolean)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val in = Vec(inNum, Flipped(Decoupled(new SelectResp(bankNum, entryNum))))
    val out = Decoupled(new SelectResp(bankNum, entryNum))
    val chosen = Output(UInt(inNum.W))
  })

  private val selector = Module(new SelectPolicy(inNum, true, haveEqual))
  selector.io.in.zip(io.in).foreach({case(si, in) =>
    si.valid := in.valid
    si.bits := in.bits.info.robPtr
  })
  io.out.valid := selector.io.out.valid
  io.out.bits := Mux1H(selector.io.out.bits, io.in.map(_.bits))
  io.chosen := selector.io.out.bits
  val chosenLocal = selector.io.out.bits.asBools
  io.in.map(_.ready).zipWithIndex.foreach({ case(r, i) =>
    r := io.out.ready && chosenLocal(i)
  })

}


class MemRSSelectRespArbiter(bankNum:Int, entryNum:Int, inNum:Int, haveEqual:Boolean)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val in = Vec(inNum, Flipped(Decoupled(new SelectResp(bankNum, entryNum))))
    val out = Decoupled(new SelectResp(bankNum, entryNum))
    val chosen = Output(UInt(inNum.W))
    val ldStop = Input(Bool())
  })



  private val selector = Module(new SelectPolicy(inNum, false, haveEqual))
  selector.io.in.zip(io.in).foreach({case(si, in) =>
    si.valid := in.valid && !((in.bits.info.fuType === ldu) && io.ldStop)
    si.bits := in.bits.info.robPtr
  })
  io.out.valid := selector.io.out.valid
  io.out.bits := Mux1H(selector.io.out.bits, io.in.map(_.bits))
  io.chosen := selector.io.out.bits
  io.in.map(_.ready).zipWithIndex.foreach({ case(r, i) =>
    if(i == 0){
      r := io.out.ready
    } else {
      r := io.out.ready && !io.in.take(i).map(_.valid).reduce(_|_)
    }
  })
}

