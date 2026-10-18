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

  /** Two full-width factors, plus headroom for `maxDepth` accumulations and the complex subtract. */
  val accWidth: Int = 2 * dataWidth + log2Ceil(maxDepth) + 1

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

  val accRe = Reg(Vec(tile * tile, SInt(accWidth.W)))
  val accIm = Reg(Vec(tile * tile, SInt(accWidth.W)))

  for (row <- 0 until tile; col <- 0 until tile) {
    val index = row * tile + col

    // (a + bi)(c + di) = (ac - bd) + (ad + bc)i, once per cell: tile^2 complex multipliers.
    val productRe = io.aRe(row) * io.bRe(col) - io.aIm(row) * io.bIm(col)
    val productIm = io.aRe(row) * io.bIm(col) + io.aIm(row) * io.bRe(col)

    when(io.clear) {
      accRe(index) := 0.S
      accIm(index) := 0.S
    }.elsewhen(io.inValid) {
      accRe(index) := accRe(index) + productRe
      accIm(index) := accIm(index) + productIm
    }

    io.outRe(index) := (accRe(index) >> fractionBits)(dataWidth - 1, 0).asSInt
    io.outIm(index) := (accIm(index) >> fractionBits)(dataWidth - 1, 0).asSInt
  }
}
