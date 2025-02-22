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

package xiangshan.frontend

import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.rocket.{RVCDecoder, ExpandedInstruction}
import chisel3.{util, _}
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.frontend.icache._
import xiangshan.backend.decode.isa.predecode.PreDecodeInst

trait HasPdConst extends HasXSParameter with HasICacheParameters with HasIFUConst{
  def isRVC(inst: UInt) = (inst(1,0) =/= 3.U)
  def isLink(reg:UInt) = reg === 1.U || reg === 5.U
  def brInfo(instr: UInt) = {
    val brType::Nil = ListLookup(instr, List(BrType.notCFI), PreDecodeInst.brTable)
    val rd = Mux(isRVC(instr), instr(12), instr(11,7))
    val rs = Mux(isRVC(instr), Mux(brType === BrType.jal, 0.U, instr(11, 7)), instr(19, 15))
    val isCall = (brType === BrType.jal && !isRVC(instr) || brType === BrType.jalr) && isLink(rd) // Only for RV64
    val isRet = brType === BrType.jalr && isLink(rs) && !isCall
    List(brType, isCall, isRet)
  }
  def jal_offset(inst: UInt, rvc: Bool): UInt = {
    val rvc_offset = Cat(inst(12), inst(8), inst(10, 9), inst(6), inst(7), inst(2), inst(11), inst(5, 3), 0.U(1.W))
    val rvi_offset = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
    val max_width = rvi_offset.getWidth
    SignExt(Mux(rvc, SignExt(rvc_offset, max_width), SignExt(rvi_offset, max_width)), XLEN)
  }
  def br_offset(inst: UInt, rvc: Bool): UInt = {
    val rvc_offset = Cat(inst(12), inst(6, 5), inst(2), inst(11, 10), inst(4, 3), 0.U(1.W))
    val rvi_offset = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
    val max_width = rvi_offset.getWidth
    SignExt(Mux(rvc, SignExt(rvc_offset, max_width), SignExt(rvi_offset, max_width)), XLEN)
  }

  def NOP = "h4501".U(16.W)
}

object BrType {
  def notCFI   = "b00".U
  def branch  = "b01".U
  def jal     = "b10".U
  def jalr    = "b11".U
  def apply() = UInt(2.W)
}

object ExcType {  //TODO:add exctype
  def notExc = "b000".U
  def apply() = UInt(3.W)
}

class PreDecodeInfo extends Bundle {  // 8 bit
  val valid   = Bool()
  val isRVC   = Bool()
  val brType  = UInt(2.W)
  val isCall  = Bool()
  val isRet   = Bool()
  //val excType = UInt(3.W)
  def isBr    = brType === BrType.branch
  def isJal   = brType === BrType.jal
  def isJalr  = brType === BrType.jalr
  def notCFI  = brType === BrType.notCFI
}

class PreDecodeResp(implicit p: Parameters) extends XSBundle with HasPdConst {
  val pd = Vec(PredictWidth, new PreDecodeInfo)
  val hasHalfValid = Vec(PredictWidth, Bool())
  val expInstr = Vec(PredictWidth, UInt(32.W))
  val jumpOffset = Vec(PredictWidth, UInt(XLEN.W))
//  val hasLastHalf = Bool()
  val triggered    = Vec(PredictWidth, new TriggerCf)
}

class PreDecode(implicit p: Parameters) extends XSModule with HasPdConst{
  val io = IO(new Bundle() {
    val in = Input(new IfuToPreDecode)
    val out = Output(new PreDecodeResp)
  })

  val data          = io.in.data
//  val lastHalfMatch = io.in.lastHalfMatch
  val validStart, validEnd = Wire(Vec(PredictWidth, Bool()))
  val h_validStart, h_validEnd = Wire(Vec(PredictWidth, Bool()))

  val rawInsts = if (HasCExtension) VecInit((0 until PredictWidth).map(i => Cat(data(i+1), data(i))))
  else         VecInit((0 until PredictWidth).map(i => data(i)))

  // Frontend Triggers
  val tdata = Reg(Vec(4, new MatchTriggerIO))
  when(io.in.frontendTrigger.t.valid) {
    tdata(io.in.frontendTrigger.t.bits.addr) := io.in.frontendTrigger.t.bits.tdata
  }
  io.out.triggered.map{i => i := 0.U.asTypeOf(new TriggerCf)}
  val triggerEnable = RegInit(VecInit(Seq.fill(4)(false.B))) // From CSR, controlled by priv mode, etc.
  triggerEnable := io.in.csrTriggerEnable
  val triggerMapping = Map(0 -> 0, 1 -> 1, 2 -> 6, 3 -> 8)
  val chainMapping = Map(0 -> 0, 2 -> 3, 3 -> 4)

  for (i <- 0 until PredictWidth) {
    val inst           =WireInit(rawInsts(i))
    val expander       = Module(new RVCExpander)
    val currentIsRVC   = isRVC(inst)
    val currentPC      = io.in.pc(i)
    expander.io.in             := inst

    val brType::isCall::isRet::Nil = brInfo(inst)
    val jalOffset = jal_offset(inst, currentIsRVC)
    val brOffset  = br_offset(inst, currentIsRVC)

    //val lastIsValidEnd =  if (i == 0) { !lastHalfMatch } else { validEnd(i-1) || !HasCExtension.B }
    val lastIsValidEnd =   if (i == 0) { true.B } else { validEnd(i-1) || !HasCExtension.B }
    validStart(i)   := (lastIsValidEnd || !HasCExtension.B)
    validEnd(i)     := validStart(i) && currentIsRVC || !validStart(i) || !HasCExtension.B

    //prepared for last half match
    //TODO if HasCExtension
    val h_lastIsValidEnd = if (i == 0) { false.B } else { h_validEnd(i-1) || !HasCExtension.B }
    h_validStart(i)   := (h_lastIsValidEnd || !HasCExtension.B)
    h_validEnd(i)     := h_validStart(i) && currentIsRVC || !h_validStart(i) || !HasCExtension.B

    io.out.hasHalfValid(i)        := h_validStart(i)

    io.out.triggered(i).triggerTiming   := DontCare//VecInit(Seq.fill(10)(false.B))
    io.out.triggered(i).triggerHitVec   := DontCare//VecInit(Seq.fill(10)(false.B))
    io.out.triggered(i).triggerChainVec := DontCare//VecInit(Seq.fill(5)(false.B))
    for (j <- 0 until 4) {
      val hit = Mux(tdata(j).select, TriggerCmp(Mux(currentIsRVC, inst(15, 0), inst), tdata(j).tdata2, tdata(j).matchType, triggerEnable(j)),
        TriggerCmp(currentPC, tdata(j).tdata2, tdata(j).matchType, triggerEnable(j)))
      io.out.triggered(i).triggerHitVec(triggerMapping(j)) := hit
      io.out.triggered(i).triggerTiming(triggerMapping(j)) := hit && tdata(j).timing
      if(chainMapping.contains(j)) io.out.triggered(i).triggerChainVec(chainMapping(j)) := hit && tdata(j).chain
    }

    io.out.pd(i).valid         := validStart(i)
    io.out.pd(i).isRVC         := currentIsRVC
    io.out.pd(i).brType        := brType
    io.out.pd(i).isCall        := isCall
    io.out.pd(i).isRet         := isRet

    io.out.expInstr(i)         := expander.io.out.bits
    io.out.jumpOffset(i)       := Mux(io.out.pd(i).isBr, brOffset, jalOffset)
  }

//  io.out.hasLastHalf := !io.out.pd(PredictWidth - 1).isRVC && io.out.pd(PredictWidth - 1).valid

  for (i <- 0 until PredictWidth) {
    XSDebug(true.B,
      p"instr ${Hexadecimal(io.out.expInstr(i))}, " +
        p"validStart ${Binary(validStart(i))}, " +
        p"validEnd ${Binary(validEnd(i))}, " +
        p"isRVC ${Binary(io.out.pd(i).isRVC)}, " +
        p"brType ${Binary(io.out.pd(i).brType)}, " +
        p"isRet ${Binary(io.out.pd(i).isRet)}, " +
        p"isCall ${Binary(io.out.pd(i).isCall)}\n"
    )
  }
}

class RVCExpander(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle {
    val in = Input(UInt(32.W))
    val out = Output(new ExpandedInstruction)
  })

  if (HasCExtension) {
    io.out := new RVCDecoder(io.in, XLEN).decode
  } else {
    io.out := new RVCDecoder(io.in, XLEN).passthrough
  }
}

/* ---------------------------------------------------------------------
 * Predict result check
 *
 * ---------------------------------------------------------------------
 */

object FaultType {
  def noFault         = "b000".U
  def jalFault        = "b001".U    //not CFI taken or invalid instruction taken
  def retFault        = "b010".U    //not CFI taken or invalid instruction taken
  def targetFault     = "b011".U
  def faulsePred      = "b100".U    //not CFI taken or invalid instruction taken
  def apply() = UInt(3.W)
}

class CheckInfo extends Bundle {  // 8 bit
  val value  = UInt(3.W)
  def isjalFault      = value === FaultType.jalFault
  def isRetFault      = value === FaultType.retFault
  def istargetFault   = value === FaultType.targetFault
  def isfaulsePred    = value === FaultType.faulsePred
}

class PredCheckerResp(implicit p: Parameters) extends XSBundle with HasPdConst {
  //to Ibuffer write port (timing critical)
  val fixedRange  = Vec(PredictWidth, Bool())
  val fixedTaken  = Vec(PredictWidth, Bool())
  //to Ftq write back port (not timing critical)
  val fixedTarget = Vec(PredictWidth, UInt(VAddrBits.W))
  val fixedMissPred = Vec(PredictWidth,  Bool())
  val faultType   = Vec(PredictWidth, new CheckInfo)
}


class PredChecker(implicit p: Parameters) extends XSModule with HasPdConst {
  val io = IO( new Bundle{
    val in = Input(new IfuToPredChecker)
    val out = Output(new PredCheckerResp)
  })

  val (takenIdx, predTaken)     = (io.in.ftqOffset.bits, io.in.ftqOffset.valid)
  val predTarget                = (io.in.target)
  val (instrRange, instrValid)  = (io.in.instrRange, io.in.instrValid)
  val (pds, pc, jumpOffset)     = (io.in.pds, io.in.pc, io.in.jumpOffset)

  val jalFaultVec, retFaultVec, targetFault, notCFITaken, invalidTaken = Wire(Vec(PredictWidth, Bool()))

  /** remask fault may appear together with other faults, but other faults are exclusive
    * so other f ault mast use fixed mask to keep only one fault would be found and redirect to Ftq
    * we first detecct remask fault and then use fixedRange to do second check
    **/

  /** first check: remask Fault */
  jalFaultVec         := VecInit(pds.zipWithIndex.map{case(pd, i) => pd.isJal && instrRange(i) && instrValid(i) && (takenIdx > i.U && predTaken || !predTaken) })
  retFaultVec         := VecInit(pds.zipWithIndex.map{case(pd, i) => pd.isRet && instrRange(i) && instrValid(i) && (takenIdx > i.U && predTaken || !predTaken) })
  val remaskFault      = VecInit((0 until PredictWidth).map(i => jalFaultVec(i) || retFaultVec(i)))
  val remaskIdx        = ParallelPriorityEncoder(remaskFault.asUInt)
  val needRemask       = ParallelOR(remaskFault)
  val fixedRange       = instrRange.asUInt & (Fill(PredictWidth, !needRemask) | Fill(PredictWidth, 1.U(1.W)) >> ~remaskIdx)

  io.out.fixedRange := fixedRange.asTypeOf((Vec(PredictWidth, Bool())))

  io.out.fixedTaken := VecInit(pds.zipWithIndex.map{case(pd, i) => instrValid (i) && fixedRange(i) && (pd.isRet || pd.isJal || takenIdx === i.U && predTaken && !pd.notCFI)  })

  /** second check: faulse prediction fault and target fault */
  notCFITaken  := VecInit(pds.zipWithIndex.map{case(pd, i) => fixedRange(i) && instrValid(i) && i.U === takenIdx && pd.notCFI && predTaken })
  invalidTaken := VecInit(pds.zipWithIndex.map{case(pd, i) => fixedRange(i) && !instrValid(i)  && i.U === takenIdx  && predTaken })

  /** target calculation  */
  val jumpTargets          = VecInit(pds.zipWithIndex.map{case(pd,i) => pc(i) + jumpOffset(i)})
  targetFault      := VecInit(pds.zipWithIndex.map{case(pd,i) => fixedRange(i) && instrValid(i) && (pd.isJal || pd.isBr) && takenIdx === i.U && predTaken  && (predTarget =/= jumpTargets(i))})

  val seqTargets = VecInit((0 until PredictWidth).map(i => pc(i) + Mux(pds(i).isRVC || !instrValid(i), 2.U, 4.U ) ))

  io.out.faultType.zipWithIndex.map{case(faultType, i) => faultType.value := Mux(jalFaultVec(i) , FaultType.jalFault ,
                                                                             Mux(retFaultVec(i), FaultType.retFault ,
                                                                             Mux(targetFault(i), FaultType.targetFault , 
                                                                             Mux(notCFITaken(i) || invalidTaken(i) ,FaultType.faulsePred, FaultType.noFault))))}

  io.out.fixedMissPred.zipWithIndex.map{case(missPred, i ) => missPred := jalFaultVec(i) || retFaultVec(i) || notCFITaken(i) || invalidTaken(i) || targetFault(i)}
  io.out.fixedTarget.zipWithIndex.map{case(target, i) => target := Mux(jalFaultVec(i) || targetFault(i), jumpTargets(i),  seqTargets(i) )}

}

class FrontendTrigger(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle(){
    val frontendTrigger = Input(new FrontendTdataDistributeIO)
    val csrTriggerEnable = Input(Vec(4, Bool()))
    val triggered    = Output(Vec(PredictWidth, new TriggerCf))

    val pds           = Input(Vec(PredictWidth, new PreDecodeInfo))
    val pc            = Input(Vec(PredictWidth, UInt(VAddrBits.W)))
    val data          = if(HasCExtension) Input(Vec(PredictWidth + 1, UInt(16.W))) 
                        else Input(Vec(PredictWidth, UInt(32.W)))
  })

  val data          = io.data

  val rawInsts = if (HasCExtension) VecInit((0 until PredictWidth).map(i => Cat(data(i+1), data(i))))
                        else         VecInit((0 until PredictWidth).map(i => data(i)))

  val tdata = Reg(Vec(4, new MatchTriggerIO))
  when(io.frontendTrigger.t.valid) {
    tdata(io.frontendTrigger.t.bits.addr) := io.frontendTrigger.t.bits.tdata
  }
  io.triggered.map{i => i := 0.U.asTypeOf(new TriggerCf)}
  val triggerEnable = RegInit(VecInit(Seq.fill(4)(false.B))) // From CSR, controlled by priv mode, etc.
  triggerEnable := io.csrTriggerEnable
  val triggerMapping = Map(0 -> 0, 1 -> 1, 2 -> 6, 3 -> 8)
  val chainMapping = Map(0 -> 0, 2 -> 3, 3 -> 4)

  for (i <- 0 until PredictWidth) {
    val currentPC = io.pc(i)
    val currentIsRVC = io.pds(i).isRVC
    val inst = WireInit(rawInsts(i))

    io.triggered(i).triggerTiming := VecInit(Seq.fill(10)(false.B))
    io.triggered(i).triggerHitVec := VecInit(Seq.fill(10)(false.B))
    io.triggered(i).triggerChainVec := VecInit(Seq.fill(5)(false.B))
    for (j <- 0 until 4) {
      val hit = Mux(tdata(j).select, TriggerCmp(Mux(currentIsRVC, inst(15, 0), inst), tdata(j).tdata2, tdata(j).matchType, triggerEnable(j)),
        TriggerCmp(currentPC, tdata(j).tdata2, tdata(j).matchType, triggerEnable(j)))
      io.triggered(i).triggerHitVec(triggerMapping(j)) := hit
      io.triggered(i).triggerTiming(triggerMapping(j)) := hit && tdata(j).timing
      if(chainMapping.contains(j)) io.triggered(i).triggerChainVec(chainMapping(j)) := hit && tdata(j).chain
    }
  }  
}
