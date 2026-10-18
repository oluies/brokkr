package brokkr

import chisel3._
import chisel3.util._

/** The dense datapath: a full `2^k x 2^k` complex matrix-vector product, one output row per cycle.
  *
  * This is the only one of the three paths that needs multipliers in bulk, and the only one whose
  * storage grows as `4^k` rather than `2^k`. Both costs are the argument for not making every block
  * take this path: a row is `2^k` complex multiply-accumulates, which is `4 * 2^k` real multipliers,
  * against the diagonal path's four.
  *
  * The operator is held in registers here because that keeps the comparison honest — the same
  * storage style as [[DiagonalKernel]]'s phase table, so a synthesis run measures the shape of the
  * datapath rather than the choice of memory macro. A real engine would use SRAM, and at `k = 6`
  * would have to: 64 KiB of operator is not a register file.
  *
  * Fixed point and truncation follow [[DiagonalKernel]], including the two integer bits. The accumulation is widened rather than
  * truncated per term, so the only rounding is the final shift; with a unitary operator and a
  * normalised input every output stays inside Q2.`fractionBits`.
  *
  * @param maxWidth  qubits in the block, so the operator is `2^maxWidth` square
  * @param dataWidth bits per real component, including the sign
  */
class DenseKernel(val maxWidth: Int, val dataWidth: Int) extends Module {
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

  val io = IO(new Bundle {
    /** Operator load port, addressed row-major as `row * 2^k + col`. */
    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt((2 * maxWidth).W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    /** One slice's amplitudes, already gathered, and the output row wanted this cycle. */
    val inValid = Input(Bool())
    val row = Input(UInt(maxWidth.W))
    val inRe = Input(Vec(dimension, SInt(dataWidth.W)))
    val inIm = Input(Vec(dimension, SInt(dataWidth.W)))

    val outValid = Output(Bool())
    val outRe = Output(SInt(dataWidth.W))
    val outIm = Output(SInt(dataWidth.W))
  })

  val operatorRe = Reg(Vec(dimension * dimension, SInt(dataWidth.W)))
  val operatorIm = Reg(Vec(dimension * dimension, SInt(dataWidth.W)))

  when(io.writeEnable) {
    operatorRe(io.writeAddr) := io.writeRe
    operatorIm(io.writeAddr) := io.writeIm
  }

  // One complex multiply per column, all of them at once: this is the MAC array.
  val terms = (0 until dimension).map { col =>
    val address = Cat(io.row, col.U(maxWidth.W))
    val entryRe = operatorRe(address)
    val entryIm = operatorIm(address)

    (
      entryRe * io.inRe(col) - entryIm * io.inIm(col),
      entryRe * io.inIm(col) + entryIm * io.inRe(col)
    )
  }

  // Widened accumulation, so the only rounding in the row is the final shift.
  val sumRe = terms.map(_._1).reduce(_ +& _)
  val sumIm = terms.map(_._2).reduce(_ +& _)

  private def narrow(wide: SInt): SInt = (wide >> fractionBits)(dataWidth - 1, 0).asSInt

  io.outRe := RegNext(narrow(sumRe), 0.S)
  io.outIm := RegNext(narrow(sumIm), 0.S)
  io.outValid := RegNext(io.inValid, false.B)
}
