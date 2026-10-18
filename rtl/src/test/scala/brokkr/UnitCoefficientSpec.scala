package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Regression: the fixed-point format must represent a coefficient of exactly ±1.
  *
  * Q1.23 looked like the natural format, because amplitudes live in the unit disc. It is not,
  * because operator coefficients do not: they sit *on* the unit circle all the time. Every `X`,
  * `CNOT`, `SWAP` and Toffoli is a permutation matrix whose every non-zero entry is exactly 1, and
  * `CZ` and `Z` contribute exact −1s. Q1.f stops at `1 − 2^-f`, so `1.0` wraps to `−1.0` and the
  * sign of the most common coefficient there is silently flips.
  *
  * It showed up as a sign error on the permute path, where the permutation entries are all unit.
  * The diagonal and dense kernels had the same latent bug and had merely been handed vectors —
  * `RZ`, `CPHASE`, `H`, `RX` — whose entries are strictly inside the disc in both components.
  *
  * Everything is Q2.22 now, which costs a bit of precision and buys correctness. These tests fail
  * if anyone narrows it back, and they are deliberately about the *behaviour*, not just the
  * arithmetic: a coefficient of one has to be the identity, in every datapath.
  */
class UnitCoefficientSpec extends AnyFlatSpec with ChiselSim {
  val DataWidth = 24
  val Width = 3
  val Dimension = 1 << Width

  // Read from the datapaths' own definition rather than restated here, so the two cannot drift.
  val fractionBits: Int = Fixed.fractionBits(DataWidth)
  val one: BigInt = Fixed.one(DataWidth)
  val largestSigned: BigInt = Fixed.largestSigned(DataWidth)

  /** Distinctive values, well inside range, that a sign flip or a mis-route would make obvious. */
  def sample(lane: Int): BigInt = BigInt(lane * 37 + 11) << 12

  "the coefficient format" should "represent +1 exactly" in {
    assert(
      one <= largestSigned,
      s"a coefficient of 1 is $one, which does not fit in $DataWidth signed bits (max $largestSigned)"
    )
  }

  it should "represent -1 exactly" in {
    assert(-one >= -(BigInt(1) << (DataWidth - 1)))
  }

  it should "reject Q1.f, which is the bug this guards" in {
    // The format that was here before. Documented as a test so the reason survives the commit log.
    val q1FractionBits = DataWidth - 1
    val q1One = BigInt(1) << q1FractionBits

    assert(
      q1One > largestSigned,
      s"Q1.$q1FractionBits would hold 1.0 after all, so this regression no longer guards anything"
    )
  }

  "DiagonalKernel" should "leave an amplitude alone when its phase is exactly 1" in {
    simulate(new DiagonalKernel(qubitCount = 6, maxWidth = Width, dataWidth = DataWidth)) { dut =>
      (0 until Dimension).foreach { address =>
        dut.io.writeEnable.poke(true.B)
        dut.io.writeAddr.poke(address.U)
        dut.io.writeRe.poke(one.S)
        dut.io.writeIm.poke(0.S)
        dut.clock.step()
      }

      dut.io.writeEnable.poke(false.B)
      (0 until Width).foreach(i => dut.io.qubits(i).poke(i.U))
      dut.io.blockWidth.poke(Width.U)

      (0 until Dimension).foreach { index =>
        dut.io.inValid.poke(true.B)
        dut.io.inIndex.poke(index.U)
        dut.io.inRe.poke(sample(index).S)
        dut.io.inIm.poke((-sample(index)).S)
        dut.clock.step()

        withClue(s"index $index, real part (a sign flip here is the regression): ") {
          dut.io.outRe.expect(sample(index).S)
        }
        withClue(s"index $index, imaginary part: ") {
          dut.io.outIm.expect((-sample(index)).S)
        }
      }
    }
  }

  "MonomialKernel" should "be the identity when its phases are 1 and its permutation is trivial" in {
    // Control bits all zero are the identity routing: every switch straight through.
    simulate(new MonomialKernel(Width, DataWidth)) { dut =>
      (0 until Dimension).foreach { lane =>
        dut.io.writeEnable.poke(true.B)
        dut.io.writeAddr.poke(lane.U)
        dut.io.writeRe.poke(one.S)
        dut.io.writeIm.poke(0.S)
        dut.clock.step()
      }

      dut.io.writeEnable.poke(false.B)
      dut.io.control.poke(0.U)

      (0 until Dimension).foreach { lane =>
        dut.io.inRe(lane).poke(sample(lane).S)
        dut.io.inIm(lane).poke((-sample(lane)).S)
      }

      dut.io.inValid.poke(true.B)
      dut.clock.step()

      (0 until Dimension).foreach { lane =>
        withClue(s"lane $lane, real part (a sign flip here is the regression): ") {
          dut.io.outRe(lane).expect(sample(lane).S)
        }
        withClue(s"lane $lane, imaginary part: ") {
          dut.io.outIm(lane).expect((-sample(lane)).S)
        }
      }
    }
  }

  it should "negate exactly when a phase is -1" in {
    simulate(new MonomialKernel(Width, DataWidth)) { dut =>
      (0 until Dimension).foreach { lane =>
        dut.io.writeEnable.poke(true.B)
        dut.io.writeAddr.poke(lane.U)
        dut.io.writeRe.poke((-one).S)
        dut.io.writeIm.poke(0.S)
        dut.clock.step()
      }

      dut.io.writeEnable.poke(false.B)
      dut.io.control.poke(0.U)

      (0 until Dimension).foreach { lane =>
        dut.io.inRe(lane).poke(sample(lane).S)
        dut.io.inIm(lane).poke(0.S)
      }

      dut.io.inValid.poke(true.B)
      dut.clock.step()

      (0 until Dimension).foreach { lane =>
        withClue(s"lane $lane: ")(dut.io.outRe(lane).expect((-sample(lane)).S))
      }
    }
  }

  "DenseKernel" should "be the identity when its operator is the identity matrix" in {
    simulate(new DenseKernel(Width, DataWidth)) { dut =>
      for (row <- 0 until Dimension; col <- 0 until Dimension) {
        dut.io.writeEnable.poke(true.B)
        dut.io.writeAddr.poke((row * Dimension + col).U)
        dut.io.writeRe.poke(if (row == col) one.S else 0.S)
        dut.io.writeIm.poke(0.S)
        dut.clock.step()
      }

      dut.io.writeEnable.poke(false.B)

      (0 until Dimension).foreach { col =>
        dut.io.inRe(col).poke(sample(col).S)
        dut.io.inIm(col).poke((-sample(col)).S)
      }

      (0 until Dimension).foreach { row =>
        dut.io.inValid.poke(true.B)
        dut.io.row.poke(row.U)
        dut.clock.step()

        withClue(s"row $row, real part (a sign flip here is the regression): ") {
          dut.io.outRe.expect(sample(row).S)
        }
        withClue(s"row $row, imaginary part: ") {
          dut.io.outIm.expect((-sample(row)).S)
        }
      }
    }
  }
}
