package brokkr

import chisel3._
import chisel3.util._

/** A complex outer-product accumulator: the density-matrix engine's whole datapath.
  *
  * The density-matrix case is the one this project's measurements keep pointing at. A channel is
  * `rho -> sum K rho K†`, which is two complex matrix products per Kraus operator, and the
  * arithmetic per byte grows with the system — 683 flop/byte at ten qubits against 15.9 for the
  * best fused state-vector block. Everything the state-vector engine needed and this does not — a
  * shuffle fabric, three datapaths, a bank mapping from coding theory, a fusion pass — falls away,
  * because a matrix product has regular dense access and its operators arrive ready-made.
  *
  * What is left is this: a `tile × tile` array of complex accumulators fed one column of `A` and
  * one row of `B` per cycle, accumulating their outer product. After `depth` cycles the array holds
  * a tile of `C = A · B`. That is the same structure as CUTE's `MatrixTE`, which is the point —
  * a state-vector engine is not CUTE, and this very nearly is.
  *
  * ==Arithmetic==
  * Entries are the shared [[Fixed]] format, Q2.`fractionBits`. Products are accumulated at full
  * width and narrowed only on the way out, so a tile of depth `d` rounds once rather than `d`
  * times — the same discipline as [[DenseKernel]], and the reason its error did not grow with
  * width. The accumulator is sized for `maxDepth` terms without overflow.
  *
  * @param tile      the output tile is `tile × tile`, costing `tile²` complex multipliers
  * @param dataWidth bits per real component, including the sign
  * @param maxDepth  the longest accumulation the accumulator must hold without overflowing
  */
class ComplexGemm(val tile: Int, val dataWidth: Int, val maxDepth: Int) extends Module {
  require(tile >= 1, "a tile needs at least one row")
  require(dataWidth >= 4, "a sign and three fraction bits is the smallest sensible format")
  require(maxDepth >= 1, "an accumulation needs at least one term")

  val fractionBits: Int = Fixed.fractionBits(dataWidth)

  /** The entry format, Q2.`fractionBits`. See [[Fixed]] for why two integer bits. */
  val working: QFormat = Fixed.working(dataWidth)

  /** One term of the accumulation: `a·c - b·d`, so a product's format plus a subtract's carry. */
  val term: QFormat = working * working - working * working

  /** Two full-width factors, plus headroom for `maxDepth` accumulations and the complex subtract.
    *
    * This used to be asserted as `2 * dataWidth + log2Ceil(maxDepth) + 1`, a number that had to be
    * checked against the arithmetic by hand. It is now derived from it: Q2.22 times Q2.22 is Q4.44,
    * the subtract carries one bit to Q5.44, and `maxDepth` of those need `log2(maxDepth)` more.
    * At `dataWidth` 24 and `maxDepth` 1024 that is Q15.44 — the same 59 bits, now a consequence.
    */
  val acc: QFormat = term.accumulating(maxDepth)

  val accWidth: Int = acc.width

  val io = IO(new Bundle {
    /** Drop the accumulators, starting a new tile. */
    val clear = Input(Bool())

    /** One column of `A` and one row of `B`. */
    val inValid = Input(Bool())
    val aRe = Input(Vec(tile, SInt(dataWidth.W)))
    val aIm = Input(Vec(tile, SInt(dataWidth.W)))
    val bRe = Input(Vec(tile, SInt(dataWidth.W)))
    val bIm = Input(Vec(tile, SInt(dataWidth.W)))

    /** The accumulated tile, row-major, narrowed back to the working format. */
    val outRe = Output(Vec(tile * tile, SInt(dataWidth.W)))
    val outIm = Output(Vec(tile * tile, SInt(dataWidth.W)))
  })

  val accRe = Reg(Vec(tile * tile, SInt(acc.width.W)))
  val accIm = Reg(Vec(tile * tile, SInt(acc.width.W)))

  for (row <- 0 until tile; col <- 0 until tile) {
    val index = row * tile + col

    val aRe = working(io.aRe(row))
    val aIm = working(io.aIm(row))
    val bRe = working(io.bRe(col))
    val bIm = working(io.bIm(col))

    // (a + bi)(c + di) = (ac - bd) + (ad + bc)i, once per cell: tile^2 complex multipliers.
    val productRe = aRe * bRe - aIm * bIm
    val productIm = aRe * bIm + aIm * bRe

    when(io.clear) {
      accRe(index) := 0.S
      accIm(index) := 0.S
    }.elsewhen(io.inValid) {
      accRe(index) := acc(accRe(index)).accumulate(productRe, acc).bits
      accIm(index) := acc(accIm(index)).accumulate(productIm, acc).bits
    }

    // The only format change in the kernel, and it has to be written to happen.
    io.outRe(index) := acc(accRe(index)).to(working).bits
    io.outIm(index) := acc(accIm(index)).to(working).bits
  }
}
