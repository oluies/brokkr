package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Checks `ComplexGemm` the same two ways as the state-vector kernels: exactly against its own
  * fixed-point arithmetic, and approximately against Scotty.
  *
  * The second is the more interesting one here. A tile of depth 16 accumulates sixteen complex
  * products before rounding once, so if the widened accumulator works the error should be no worse
  * than a single multiply's — which is what makes a deep GEMM safe in fixed point at all.
  */
class ComplexGemmSpec extends AnyFlatSpec with ChiselSim {
  val DataWidth = 24
  val FractionBits = Fixed.fractionBits(DataWidth)
  val Scale = BigDecimal(BigInt(1) << FractionBits)

  case class Step(aRe: Seq[Double], aIm: Seq[Double], bRe: Seq[Double], bIm: Seq[Double])

  lazy val (tile, depth, steps, expected) = {
    val stream = getClass.getResourceAsStream("/gemm-vectors.txt")
    require(stream != null, "gemm-vectors.txt is missing; regenerate it with vectors.runMain")

    val lines = Source.fromInputStream(stream).getLines().filterNot(_.startsWith("#")).toSeq
    val fields = lines.map(_.trim.split("\\s+").toSeq)
    val t = fields.find(_.head == "tile").get(1).toInt
    val d = fields.find(_.head == "depth").get(1).toInt

    val parsed = fields.filter(_.head == "step").sortBy(_(1).toInt).map { f =>
      val column = f(2).split(",").map(_.toDouble)
      val row = f(3).split(",").map(_.toDouble)

      Step(
        aRe = (0 until t).map(i => column(2 * i)),
        aIm = (0 until t).map(i => column(2 * i + 1)),
        bRe = (0 until t).map(i => row(2 * i)),
        bIm = (0 until t).map(i => row(2 * i + 1))
      )
    }

    val results = fields.filter(_.head == "result").map { f =>
      (f(1).toInt * t + f(2).toInt) -> (f(3).toDouble, f(4).toDouble)
    }.toMap

    (t, d, parsed, results)
  }

  def fixed(value: Double): BigInt =
    BigDecimal(value).*(Scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  def real(value: BigInt): Double = (BigDecimal(value) / Scale).toDouble

  /** The engine's arithmetic exactly: accumulate at full width, narrow once at the end. */
  def model: Map[Int, (BigInt, BigInt)] = {
    val accumulated = Array.fill(tile * tile)((BigInt(0), BigInt(0)))

    steps.foreach { step =>
      for (row <- 0 until tile; col <- 0 until tile) {
        val index = row * tile + col
        val (aRe, aIm) = (fixed(step.aRe(row)), fixed(step.aIm(row)))
        val (bRe, bIm) = (fixed(step.bRe(col)), fixed(step.bIm(col)))
        val (accRe, accIm) = accumulated(index)

        accumulated(index) = (accRe + aRe * bRe - aIm * bIm, accIm + aRe * bIm + aIm * bRe)
      }
    }

    accumulated.zipWithIndex.map { case ((re, im), index) =>
      index -> ((re >> FractionBits, im >> FractionBits))
    }.toMap
  }

  def run(dut: ComplexGemm): Unit = {
    dut.io.clear.poke(true.B)
    dut.io.inValid.poke(false.B)
    dut.clock.step()
    dut.io.clear.poke(false.B)

    steps.foreach { step =>
      (0 until tile).foreach { i =>
        dut.io.aRe(i).poke(fixed(step.aRe(i)).S)
        dut.io.aIm(i).poke(fixed(step.aIm(i)).S)
        dut.io.bRe(i).poke(fixed(step.bRe(i)).S)
        dut.io.bIm(i).poke(fixed(step.bIm(i)).S)
      }

      dut.io.inValid.poke(true.B)
      dut.clock.step()
    }

    dut.io.inValid.poke(false.B)
  }

  "ComplexGemm" should "load its golden vectors" in {
    assert(tile == 4 && depth == 16)
    assert(steps.length == depth)
    assert(expected.size == tile * tile)
  }

  it should "implement its fixed-point arithmetic exactly" in {
    simulate(new ComplexGemm(4, DataWidth, maxDepth = 16)) { dut =>
      run(dut)

      val reference = model

      (0 until tile * tile).foreach { index =>
        withClue(s"cell ${index / tile},${index % tile} real: ") {
          dut.io.outRe(index).expect(reference(index)._1.S)
        }
        withClue(s"cell ${index / tile},${index % tile} imaginary: ") {
          dut.io.outIm(index).expect(reference(index)._2.S)
        }
      }
    }
  }

  it should "stay close to Scotty's complex matrix product" in {
    var worst = 0.0

    simulate(new ComplexGemm(4, DataWidth, maxDepth = 16)) { dut =>
      run(dut)

      (0 until tile * tile).foreach { index =>
        val (wantRe, wantIm) = expected(index)

        worst = worst
          .max(math.abs(real(dut.io.outRe(index).peek().litValue) - wantRe))
          .max(math.abs(real(dut.io.outIm(index).peek().litValue) - wantIm))
      }
    }

    // Sixteen products accumulate before a single rounding, so this should be no worse than one
    // multiply's error — which is the property that makes a deep GEMM safe in fixed point.
    info(f"worst deviation from Scotty over ${tile * tile} cells, depth $depth: $worst%.3e")
    assert(worst < 1e-6, f"Q2.$FractionBits drifted by $worst%.3e across a depth-$depth product")
  }

  it should "clear between tiles" in {
    simulate(new ComplexGemm(4, DataWidth, maxDepth = 16)) { dut =>
      run(dut)

      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      (0 until tile * tile).foreach { index =>
        withClue(s"cell $index after clear: ")(dut.io.outRe(index).expect(0.S))
      }
    }
  }
}
