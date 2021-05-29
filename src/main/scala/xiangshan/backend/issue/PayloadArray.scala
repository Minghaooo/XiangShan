package xiangshan.backend.issue

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._

class PayloadArrayReadIO[T <: Data](gen: T, config: RSConfig) extends Bundle {
  val addr = Input(UInt(config.numEntries.W))
  val data = Output(gen)

  override def cloneType: PayloadArrayReadIO.this.type =
    new PayloadArrayReadIO(gen, config).asInstanceOf[this.type]
}

class PayloadArrayWriteIO[T <: Data](gen: T, config: RSConfig) extends Bundle {
  val enable = Input(Bool())
  val addr   = Input(UInt(config.numEntries.W))
  val data   = Input(gen)

  override def cloneType: PayloadArrayWriteIO.this.type =
    new PayloadArrayWriteIO(gen, config).asInstanceOf[this.type]
}

class PayloadArray[T <: Data](gen: T, config: RSConfig)(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle {
    val read = Vec(config.numDeq, new PayloadArrayReadIO(gen, config))
    val write = Vec(config.numEnq, new PayloadArrayWriteIO(gen, config))
  })

  val payload = Reg(Vec(config.numEntries, gen))

  // read ports
  io.read.map(_.data).zip(io.read.map(_.addr)).map {
    case (data, addr) => data := Mux1H(addr, payload)
    XSError(PopCount(addr) > 1.U, p"raddr ${Binary(addr)} is not one-hot\n")
  }

  // write ports
  for (i <- 0 until config.numEntries) {
    val wenVec = VecInit(io.write.map(w => w.enable && w.addr(i)))
    val wen = wenVec.asUInt.orR
    val wdata = Mux1H(wenVec, io.write.map(_.data))
    when (wen) {
      payload(i) := wdata
    }
    XSError(PopCount(wenVec) > 1.U, p"wenVec ${Binary(wenVec.asUInt)} is not one-hot\n")
  }

  for (w <- io.write) {
    // check for writing to multiple entries
    XSError(w.enable && PopCount(w.addr.asBools) =/= 1.U,
      p"write address ${Binary(w.addr)} is not one-hot\n")
    // write log
    XSDebug(w.enable, p"write to address ${OHToUInt(w.addr)}\n")
  }

}
