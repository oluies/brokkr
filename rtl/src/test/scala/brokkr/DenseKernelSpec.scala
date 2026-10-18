package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Checks `DenseKernel` the same two ways as the diagonal one: exactly against its own fixed-point
  * arithmetic, and approximately against Scotty.
  *
  * The dense path accumulates `2^k` products before rounding where the diagonal path rounds one, so
  * the interesting question is whether the error grows with the width. It does not much: the
  * accumulation is widened, so the only rounding in a row is the final shift.
  */
class DenseKernelSpec extends AnyFlatSpec with ChiselSim {
  val Width = 3
  val Dimension = 1 << Width
  val DataWidth = 24
  val FractionBits = DataWidth - 2
  val Scale = BigDecimal(BigInt(1) << FractionBits)

  case class Sample(index: Int, inRe: Double, inIm: Double, outRe: Double, outIm: Double)

  lazy val (operator, samples) = {
    val stream = getClass.getResourceAsStream("/dense-vectors.txt")
    require(stream != null, "dense-vectors.txt is missing; regenerate it with vectors.runMain")

    val fields = Source.fromInputStream(stream).getLines()
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+").toSeq)
      .toSeq

    val flat = fields.find(_.head == "operator").get(1).split(",").map(_.toDouble)
    val entries = flat.grouped(2).map(pair => (pair(0), pair(1))).toSeq
    val rows = fields.filter(_.length == 5).map { f =>
      Sample(f(0).toInt, f(1).toDouble, f(2).toDouble, f(3).toDouble, f(4).toDouble)
    }

    (entries, rows)
  }

  def fixed(value: Double): BigInt =
    BigDecimal(value).*(Scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  def real(value: BigInt): Double = (BigDecimal(value) / Scale).toDouble

  def wrap(value: BigInt): BigInt = {
    val modulus = BigInt(1) << DataWidth
    val masked = ((value % modulus) + modulus) % modulus

    if (masked >= (BigInt(1) << (DataWidth - 1))) masked - modulus else masked
  }

  /** The kernel's arithmetic for one output row, exactly. */
  def model(row: Int): (BigInt, BigInt) = {
    val (re, im) = (0 until Dimension).foldLeft((BigInt(0), BigInt(0))) { case ((accRe, accIm), col) =>
      val (eRe, eIm) = operator(row * Dimension + col)
      val (pRe, pIm) = (fixed(eRe), fixed(eIm))
      val (aRe, aIm) = (fixed(samples(col).inRe), fixed(samples(col).inIm))

      (accRe + pRe * aRe - pIm * aIm, accIm + pRe * aIm + pIm * aRe)
    }

    (wrap(re >> FractionBits), wrap(im >> FractionBits))
  }

  def load(dut: DenseKernel): Unit = {
    operator.zipWithIndex.foreach { case ((re, im), addr) =>
      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(addr.U)
      dut.io.writeRe.poke(fixed(re).S)
      dut.io.writeIm.poke(fixed(im).S)
      dut.clock.step()
    }

    dut.io.writeEnable.poke(false.B)

    samples.foreach { s =>
      dut.io.inRe(s.index).poke(fixed(s.inRe).S)
      dut.io.inIm(s.index).poke(fixed(s.inIm).S)
    }
  }

  "DenseKernel" should "load its golden vectors" in {
    assert(operator.length == Dimension * Dimension, s"expected a ${Dimension}x$Dimension operator")
    assert(samples.length == Dimension)
  }

  it should "implement its fixed-point arithmetic exactly" in {
    simulate(new DenseKernel(Width, DataWidth)) { dut =>
      load(dut)

      (0 until Dimension).foreach { row =>
        val expected = model(row)

        dut.io.inValid.poke(true.B)
        dut.io.row.poke(row.U)
        dut.clock.step()

        withClue(s"row $row, real part: ")(dut.io.outRe.expect(expected._1.S))
        withClue(s"row $row, imaginary part: ")(dut.io.outIm.expect(expected._2.S))
      }
    }
  }

  it should "stay close to Scotty's double-precision result" in {
    var worst = 0.0

    simulate(new DenseKernel(Width, DataWidth)) { dut =>
      load(dut)

      (0 until Dimension).foreach { row =>
        dut.io.inValid.poke(true.B)
        dut.io.row.poke(row.U)
        dut.clock.step()

        worst = worst
          .max(math.abs(real(dut.io.outRe.peek().litValue) - samples(row).outRe))
          .max(math.abs(real(dut.io.outIm.peek().litValue) - samples(row).outIm))
      }
    }

    info(f"worst deviation from Scotty over $Dimension rows: $worst%.3e")
    assert(worst < 1e-6, f"Q2.$FractionBits drifted by $worst%.3e across a width-$Width row")
  }

}
