package brokkr

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Checks `DiagonalKernel` twice over, because it is the first block that is not bit-exact.
  *
  * `AddressGen` computes an integer, so it either matches Scotty or it does not. This one multiplies
  * amplitudes, and the moment Scotty's doubles become Q1.23 fixed point the two stop agreeing
  * exactly. Conflating the two questions would hide both, so they are asked separately:
  *
  *   1. does the RTL implement the fixed-point arithmetic exactly? Checked against an integer model
  *      in BigInt, which admits no tolerance at all;
  *   2. how far does that arithmetic drift from the golden model? Measured against Scotty's doubles
  *      and reported, so the cost of the format is a number rather than an assumption.
  *
  * The second is what the design assessment lists as the open precision risk, and this is the first
  * evidence about it.
  */
class DiagonalKernelSpec extends AnyFlatSpec with ChiselScalatestTester {
  val QubitCount = 4
  val MaxWidth = 2
  val DataWidth = 24
  val FractionBits = DataWidth - 1
  val Scale = BigDecimal(BigInt(1) << FractionBits)

  case class Sample(index: Int, inRe: Double, inIm: Double, outRe: Double, outIm: Double)

  lazy val (qubits, blockWidth, table, samples) = {
    val stream = getClass.getResourceAsStream("/diagonal-vectors.txt")
    require(stream != null, "diagonal-vectors.txt is missing; regenerate it with vectors.runMain")

    val lines = Source.fromInputStream(stream).getLines().filterNot(_.startsWith("#")).toSeq
    val fields = lines.map(_.trim.split("\\s+").toSeq)

    val qs = fields.find(_.head == "qubits").get(1).split(",").map(_.toInt).toSeq
    val w = fields.find(_.head == "width").get(1).toInt
    val flat = fields.find(_.head == "table").get(1).split(",").map(_.toDouble)
    val entries = flat.grouped(2).map(pair => (pair(0), pair(1))).toSeq
    val rows = fields.filter(_.length == 5).map { f =>
      Sample(f(0).toInt, f(1).toDouble, f(2).toDouble, f(3).toDouble, f(4).toDouble)
    }

    (qs, w, entries, rows)
  }

  /** A double as the kernel stores it. */
  def fixed(value: Double): BigInt = BigDecimal(value).*(Scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  def real(value: BigInt): Double = (BigDecimal(value) / Scale).toDouble

  /** The kernel's arithmetic, exactly: multiply, subtract, arithmetic-shift, wrap to dataWidth. */
  def model(phase: (BigInt, BigInt), amplitude: (BigInt, BigInt)): (BigInt, BigInt) = {
    val (pRe, pIm) = phase
    val (aRe, aIm) = amplitude

    (wrap((pRe * aRe - pIm * aIm) >> FractionBits), wrap((pRe * aIm + pIm * aRe) >> FractionBits))
  }

  def wrap(value: BigInt): BigInt = {
    val modulus = BigInt(1) << DataWidth
    val masked = ((value % modulus) + modulus) % modulus

    if (masked >= (BigInt(1) << (DataWidth - 1))) masked - modulus else masked
  }

  def drive(dut: DiagonalKernel): Unit = {
    qubits.zipWithIndex.foreach { case (q, i) => dut.io.qubits(i).poke(q.U) }
    (qubits.length until MaxWidth).foreach(i => dut.io.qubits(i).poke(0.U))
    dut.io.blockWidth.poke(blockWidth.U)

    table.zipWithIndex.foreach { case ((re, im), addr) =>
      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(addr.U)
      dut.io.writeRe.poke(fixed(re).S)
      dut.io.writeIm.poke(fixed(im).S)
      dut.clock.step()
    }

    dut.io.writeEnable.poke(false.B)
  }

  "DiagonalKernel" should "load its golden vectors" in {
    assert(samples.length == 16, s"expected 16 samples, found ${samples.length}")
    assert(table.length == 4, s"expected a 4-entry phase table, found ${table.length}")
    assert(qubits == Seq(0, 2))
  }

  it should "implement its fixed-point arithmetic exactly" in {
    test(new DiagonalKernel(QubitCount, MaxWidth, DataWidth)) { dut =>
      drive(dut)

      samples.foreach { s =>
        val address = qubits.zipWithIndex.map { case (q, j) => ((s.index >> q) & 1) << j }.sum
        val expected = model(
          (fixed(table(address)._1), fixed(table(address)._2)),
          (fixed(s.inRe), fixed(s.inIm))
        )

        dut.io.inValid.poke(true.B)
        dut.io.inIndex.poke(s.index.U)
        dut.io.inRe.poke(fixed(s.inRe).S)
        dut.io.inIm.poke(fixed(s.inIm).S)
        dut.clock.step()

        withClue(s"amplitude ${s.index}, real part: ") {
          dut.io.outRe.expect(expected._1.S)
        }
        withClue(s"amplitude ${s.index}, imaginary part: ") {
          dut.io.outIm.expect(expected._2.S)
        }
        assert(dut.io.outValid.peek().litToBoolean)
      }
    }
  }

  it should "stay close to Scotty's double-precision result" in {
    var worst = 0.0

    test(new DiagonalKernel(QubitCount, MaxWidth, DataWidth)) { dut =>
      drive(dut)

      samples.foreach { s =>
        dut.io.inValid.poke(true.B)
        dut.io.inIndex.poke(s.index.U)
        dut.io.inRe.poke(fixed(s.inRe).S)
        dut.io.inIm.poke(fixed(s.inIm).S)
        dut.clock.step()

        val gotRe = real(dut.io.outRe.peek().litValue)
        val gotIm = real(dut.io.outIm.peek().litValue)

        worst = worst.max(math.abs(gotRe - s.outRe)).max(math.abs(gotIm - s.outIm))
      }
    }

    // One quantisation step is 2^-23, about 1.2e-7, and a complex multiply costs a few of them.
    info(f"worst deviation from Scotty over ${samples.length} amplitudes: $worst%.3e")
    assert(worst < 1e-6, f"Q1.$FractionBits drifted by $worst%.3e, more than the format should cost")
  }

  it should "hold its output until the next valid amplitude" in {
    test(new DiagonalKernel(QubitCount, MaxWidth, DataWidth)) { dut =>
      drive(dut)

      dut.io.inValid.poke(false.B)
      dut.clock.step()
      assert(!dut.io.outValid.peek().litToBoolean, "a cycle with no input should produce no output")
    }
  }
}
