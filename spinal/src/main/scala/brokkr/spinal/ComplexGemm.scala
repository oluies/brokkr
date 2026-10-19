package brokkr.spinal

import _root_.spinal.core._

/** [[brokkr.ComplexGemm]] again, in SpinalHDL, as a control on the per-MAC area claim.
  *
  * The assessment's cleanest number is that four structurally different datapaths all land within
  * 6% of each other at about 20 000 cells per complex multiply-accumulate — phase tables, Benes
  * networks and wide accumulators all being noise against the multipliers. But all four came out of
  * one generator, so the result could instead be a fact about Chisel and firtool rather than about
  * arithmetic. SpinalHDL is an independent Scala HDL with its own Verilog backend, so the same
  * kernel written here and synthesised through the same Yosys script tests exactly that.
  *
  * Two things are deliberately kept identical to the Chisel version, because otherwise the
  * comparison measures the differences rather than the HDLs: the accumulators carry no reset, as
  * `Reg` without `init` in either language, and the arithmetic is the same widths in the same order.
  *
  * ==What the type system does here==
  * The Chisel version tracks its binary point by hand: [[brokkr.Fixed]] is three integer functions,
  * and each kernel shifts by `fractionBits` and truncates at the right moment. Getting that wrong is
  * what the Q1.23 bug was. Here the format is in the type — `SFix(1 exp, -22 exp)` is Q2.22, with
  * the range `[-2, 2)` that the unit coefficients need — and the compiler works out the product and
  * sum formats, refusing any assignment that would silently move the point.
  *
  * @param tile      the output tile is `tile × tile`, costing `tile²` complex multipliers
  * @param dataWidth bits per real component, including the sign
  * @param maxDepth  the longest accumulation the accumulator must hold without overflowing
  */
class ComplexGemm(val tile: Int, val dataWidth: Int, val maxDepth: Int) extends Component {
  require(tile >= 1, "a tile needs at least one row")
  require(dataWidth >= 4, "a sign and three fraction bits is the smallest sensible format")
  require(maxDepth >= 1, "an accumulation needs at least one term")

  /** Q2.`fractionBits`, matching `brokkr.Fixed`: two integer bits, so an exact ±1 fits. */
  val fractionBits: Int = dataWidth - 2

  /** The working format. `peak` is the exponent of the top magnitude bit, so `1` means `[-2, 2)`. */
  private def working = SFix(peak = (dataWidth - fractionBits - 1) exp, resolution = -fractionBits exp)

  /** Two full-width factors, plus headroom for `maxDepth` accumulations and the complex subtract.
    *
    * The same width as the Chisel version's `2 * dataWidth + log2Ceil(maxDepth) + 1`, but arrived at
    * from the arithmetic rather than asserted: a product peaks two integer bits above the factors,
    * the subtract adds one, and `maxDepth` additions add `log2(maxDepth)`.
    */
  private def accumulator =
    SFix(peak = (2 * (dataWidth - fractionBits) + log2Up(maxDepth)) exp, resolution = -2 * fractionBits exp)

  val io = new Bundle {
    /** Drop the accumulators, starting a new tile. */
    val clear = in Bool ()

    /** One column of `A` and one row of `B`. */
    val inValid = in Bool ()
    val aRe = in Vec (working, tile)
    val aIm = in Vec (working, tile)
    val bRe = in Vec (working, tile)
    val bIm = in Vec (working, tile)

    /** The accumulated tile, row-major, narrowed back to the working format. */
    val outRe = out Vec (working, tile * tile)
    val outIm = out Vec (working, tile * tile)
  }

  // No `init`: the accumulators are not reset, exactly as `Reg(Vec(...))` is not in the Chisel
  // version. Clearing is the `clear` port's job, so a reset network here would be area the other
  // one does not pay for.
  val accRe = Vec.fill(tile * tile)(Reg(accumulator))
  val accIm = Vec.fill(tile * tile)(Reg(accumulator))

  for (row <- 0 until tile; col <- 0 until tile) {
    val index = row * tile + col

    // (a + bi)(c + di) = (ac - bd) + (ad + bc)i, once per cell: tile^2 complex multipliers.
    val productRe = io.aRe(row) * io.bRe(col) - io.aIm(row) * io.bIm(col)
    val productIm = io.aRe(row) * io.bIm(col) + io.aIm(row) * io.bRe(col)

    when(io.clear) {
      accRe(index).raw := 0
      accIm(index).raw := 0
    } elsewhen (io.inValid) {
      accRe(index) := (accRe(index) + productRe).truncated
      accIm(index) := (accIm(index) + productIm).truncated
    }

    io.outRe(index) := accRe(index).truncated
    io.outIm(index) := accIm(index).truncated
  }
}

/** `./mill spinal.runMain brokkr.spinal.Generate` — same shape as `brokkr.Generate`. */
object Generate extends App {
  SpinalConfig(
    targetDirectory = "build/SpinalComplexGemm",
    // Chisel's reset is synchronous; SpinalHDL defaults to asynchronous. Nothing here is reset, so
    // this changes no gate — it is set so that if something ever is, the two stay comparable.
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC)
  ).generateVerilog(new ComplexGemm(tile = 4, dataWidth = 24, maxDepth = 1024).setDefinitionName("SpinalComplexGemm"))
}
