package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Runs every channel and state in the vector set through the hardware.
  *
  * The engine was first checked on one channel and one state, which was thin for the thing the
  * assessment rests on — the state-vector path it is compared against is checked against randomised
  * circuits over the whole gate set. This closes that: three channels with different Kraus
  * structures and counts, three random states each.
  *
  * Three properties are asserted on every case, and the last two matter most. A channel is trace
  * preserving and maps Hermitian states to Hermitian states, so those hold regardless of what the
  * reference says — a transposed index or a conjugation dropped from the second product breaks them
  * even if the element-wise comparison somehow passes. They are the closest thing here to a check
  * that does not depend on the golden model.
  */
class ChannelEngineSpec extends AnyFlatSpec with ChiselSim {
  val DataWidth = 24
  val FractionBits = Fixed.fractionBits(DataWidth)
  val Scale = BigDecimal(BigInt(1) << FractionBits)

  val Tile = 4

  case class Case(
      index: Int,
      name: String,
      krausCount: Int,
      dimension: Int,
      rho: Seq[(Double, Double)],
      kraus: Seq[Seq[(Double, Double)]],
      expected: Seq[(Double, Double)]
  ) {
    def tiled: Boolean = dimension > Tile
  }

  lazy val cases: Seq[Case] = {
    val stream = getClass.getResourceAsStream("/channel-vectors.txt")
    require(stream != null, "channel-vectors.txt is missing; regenerate it with vectors.runMain")

    val fields = Source.fromInputStream(stream).getLines()
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+").toSeq)
      .toSeq

    def matrix(label: String, caseIndex: Int, operator: Int, d: Int): Seq[(Double, Double)] =
      (0 until d).flatMap { row =>
        val entries = fields
          .find(f =>
            f.head == label && f(1).toInt == caseIndex && f(2).toInt == operator && f(3).toInt == row
          )
          .getOrElse(sys.error(s"missing $label for case $caseIndex operator $operator row $row"))(4)
          .split(",")
          .map(_.toDouble)

        (0 until d).map(col => (entries(2 * col), entries(2 * col + 1)))
      }

    fields.filter(_.head == "case").map { f =>
      val index = f(1).toInt
      val count = f(3).toInt
      val d = f(4).toInt

      Case(
        index,
        f(2),
        count,
        d,
        matrix("rho", index, 0, d),
        (0 until count).map(matrix("k", index, _, d)),
        matrix("result", index, 0, d)
      )
    }
  }

  def fixed(value: Double): BigInt =
    BigDecimal(value).*(Scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  def real(value: BigInt): Double = (BigDecimal(value) / Scale).toDouble

  /** Every case run once, since a simulation per property would be three times the Verilator
    * builds for the same information.
    */
  lazy val runs: Map[Int, (Seq[(Double, Double)], Int)] = cases.map { c =>
    var out = Seq.empty[(Double, Double)]
    var taken = 0

    simulate(new ChannelEngine(c.dimension, c.krausCount, DataWidth, Tile)) { dut =>
      c.rho.zipWithIndex.foreach { case ((re, im), address) =>
        dut.io.writeEnable.poke(true.B)
        dut.io.writeKraus.poke(false.B)
        dut.io.writeAddr.poke(address.U)
        dut.io.writeRe.poke(fixed(re).S)
        dut.io.writeIm.poke(fixed(im).S)
        dut.clock.step()
      }

      c.kraus.zipWithIndex.foreach { case (operator, index) =>
        operator.zipWithIndex.foreach { case ((re, im), cell) =>
          dut.io.writeEnable.poke(true.B)
          dut.io.writeKraus.poke(true.B)
          dut.io.writeAddr.poke((index * c.dimension * c.dimension + cell).U)
          dut.io.writeRe.poke(fixed(re).S)
          dut.io.writeIm.poke(fixed(im).S)
          dut.clock.step()
        }
      }

      dut.io.writeEnable.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var cycles = 0

      while (!dut.io.done.peek().litToBoolean && cycles < 20000) {
        dut.clock.step()
        cycles += 1
      }

      assert(dut.io.done.peek().litToBoolean, s"case ${c.index} did not finish in $cycles cycles")

      taken = cycles
      out = (0 until c.dimension * c.dimension).map { cell =>
        (real(dut.io.outRe(cell).peek().litValue), real(dut.io.outIm(cell).peek().litValue))
      }
    }

    c.index -> (out, taken)
  }.toMap

  lazy val results: Map[Int, Seq[(Double, Double)]] = runs.view.mapValues(_._1).toMap

  "the vectors" should "cover several channels and several states each" in {
    assert(cases.length == 18, s"expected eighteen cases, found ${cases.length}")
    assert(cases.exists(_.tiled), "some cases should force the engine to loop over tiles")
    assert(cases.exists(!_.tiled), "some cases should fit in one tile")
    assert(cases.map(_.name).distinct.length == 3)
    assert(cases.map(_.krausCount).distinct.length > 1, "the cases should differ in Kraus count")

    cases.foreach { c =>
      val trace = (0 until c.dimension).map(i => c.rho(i * c.dimension + i)._1).sum

      withClue(s"case ${c.index} (${c.name}) input: ") {
        assert(math.abs(trace - 1.0) < 1e-9)
      }
    }
  }

  "ChannelEngine" should "reproduce Scotty on every channel and state" in {
    var worst = 0.0

    cases.foreach { c =>
      results(c.index).zip(c.expected).zipWithIndex.foreach {
        case (((gotRe, gotIm), (wantRe, wantIm)), cell) =>
          worst = worst.max(math.abs(gotRe - wantRe)).max(math.abs(gotIm - wantIm))

          withClue(s"case ${c.index} (${c.name}, dim ${c.dimension}) cell ${cell / c.dimension},${cell % c.dimension}: ") {
            assert(math.abs(gotRe - wantRe) < 1e-5)
            assert(math.abs(gotIm - wantIm) < 1e-5)
          }
      }
    }

    info(f"worst deviation from Scotty over ${cases.length} cases: $worst%.3e")
  }

  it should "preserve the trace on every case, as a channel must" in {
    var worst = 0.0

    cases.foreach { c =>
      val trace = (0 until c.dimension).map(i => results(c.index)(i * c.dimension + i)._1).sum

      worst = worst.max(math.abs(trace - 1.0))

      withClue(s"case ${c.index} (${c.name}): ") {
        assert(math.abs(trace - 1.0) < 1e-4, f"trace is $trace%.6f")
      }
    }

    info(f"worst trace error over ${cases.length} cases: $worst%.3e")
  }

  it should "produce Hermitian results on every case, as a channel must" in {
    var worst = 0.0

    cases.foreach { c =>
      for (row <- 0 until c.dimension; col <- 0 until c.dimension) {
        val (re, im) = results(c.index)(row * c.dimension + col)
        val (mirrorRe, mirrorIm) = results(c.index)(col * c.dimension + row)

        worst = worst.max(math.abs(re - mirrorRe)).max(math.abs(im + mirrorIm))

        withClue(s"case ${c.index} (${c.name}), ($row,$col) against ($col,$row): ") {
          assert(math.abs(re - mirrorRe) < 1e-5)
          assert(math.abs(im + mirrorIm) < 1e-5)
        }
      }
    }

    info(f"worst Hermitian asymmetry over ${cases.length} cases: $worst%.3e")
  }

  it should "loop over tiles when the state is wider than the array" in {
    // The cost of tiling is the point: a matrix product is (dimension/tile)^2 blocks of
    // `dimension` accumulations each, so cycles grow as dimension^3 / tile^2. At tile 4 the
    // dimension-8 cases do four blocks per product where the dimension-4 cases do one.
    val byDimension = cases
      .groupBy(_.dimension)
      .view
      .mapValues(group => group.map(c => runs(c.index)._2 / c.krausCount).distinct.sorted)
      .toMap

    byDimension.toSeq.sortBy(_._1).foreach { case (dimension, perKraus) =>
      val blocks = (dimension / Tile) * (dimension / Tile)

      info(s"dimension $dimension: $blocks block(s) per product, ${perKraus.mkString("/")} cycles per Kraus operator")
    }

    val small = byDimension(4).head
    val large = byDimension(8).head

    // Eight times the work — twice the depth, four times the blocks, in each of two products.
    assert(large > small * 5, s"dimension 8 took $large cycles per operator against $small at 4")
    assert(cases.filter(_.tiled).forall(c => runs(c.index)._2 > 0))
  }

  it should "handle channels with different Kraus counts" in {
    // Four operators for depolarizing, two for the others. The sequencing is a counter over them,
    // so a case with a different count exercises a different path through the state machine.
    val counts = cases.map(c => c.name -> c.krausCount).distinct.toMap

    assert(counts("depolarizing") == 4)
    assert(counts("amplitudeDamping") == 2)
    assert(counts("dephasing") == 2)
    info(s"Kraus counts exercised: ${counts.values.toSeq.distinct.sorted.mkString(", ")}")
  }
}
