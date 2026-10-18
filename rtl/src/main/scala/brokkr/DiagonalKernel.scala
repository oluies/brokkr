package brokkr

import chisel3._
import chisel3.util._

/** The diagonal datapath: scale every amplitude in place by the phase its supported bits select.
  *
  * This is the cheapest of the three paths and the one a dense-only engine throws away. There is no
  * gather, no scatter and no shuffle network — amplitudes stream past a `2^k` phase table and one
  * complex multiplier, at one amplitude per cycle. A multi-controlled `Z` over the whole register,
  * which a dense pass would ask `4^k` entries for, costs exactly this.
  *
  * One complex multiplier is four real ones: CIRCT emits `ac`, `bd`, `ad` and `bc` as four
  * `dataWidth x dataWidth` products feeding one add and one subtract. Against the dense path's
  * `2^k`-wide multiply-accumulate array that is the whole argument for keeping the forms apart.
  *
  * ==Precision==
  * Amplitudes and coefficients are fixed point in the shared [[Fixed]] format, Q2.`fractionBits`.
  * This is the first real departure from Scotty, whose amplitudes are double precision, and the
  * departure is measured rather than assumed: `DiagonalKernelSpec` checks the RTL against an exact
  * integer model of this arithmetic, which admits no tolerance, and separately checks how far that
  * model drifts from Scotty's doubles.
  *
  * The product of two Q2.f numbers is Q4.2f, so it is shifted right by `fractionBits` and truncated
  * — truncated, not rounded, because that is one gate cheaper and the error is already bounded well
  * below what the format costs. An amplitude is at most 1 and a unitary entry at most 1, so the product fits.
  *
  * @param qubitCount how many qubits the register has, and so how wide an amplitude index is
  * @param maxWidth   the widest block support this kernel serves
  * @param dataWidth  bits per real component, including the sign
  */
class DiagonalKernel(val qubitCount: Int, val maxWidth: Int, val dataWidth: Int) extends Module {
  require(qubitCount >= 1, "a register needs at least one qubit")
  require(maxWidth >= 1 && maxWidth <= qubitCount, "block width has to fit in the register")
  require(dataWidth >= 4, "a sign and three fraction bits is the smallest sensible format")

  val indexWidth: Int = math.max(1, log2Ceil(qubitCount))
  val countWidth: Int = log2Ceil(maxWidth + 1)
  val tableDepth: Int = 1 << maxWidth

  /** Q2.`fractionBits`; see [[Fixed]] for why two integer bits. */
  val fractionBits: Int = Fixed.fractionBits(dataWidth)

  val io = IO(new Bundle {
    /** The block's support, ascending. Entries at or past `blockWidth` are ignored. */
    val qubits = Input(Vec(maxWidth, UInt(indexWidth.W)))
    val blockWidth = Input(UInt(countWidth.W))

    /** Phase table load port. In the full engine this is written by the descriptor queue. */
    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(log2Ceil(tableDepth).W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    /** One amplitude per cycle, with the index it sits at. */
    val inValid = Input(Bool())
    val inIndex = Input(UInt(qubitCount.W))
    val inRe = Input(SInt(dataWidth.W))
    val inIm = Input(SInt(dataWidth.W))

    /** The scaled amplitude, one cycle later. */
    val outValid = Output(Bool())
    val outRe = Output(SInt(dataWidth.W))
    val outIm = Output(SInt(dataWidth.W))
  })

  val phaseRe = Reg(Vec(tableDepth, SInt(dataWidth.W)))
  val phaseIm = Reg(Vec(tableDepth, SInt(dataWidth.W)))

  when(io.writeEnable) {
    phaseRe(io.writeAddr) := io.writeRe
    phaseIm(io.writeAddr) := io.writeIm
  }

  // The supported bits of the amplitude index, packed down into a table address. This is the whole
  // of the addressing for a diagonal block: no base address, no strides, no permutation.
  val local = (0 until maxWidth).foldLeft(0.U(maxWidth.W)) { (packed, stage) =>
    val bit = (io.inIndex >> io.qubits(stage))(0)

    packed | Mux(stage.U < io.blockWidth, (bit.asUInt << stage).asUInt, 0.U)
  }

  val phase = local(maxWidth - 1, 0)
  val selectedRe = phaseRe(phase)
  val selectedIm = phaseIm(phase)

  // (a + bi)(c + di) = (ac - bd) + (ad + bc)i
  val productRe = selectedRe * io.inRe - selectedIm * io.inIm
  val productIm = selectedRe * io.inIm + selectedIm * io.inRe

  /** Q4.2f back down to Q2.f, truncating. */
  private def narrow(wide: SInt): SInt = (wide >> fractionBits)(dataWidth - 1, 0).asSInt

  io.outRe := RegNext(narrow(productRe), 0.S)
  io.outIm := RegNext(narrow(productIm), 0.S)
  io.outValid := RegNext(io.inValid, false.B)
}
