package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Runs a whole Kraus channel through the hardware and compares it with Scotty.
  *
  * This is the end-to-end check the project has been building toward: not a kernel against a matrix
  * product, but `rho -> sum K rho K†` against the simulator's own `Channel.apply`, on a real
  * depolarizing channel and a real state.
  *
  * The physical properties are checked as well as the numbers. A channel is trace preserving, so
  * the output's trace must still be one; and the result must still be Hermitian. Those would catch
  * a transposed index or a conjugation dropped in the second product, which an element-wise
  * comparison against a reference might survive if the reference were wrong in the same way.
  */
class ChannelEngineSpec extends AnyFlatSpec with ChiselSim {
  val DataWidth = 24
  val FractionBits = Fixed.fractionBits(DataWidth)
  val Scale = BigDecimal(BigInt(1) << FractionBits)

  lazy val (dimension, krausCount, rho, kraus, expected) = {
    val stream = getClass.getResourceAsStream("/channel-vectors.txt")
    require(stream != null, "channel-vectors.txt is missing; regenerate it with vectors.runMain")

    val fields = Source.fromInputStream(stream).getLines()
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+").toSeq)
      .toSeq

    val d = fields.find(_.head == "dimension").get(1).toInt
    val m = fields.find(_.head == "kraus").get(1).toInt

    def matrix(label: String, index: Int): Seq[(Double, Double)] =
      (0 until d).flatMap { row =>
        val entries = fields
          .find(f => f.head == label && f(1).toInt == index && f(2).toInt == row)
          .get(3)
          .split(",")
          .map(_.toDouble)

        (0 until d).map(col => (entries(2 * col), entries(2 * col + 1)))
      }

    (d, m, matrix("rho", 0), (0 until m).map(matrix("k", _)), matrix("result", 0))
  }

  def fixed(value: Double): BigInt =
    BigDecimal(value).*(Scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  def real(value: BigInt): Double = (BigDecimal(value) / Scale).toDouble

  def load(dut: ChannelEngine): Unit = {
    rho.zipWithIndex.foreach { case ((re, im), address) =>
      dut.io.writeEnable.poke(true.B)
      dut.io.writeKraus.poke(false.B)
      dut.io.writeAddr.poke(address.U)
      dut.io.writeRe.poke(fixed(re).S)
      dut.io.writeIm.poke(fixed(im).S)
      dut.clock.step()
    }

    kraus.zipWithIndex.foreach { case (operator, index) =>
      operator.zipWithIndex.foreach { case ((re, im), cell) =>
        dut.io.writeEnable.poke(true.B)
        dut.io.writeKraus.poke(true.B)
        dut.io.writeAddr.poke((index * dimension * dimension + cell).U)
        dut.io.writeRe.poke(fixed(re).S)
        dut.io.writeIm.poke(fixed(im).S)
        dut.clock.step()
      }
    }

    dut.io.writeEnable.poke(false.B)
  }

  def run(dut: ChannelEngine): Seq[(Double, Double)] = {
    load(dut)

    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)

    var cycles = 0

    while (!dut.io.done.peek().litToBoolean && cycles < 1000) {
      dut.clock.step()
      cycles += 1
    }

    assert(dut.io.done.peek().litToBoolean, s"the engine did not finish in $cycles cycles")
    info(s"channel applied in $cycles cycles")

    (0 until dimension * dimension).map { cell =>
      (real(dut.io.outRe(cell).peek().litValue), real(dut.io.outIm(cell).peek().litValue))
    }
  }

  "the vectors" should "describe a real channel and a real state" in {
    assert(dimension == 4 && krausCount == 4)
    assert(rho.length == 16 && kraus.forall(_.length == 16))

    val trace = (0 until dimension).map(i => rho(i * dimension + i)._1).sum

    assert(math.abs(trace - 1.0) < 1e-9, s"rho should have unit trace, has $trace")
  }

  "ChannelEngine" should "reproduce Scotty's channel" in {
    simulate(new ChannelEngine(4, 4, DataWidth)) { dut =>
      val got = run(dut)
      var worst = 0.0

      got.zip(expected).zipWithIndex.foreach { case (((gotRe, gotIm), (wantRe, wantIm)), cell) =>
        worst = worst.max(math.abs(gotRe - wantRe)).max(math.abs(gotIm - wantIm))

        withClue(s"cell ${cell / dimension},${cell % dimension}: ") {
          assert(math.abs(gotRe - wantRe) < 1e-5)
          assert(math.abs(gotIm - wantIm) < 1e-5)
        }
      }

      info(f"worst deviation from Scotty over ${dimension * dimension} cells: $worst%.3e")
    }
  }

  it should "preserve the trace, as a channel must" in {
    simulate(new ChannelEngine(4, 4, DataWidth)) { dut =>
      val got = run(dut)
      val trace = (0 until dimension).map(i => got(i * dimension + i)._1).sum

      info(f"trace of the result: $trace%.6f")
      assert(math.abs(trace - 1.0) < 1e-4, f"a channel must preserve the trace, got $trace%.6f")
    }
  }

  it should "produce a Hermitian result, as a channel must" in {
    simulate(new ChannelEngine(4, 4, DataWidth)) { dut =>
      val got = run(dut)

      for (row <- 0 until dimension; col <- 0 until dimension) {
        val (re, im) = got(row * dimension + col)
        val (mirrorRe, mirrorIm) = got(col * dimension + row)

        withClue(s"($row,$col) against ($col,$row): ") {
          assert(math.abs(re - mirrorRe) < 1e-5)
          assert(math.abs(im + mirrorIm) < 1e-5)
        }
      }
    }
  }
}
