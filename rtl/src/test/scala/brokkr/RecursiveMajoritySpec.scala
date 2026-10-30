package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Checks [[RecursiveMajority]] on all 512 received words of nine copies, against what pctc proves.
  *
  * `Pctc.recursiveMajority_corrects` says every pattern of three flips is corrected and
  * `Pctc.recursiveMajority_fails` that some four are not. This checks both, and pins exactly which
  * four fail: those with two flips in each of two groups, 27 of the 126 patterns of four, which is
  * what makes the decoder worse than [[MajorityVote]] at the same size.
  */
class RecursiveMajoritySpec extends AnyFlatSpec with ChiselSim {
  private val copies = 9

  private def received(b: Boolean, errors: Int): Int =
    if (b) errors ^ ((1 << copies) - 1) else errors

  /** Flips per group of three: positions 0-2, 3-5 and 6-8, the groups the decoder votes over first. */
  private def groupFlips(errors: Int): Seq[Int] =
    Seq(0, 3, 6).map(start => Integer.bitCount((errors >> start) & 7))

  private def decodeAll(b: Boolean): Map[Int, Boolean] = {
    var decoded = Map.empty[Int, Boolean]

    simulate(new RecursiveMajority(levels = 2)) { dut =>
      (0 until (1 << copies)).foreach { errors =>
        dut.io.received.poke(received(b, errors).U)
        dut.clock.step()
        decoded += errors -> dut.io.decoded.peek().litToBoolean
      }
    }

    decoded
  }

  behavior of "RecursiveMajority"

  it should "recover the codeword from up to 3 flips among 9 copies" in {
    for (b <- Seq(false, true); (errors, out) <- decodeAll(b) if Integer.bitCount(errors) <= 3) {
      withClue(s"codeword $b, errors ${errors.toBinaryString}: ")(assert(out == b))
    }
  }

  it should "fail exactly when two of the three groups are each majority-flipped" in {
    for (b <- Seq(false, true); (errors, out) <- decodeAll(b)) {
      val wrong = groupFlips(errors).count(_ >= 2) >= 2

      withClue(s"codeword $b, errors ${errors.toBinaryString}: ")(assert(out == (if (wrong) !b else b)))
    }
  }

  it should "be defeated by 27 of the 126 patterns of 4 flips, which exact majority corrects" in {
    val decoded = decodeAll(false)
    val four = (0 until (1 << copies)).filter(Integer.bitCount(_) == 4)

    assert(four.length == 126)
    assert(four.count(decoded(_)) == 27)
  }
}
