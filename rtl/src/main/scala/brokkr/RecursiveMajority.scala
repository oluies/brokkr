package brokkr

import chisel3._

/** A cheaper repetition-code decoder: the majority of majorities of three, `levels` deep, over
  * `3^levels` copies.
  *
  * Each level is a three-input majority, `ab | bc | ca`, so the decoder is a tree of them rather
  * than [[MajorityVote]]'s population count and comparison. What it gives up is correction: two
  * flips in each of two groups of three already defeat two levels, so on nine copies it corrects
  * three flips where exact majority corrects four. pctc proves both limits in Lean,
  * `Pctc.recursiveMajority_corrects` and `Pctc.recursiveMajority_fails` against
  * `Pctc.majority_nine_corrects`, and `RecursiveMajoritySpec` checks them here on every input.
  */
class RecursiveMajority(val levels: Int) extends Module {
  require(levels >= 1, "a recursive majority needs at least one level")

  val copies: Int = Seq.fill(levels)(3).product

  val io = IO(new Bundle {
    val received = Input(UInt(copies.W))
    val decoded = Output(Bool())
  })

  private def maj3(a: Bool, b: Bool, c: Bool): Bool = (a && b) || (b && c) || (a && c)

  private def reduce(bits: Seq[Bool]): Bool =
    if (bits.length == 1) bits.head
    else reduce(bits.grouped(3).map { case Seq(a, b, c) => maj3(a, b, c) }.toSeq)

  io.decoded := RegNext(reduce(Seq.tabulate(copies)(io.received(_))), false.B)
}
