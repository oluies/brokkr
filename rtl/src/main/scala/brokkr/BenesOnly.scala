package brokkr

import chisel3._

/** The Benes network on its own, so its area can be separated from the multipliers around it.
  *
  * Not part of the engine — [[MonomialKernel]] instantiates the network directly. This exists only
  * so `scripts/area.sh` can answer how much of the permute path is shuffle and how much is
  * arithmetic.
  */
class BenesOnly(val maxWidth: Int, val dataWidth: Int) extends Module {
  val dimension: Int = 1 << maxWidth

  val io = IO(new Bundle {
    val control = Input(UInt(Benes.controlBits(dimension).W))
    val inRe = Input(Vec(dimension, SInt(dataWidth.W)))
    val inIm = Input(Vec(dimension, SInt(dataWidth.W)))
    val outRe = Output(Vec(dimension, SInt(dataWidth.W)))
    val outIm = Output(Vec(dimension, SInt(dataWidth.W)))
  })

  val control = Seq.tabulate(Benes.controlBits(dimension))(bit => io.control(bit))

  Benes(io.inRe, control).zipWithIndex.foreach { case (lane, i) => io.outRe(i) := RegNext(lane, 0.S) }
  Benes(io.inIm, control).zipWithIndex.foreach { case (lane, i) => io.outIm(i) := RegNext(lane, 0.S) }
}
