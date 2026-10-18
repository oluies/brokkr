package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Checks `MonomialKernel`, and with it the Benes network underneath.
  *
  * The permutation in these vectors is classified Arbitrary by
  * `brokkr.vectors.PermutationStudy` — it is not affine over GF(2), because the circuit it comes
  * from contains a Toffoli. An Omega network could not route it. That is the whole reason the
  * shuffle here is a Benes network, so verifying this block is also verifying that design decision.
  */
class MonomialKernelSpec extends AnyFlatSpec with ChiselSim {
  val Width = 3
  val Dimension = 1 << Width
  val DataWidth = 24
  val FractionBits = DataWidth - 2
  val Scale = BigDecimal(BigInt(1) << FractionBits)

  case class Sample(lane: Int, inRe: Double, inIm: Double, outRe: Double, outIm: Double)

  lazy val (permutation, control, phases, samples) = {
    val stream = getClass.getResourceAsStream("/monomial-vectors.txt")
    require(stream != null, "monomial-vectors.txt is missing; regenerate it with vectors.runMain")

    val fields = Source.fromInputStream(stream).getLines()
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+").toSeq)
      .toSeq

    val rows = fields.find(_.head == "permutation").get(1).split(",").map(_.toInt).toSeq
    val bits = BigInt(fields.find(_.head == "control").get(1))
    val flat = fields.find(_.head == "phases").get(1).split(",").map(_.toDouble)
    val entries = flat.grouped(2).map(pair => (pair(0), pair(1))).toSeq
    val rowsOfData = fields.filter(_.length == 5).map { f =>
      Sample(f(0).toInt, f(1).toDouble, f(2).toDouble, f(3).toDouble, f(4).toDouble)
    }

    (rows, bits, entries, rowsOfData)
  }

  def fixed(value: Double): BigInt =
    BigDecimal(value).*(Scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  def real(value: BigInt): Double = (BigDecimal(value) / Scale).toDouble

  def wrap(value: BigInt): BigInt = {
    val modulus = BigInt(1) << DataWidth
    val masked = ((value % modulus) + modulus) % modulus

    if (masked >= (BigInt(1) << (DataWidth - 1))) masked - modulus else masked
  }

  /** The kernel's arithmetic: scale lane by lane, then permute. */
  def model: Seq[(BigInt, BigInt)] = {
    val out = Array.fill(Dimension)((BigInt(0), BigInt(0)))

    (0 until Dimension).foreach { lane =>
      val (pRe, pIm) = (fixed(phases(lane)._1), fixed(phases(lane)._2))
      val (aRe, aIm) = (fixed(samples(lane).inRe), fixed(samples(lane).inIm))

      out(permutation(lane)) = (
        wrap((pRe * aRe - pIm * aIm) >> FractionBits),
        wrap((pRe * aIm + pIm * aRe) >> FractionBits)
      )
    }

    out.toSeq
  }

  def load(dut: MonomialKernel): Unit = {
    phases.zipWithIndex.foreach { case ((re, im), lane) =>
      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(lane.U)
      dut.io.writeRe.poke(fixed(re).S)
      dut.io.writeIm.poke(fixed(im).S)
      dut.clock.step()
    }

    dut.io.writeEnable.poke(false.B)
    dut.io.control.poke(control.U)

    samples.foreach { s =>
      dut.io.inRe(s.lane).poke(fixed(s.inRe).S)
      dut.io.inIm(s.lane).poke(fixed(s.inIm).S)
    }
  }

  "MonomialKernel" should "load a permutation an Omega network could not route" in {
    assert(permutation.sorted == (0 until Dimension), "the vectors should hold a permutation")
    assert(permutation != (0 until Dimension), "a trivial permutation would prove nothing")
    assert(samples.length == Dimension)
  }

  it should "implement its fixed-point arithmetic exactly" in {
    simulate(new MonomialKernel(Width, DataWidth)) { dut =>
      load(dut)
      dut.io.inValid.poke(true.B)
      dut.clock.step()

      val expected = model

      (0 until Dimension).foreach { lane =>
        withClue(s"lane $lane, real part: ")(dut.io.outRe(lane).expect(expected(lane)._1.S))
        withClue(s"lane $lane, imaginary part: ")(dut.io.outIm(lane).expect(expected(lane)._2.S))
      }

      assert(dut.io.outValid.peek().litToBoolean)
    }
  }

  it should "stay close to Scotty's double-precision result" in {
    var worst = 0.0

    simulate(new MonomialKernel(Width, DataWidth)) { dut =>
      load(dut)
      dut.io.inValid.poke(true.B)
      dut.clock.step()

      (0 until Dimension).foreach { lane =>
        worst = worst
          .max(math.abs(real(dut.io.outRe(lane).peek().litValue) - samples(lane).outRe))
          .max(math.abs(real(dut.io.outIm(lane).peek().litValue) - samples(lane).outIm))
      }
    }

    info(f"worst deviation from Scotty over $Dimension lanes: $worst%.3e")
    assert(worst < 1e-6, f"Q2.$FractionBits drifted by $worst%.3e")
  }

  it should "route the permutation, not merely scale" in {
    // Phases of one half everywhere, so any lane that ends up in the wrong place is visible as a
    // value rather than hidden inside the arithmetic.
    simulate(new MonomialKernel(Width, DataWidth)) { dut =>
      val half = BigInt(1) << (FractionBits - 1)
      val tag = (lane: Int) => BigInt(lane + 1) << 14

      (0 until Dimension).foreach { lane =>
        dut.io.writeEnable.poke(true.B)
        dut.io.writeAddr.poke(lane.U)
        dut.io.writeRe.poke(half.S)
        dut.io.writeIm.poke(0.S)
        dut.clock.step()
      }

      dut.io.writeEnable.poke(false.B)
      dut.io.control.poke(control.U)

      (0 until Dimension).foreach { lane =>
        dut.io.inRe(lane).poke(tag(lane).S)
        dut.io.inIm(lane).poke(0.S)
      }

      dut.io.inValid.poke(true.B)
      dut.clock.step()

      (0 until Dimension).foreach { lane =>
        withClue(s"input lane $lane should land on output ${permutation(lane)}: ") {
          dut.io.outRe(permutation(lane)).expect(((half * tag(lane)) >> FractionBits).S)
        }
      }
    }
  }
}
