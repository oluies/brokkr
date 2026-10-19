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
  /** Q2.`fractionBits`; see [[Fixed]] for why two integer bits. */
  val fractionBits: Int = Fixed.fractionBits(dataWidth)

  /** The entry format, and the formats the arithmetic over it produces. */
  val working: QFormat = Fixed.working(dataWidth)

  /** One column's contribution: `a·c - b·d`, a product plus the subtract's carry. */
  val term: QFormat = working * working - working * working

  /** The row sum. `dimension` terms need `log2(dimension)` more integer bits than one of them — a
    * width the type computes instead of the reduce tree growing by a bit per addition.
    */
  val total: QFormat = term.accumulating(dimension)

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

    val coefficientRe = working(entryRe)
    val coefficientIm = working(entryIm)
    val amplitudeRe = working(io.inRe(col))
    val amplitudeIm = working(io.inIm(col))

    (
      (coefficientRe * amplitudeRe - coefficientIm * amplitudeIm).widen(total),
      (coefficientRe * amplitudeIm + coefficientIm * amplitudeRe).widen(total)
    )
  }

  // Widened accumulation, so the only rounding in the row is the final narrowing. Every addition
  // happens at `total`, which already has room for all `dimension` of them, rather than the tree
  // growing a bit per level as `reduce(_ +& _)` did.
  val sumRe = terms.map(_._1).reduce((a, b) => a.accumulate(b, total))
  val sumIm = terms.map(_._2).reduce((a, b) => a.accumulate(b, total))

  io.outRe := RegNext(sumRe.to(working).bits, 0.S)
  io.outIm := RegNext(sumIm.to(working).bits, 0.S)
  io.outValid := RegNext(io.inValid, false.B)
}
