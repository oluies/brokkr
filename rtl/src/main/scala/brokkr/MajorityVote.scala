package brokkr

import chisel3._
import chisel3.util.PopCount

/** The decoder of a repetition code: reads `copies` measured bits and outputs the majority.
  *
  * Not part of the engine. It is the hardware half of pctc's repetition-code side note, where the
  * phase-flip code stores a cat qubit's logical value in `copies` qubits, measures each in the
  * `|+>`, `|->` basis and takes the majority. pctc's `Pctc.majority_corrects` proves that this
  * recovers the codeword from any `copies / 2` flips, and `Pctc.majority_fails` that some
  * `copies / 2 + 1` flips defeat it; `MajorityVoteSpec` checks both against this module, on every
  * input.
  *
  * A population count and a comparison, with the decoded bit registered like the other modules'
  * outputs, so `scripts/area.sh` reports a decoder rather than a bare adder tree.
  */
class MajorityVote(val copies: Int) extends Module {
  require(copies >= 1 && copies % 2 == 1, "a repetition code needs an odd number of copies")

  val io = IO(new Bundle {
    val received = Input(UInt(copies.W))
    val decoded = Output(Bool())
  })

  io.decoded := RegNext(PopCount(io.received) > (copies / 2).U, false.B)
}
