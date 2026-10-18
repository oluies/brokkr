package brokkr

import chisel3._
import chisel3.util._

/** The permute path: scale each of a slice's `2^k` amplitudes, then permute them.
  *
  * A monomial operator is a permutation with phases — column `j` maps to row `rows(j)` with
  * coefficient `values(j)` — which is what `X`, `Y`, `SWAP`, `CNOT`, `CCNOT` and `CSWAP` fuse into.
  * It needs `2^k` complex multipliers, one per lane, and a shuffle network. It does not need a
  * multiply-accumulate array, which is the point: the dense path's cost is the accumulation, and
  * this avoids all of it.
  *
  * A whole slice moves per cycle, unlike [[DenseKernel]], which emits one output row per cycle
  * because each row needs every input. That difference matters when comparing area: this is `2^k`
  * times the throughput for roughly `2^k` times fewer gates.
  *
  * The shuffle is a [[Benes]] network rather than an Omega network, because the permutations that
  * occur are mostly not bit permutations — see [[Benes]].
  *
  * @param maxWidth  qubits in the block, so `2^maxWidth` lanes
  * @param dataWidth bits per real component, including the sign
  */
class MonomialKernel(val maxWidth: Int, val dataWidth: Int) extends Module {
  require(maxWidth >= 1, "a block spans at least one qubit")
  require(dataWidth >= 4, "a sign and three fraction bits is the smallest sensible format")

  val dimension: Int = 1 << maxWidth
  /** Q2.`fractionBits`: a stored integer `x` means `x / 2^fractionBits`, so the range is
    * `[-2, 2)`.
    *
    * Two integer bits rather than one, which costs a bit of precision and buys correctness. A
    * unitary operator has entries of exactly ±1 all over it — every `X`, `CNOT`, `SWAP` and Toffoli
    * is a permutation matrix of them — and `1.0` is not representable in Q1.f, where the range stops
    * at `1 - 2^-f`. It wraps to `-1.0`, silently flipping the sign of the most common coefficient
    * there is. Amplitudes never need the extra range, but sharing one format keeps a single shift
    * in the datapath.
    */
  val fractionBits: Int = dataWidth - 2
  val controlBits: Int = Benes.controlBits(dimension)

  val io = IO(new Bundle {
    /** Phase table load port: one coefficient per column. */
    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(maxWidth.W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    /** Benes switch settings, computed by the host once per block. */
    val control = Input(UInt(controlBits.W))

    val inValid = Input(Bool())
    val inRe = Input(Vec(dimension, SInt(dataWidth.W)))
    val inIm = Input(Vec(dimension, SInt(dataWidth.W)))

    val outValid = Output(Bool())
    val outRe = Output(Vec(dimension, SInt(dataWidth.W)))
    val outIm = Output(Vec(dimension, SInt(dataWidth.W)))
  })

  val phaseRe = Reg(Vec(dimension, SInt(dataWidth.W)))
  val phaseIm = Reg(Vec(dimension, SInt(dataWidth.W)))

  when(io.writeEnable) {
    phaseRe(io.writeAddr) := io.writeRe
    phaseIm(io.writeAddr) := io.writeIm
  }

  private def narrow(wide: SInt): SInt = (wide >> fractionBits)(dataWidth - 1, 0).asSInt

  // One complex multiply per lane. No accumulation anywhere.
  val scaled = Seq.tabulate(dimension) { lane =>
    val re = narrow(phaseRe(lane) * io.inRe(lane) - phaseIm(lane) * io.inIm(lane))
    val im = narrow(phaseRe(lane) * io.inIm(lane) + phaseIm(lane) * io.inRe(lane))

    (re, im)
  }

  val control = Seq.tabulate(controlBits)(bit => io.control(bit))
  val permutedRe = Benes(scaled.map(_._1), control)
  val permutedIm = Benes(scaled.map(_._2), control)

  (0 until dimension).foreach { lane =>
    io.outRe(lane) := RegNext(permutedRe(lane), 0.S)
    io.outIm(lane) := RegNext(permutedIm(lane), 0.S)
  }

  io.outValid := RegNext(io.inValid, false.B)
}
