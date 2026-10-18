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

  lazy val (tile, depths, steps, expected) = {
    val stream = getClass.getResourceAsStream("/gemm-vectors.txt")
    require(stream != null, "gemm-vectors.txt is missing; regenerate it with vectors.runMain")

    val fields = Source.fromInputStream(stream).getLines()
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+").toSeq)
      .toSeq

    val t = fields.find(_.head == "tile").get(1).toInt
    val ds = fields.find(_.head == "depths").get(1).split(",").map(_.toInt).toSeq

    val parsed = ds.map { depth =>
      depth -> fields
        .filter(f => f.head == "step" && f(1).toInt == depth)
        .sortBy(_(2).toInt)
        .map { f =>
          val column = f(3).split(",").map(_.toDouble)
          val row = f(4).split(",").map(_.toDouble)

          Step(
            aRe = (0 until t).map(i => column(2 * i)),
            aIm = (0 until t).map(i => column(2 * i + 1)),
            bRe = (0 until t).map(i => row(2 * i)),
            bIm = (0 until t).map(i => row(2 * i + 1))
          )
        }
    }.toMap

    val results = ds.map { depth =>
      depth -> fields
        .filter(f => f.head == "result" && f(1).toInt == depth)
        .map(f => (f(2).toInt * t + f(3).toInt) -> (f(4).toDouble, f(5).toDouble))
        .toMap
    }.toMap

    (t, ds, parsed, results)
  }

  def fixed(value: Double): BigInt =
    BigDecimal(value).*(Scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  def real(value: BigInt): Double = (BigDecimal(value) / Scale).toDouble

  /** The engine's arithmetic exactly: accumulate at full width, narrow once at the end. */
  def model(depth: Int): Map[Int, (BigInt, BigInt)] = {
    val accumulated = Array.fill(tile * tile)((BigInt(0), BigInt(0)))

    steps(depth).foreach { step =>
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

  def run(dut: ComplexGemm, depth: Int): Unit = {
    dut.io.clear.poke(true.B)
    dut.io.inValid.poke(false.B)
    dut.clock.step()
    dut.io.clear.poke(false.B)

    steps(depth).foreach { step =>
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
    assert(tile == 4)
    assert(depths == Seq(4, 8, 16, 32))
    depths.foreach(d => assert(steps(d).length == d, s"depth $d should have $d steps"))
    depths.foreach(d => assert(expected(d).size == tile * tile))
  }

  it should "implement its fixed-point arithmetic exactly, at every depth" in {
    depths.foreach { depth =>
      simulate(new ComplexGemm(4, DataWidth, maxDepth = 32)) { dut =>
        run(dut, depth)

        val reference = model(depth)

        (0 until tile * tile).foreach { index =>
          withClue(s"depth $depth, cell ${index / tile},${index % tile} real: ") {
            dut.io.outRe(index).expect(reference(index)._1.S)
          }
          withClue(s"depth $depth, cell ${index / tile},${index % tile} imaginary: ") {
            dut.io.outIm(index).expect(reference(index)._2.S)
          }
        }
      }
    }
  }

  it should "not accumulate error as the depth grows" in {
    // The claim this pins: the accumulator is widened and narrowed only on the way out, so a
    // depth-32 product rounds once rather than thirty-two times. If that were wrong the deviation
    // would climb with depth, and the design could not be run to the depth 2^n a ten-qubit channel
    // needs.
    val measured = depths.map { depth =>
      var worst = 0.0

      simulate(new ComplexGemm(4, DataWidth, maxDepth = 32)) { dut =>
        run(dut, depth)

        (0 until tile * tile).foreach { index =>
          val (wantRe, wantIm) = expected(depth)(index)

          worst = worst
            .max(math.abs(real(dut.io.outRe(index).peek().litValue) - wantRe))
            .max(math.abs(real(dut.io.outIm(index).peek().litValue) - wantIm))
        }
      }

      depth -> worst
    }

    measured.foreach { case (depth, worst) =>
      info(f"depth $depth%3d: worst deviation $worst%.3e")
    }

    measured.foreach { case (depth, worst) =>
      withClue(s"depth $depth: ")(assert(worst < 1e-6))
    }

    // Eight times the depth should not cost anything like eight times the error.
    val shallow = measured.head._2
    val deep = measured.last._2

    assert(
      deep < shallow * 4 + 1e-7,
      f"error grew from $shallow%.3e at depth ${depths.head} to $deep%.3e at depth ${depths.last}"
    )
  }

  it should "clear between tiles" in {
    simulate(new ComplexGemm(4, DataWidth, maxDepth = 32)) { dut =>
      run(dut, depths.head)

      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      (0 until tile * tile).foreach { index =>
        withClue(s"cell $index after clear: ")(dut.io.outRe(index).expect(0.S))
      }
    }
  }
}
