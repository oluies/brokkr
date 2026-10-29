package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Checks [[MajorityVote]] on every input, against the two facts pctc proves in Lean.
  *
  * The golden model is the theorem rather than Scotty: for the codeword `b, ..., b` of length
  * `2t + 1` and an error pattern `e`, `Pctc.majority_corrects` says the decoder returns `b` whenever
  * `e` has at most `t` flips, and `Pctc.majority_fails` that some `t + 1` flips make it return
  * `!b`. At these sizes every one of the `2^copies` received words can be tried, so the checks are
  * exhaustive.
  */
class MajorityVoteSpec extends AnyFlatSpec with ChiselSim {
  private val sizes = Seq(1, 3, 5, 7, 9)

  /** The word read back when codeword `b` suffers the flips in `errors`, as pctc's `received`. */
  private def received(b: Boolean, errors: Int, copies: Int): Int =
    if (b) errors ^ ((1 << copies) - 1) else errors

  private def decode(dut: MajorityVote, word: Int): Boolean = {
    dut.io.received.poke(word.U)
    dut.clock.step()
    dut.io.decoded.peek().litToBoolean
  }

  behavior of "MajorityVote"

  sizes.foreach { copies =>
    val t = copies / 2

    it should s"recover the codeword from up to $t flips among $copies copies" in {
      simulate(new MajorityVote(copies)) { dut =>
        for (b <- Seq(false, true); errors <- 0 until (1 << copies) if Integer.bitCount(errors) <= t) {
          withClue(s"codeword $b, errors ${errors.toBinaryString}: ") {
            assert(decode(dut, received(b, errors, copies)) == b)
          }
        }
      }
    }

    it should s"be defeated by every pattern of more than $t flips among $copies copies" in {
      // Stronger than majority_fails, which needs only one such pattern: with more than half the
      // copies flipped, the majority is the flipped value whichever copies they are.
      simulate(new MajorityVote(copies)) { dut =>
        for (b <- Seq(false, true); errors <- 0 until (1 << copies) if Integer.bitCount(errors) > t) {
          withClue(s"codeword $b, errors ${errors.toBinaryString}: ") {
            assert(decode(dut, received(b, errors, copies)) == !b)
          }
        }
      }
    }
  }

  it should "reject an even number of copies" in {
    assertThrows[IllegalArgumentException](simulate(new MajorityVote(4)) { _ => () })
  }
}
