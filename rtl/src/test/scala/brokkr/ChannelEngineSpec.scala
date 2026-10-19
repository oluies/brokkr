package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Runs every channel and state in the vector set through the hardware.
  *
  * The engine was first checked on one channel and one state, which was thin for the thing the
  * assessment rests on — the state-vector path it is compared against is checked against randomised
  * circuits over the whole gate set. This closes that: four channels with different Kraus
  * structures, counts and shapes, with several random states each.
  *
  * Three properties are asserted on every case, and the last two matter most. A channel is trace
  * preserving and maps Hermitian states to Hermitian states, so those hold regardless of what the
  * reference says — a transposed index or a conjugation dropped from the second product breaks them
  * even if the element-wise comparison somehow passes. They are the closest thing here to a check
  * that does not depend on the golden model.
  *
  * The fourth channel is '''erasure''', which is rectangular: `2 → 3` on a qubit, `8 → 12` lifted
  * over three. It was a stated gap for as long as the engine assumed one square dimension, and it is
  * the case that distinguishes the two dimensions actually being separate from them merely being
  * spelled separately.
  */
class ChannelEngineSpec extends AnyFlatSpec with ChiselSim {
  val DataWidth = 24
  val FractionBits = Fixed.fractionBits(DataWidth)
  val Scale = BigDecimal(BigInt(1) << FractionBits)

  case class Case(
      index: Int,
      name: String,
      krausCount: Int,
      inputDim: Int,
      outputDim: Int,
      tile: Int,
      trueInputDim: Int,
      trueOutputDim: Int,
      rho: Seq[(Double, Double)],
      kraus: Seq[Seq[(Double, Double)]],
      expected: Seq[(Double, Double)]
  ) {
    def square: Boolean = inputDim == outputDim

    def padded: Boolean = inputDim != trueInputDim || outputDim != trueOutputDim

    def shape: String = s"$inputDim->$outputDim at tile $tile"

    /** Every block of both products, each an `inputDim`-deep accumulation plus its write.
      *
      * `T = K · rho` is `outputDim × inputDim` and `R = T · K†` is `outputDim × outputDim`, so the
      * two products differ in their block-column count. A pass is `inputDim + 1` cycles because the
      * synchronous read runs a cycle ahead, and a write is `tile` cycles because `tile`
      * single-ported banks cannot absorb `tile²` values at once.
      *
      * The second product's write is one cycle longer again. It goes into the accumulator, which is
      * read-modify-write now that it lives in memory, so the write of a block row runs a cycle
      * behind the read of it.
      */
    def cyclesPerOperator: Int = {
      val inTiles = inputDim / tile
      val outTiles = outputDim / tile

      outTiles * inTiles * (inputDim + 1 + tile) +
        outTiles * outTiles * (inputDim + 1 + tile + 1)
    }
  }

  lazy val cases: Seq[Case] = {
    val stream = getClass.getResourceAsStream("/channel-vectors.txt")
    require(stream != null, "channel-vectors.txt is missing; regenerate it with vectors.runMain")

    val fields = Source.fromInputStream(stream).getLines()
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+").toSeq)
      .toSeq

    def matrix(label: String, caseIndex: Int, operator: Int, rows: Int, cols: Int): Seq[(Double, Double)] =
      (0 until rows).flatMap { row =>
        val entries = fields
          .find(f =>
            f.head == label && f(1).toInt == caseIndex && f(2).toInt == operator && f(3).toInt == row
          )
          .getOrElse(sys.error(s"missing $label for case $caseIndex operator $operator row $row"))(4)
          .split(",")
          .map(_.toDouble)

        (0 until cols).map(col => (entries(2 * col), entries(2 * col + 1)))
      }

    fields.filter(_.head == "case").map { f =>
      val index = f(1).toInt
      val count = f(3).toInt
      val inputDim = f(4).toInt
      val outputDim = f(5).toInt

      Case(
        index,
        f(2),
        count,
        inputDim,
        outputDim,
        f(6).toInt,
        f(7).toInt,
        f(8).toInt,
        matrix("rho", index, 0, inputDim, inputDim),
        (0 until count).map(matrix("k", index, _, outputDim, inputDim)),
        matrix("result", index, 0, outputDim, outputDim)
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

    simulate(new ChannelEngine(c.inputDim, c.outputDim, c.krausCount, DataWidth, c.tile)) { dut =>
      def load(kraus: Boolean, copy: Int, cols: Int, entries: Seq[(Double, Double)]): Unit =
        entries.zipWithIndex.foreach { case ((re, im), cell) =>
          dut.io.writeEnable.poke(true.B)
          dut.io.writeKraus.poke(kraus.B)
          dut.io.writeCopy.poke(copy.U)
          dut.io.writeRow.poke((cell / cols).U)
          dut.io.writeCol.poke((cell % cols).U)
          dut.io.writeRe.poke(fixed(re).S)
          dut.io.writeIm.poke(fixed(im).S)
          dut.clock.step()
        }

      load(kraus = false, 0, c.inputDim, c.rho)
      c.kraus.zipWithIndex.foreach { case (operator, index) =>
        load(kraus = true, index, c.inputDim, operator)
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

      // The result is in memory, so it comes out a row slice at a time through the same read port
      // the datapath uses, one cycle after the address. A wide combinational output port would
      // defeat the point of having moved it off registers.
      out = (0 until c.outputDim).flatMap { row =>
        (0 until c.outputDim / c.tile).flatMap { tileCol =>
          dut.io.resultRow.poke(row.U)
          dut.io.resultTileCol.poke(tileCol.U)
          dut.clock.step()

          (0 until c.tile).map { lane =>
            (real(dut.io.resultRe(lane).peek().litValue), real(dut.io.resultIm(lane).peek().litValue))
          }
        }
      }
    }

    c.index -> (out, taken)
  }.toMap

  lazy val results: Map[Int, Seq[(Double, Double)]] = runs.view.mapValues(_._1).toMap

  "the vectors" should "cover several channels and several states each" in {
    assert(cases.length == 24, s"expected twenty-four cases, found ${cases.length}")
    assert(cases.map(_.name).distinct.length == 4)
    assert(cases.exists(c => c.outputDim > c.tile), "some cases should force the engine to loop over tiles")
    assert(cases.exists(c => c.outputDim == c.tile), "some cases should fit in one tile")
    assert(cases.map(_.krausCount).distinct.length > 1, "the cases should differ in Kraus count")
    assert(cases.exists(!_.square), "some cases should be rectangular")
    assert(cases.exists(c => !c.square && !c.padded), "a rectangular case should need no padding")

    cases.foreach { c =>
      val trace = (0 until c.inputDim).map(i => c.rho(i * c.inputDim + i)._1).sum

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

          withClue(s"case ${c.index} (${c.name}, ${c.shape}) cell ${cell / c.outputDim},${cell % c.outputDim}: ") {
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
      val trace = (0 until c.outputDim).map(i => results(c.index)(i * c.outputDim + i)._1).sum

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
      for (row <- 0 until c.outputDim; col <- 0 until c.outputDim) {
        val (re, im) = results(c.index)(row * c.outputDim + col)
        val (mirrorRe, mirrorIm) = results(c.index)(col * c.outputDim + row)

        worst = worst.max(math.abs(re - mirrorRe)).max(math.abs(im + mirrorIm))

        withClue(s"case ${c.index} (${c.name}), ($row,$col) against ($col,$row): ") {
          assert(math.abs(re - mirrorRe) < 1e-5)
          assert(math.abs(im + mirrorIm) < 1e-5)
        }
      }
    }

    info(f"worst Hermitian asymmetry over ${cases.length} cases: $worst%.3e")
  }

  it should "take exactly the cycles the block schedule predicts" in {
    // The cost of tiling is the point, and so is pinning it: a product is (rows/tile)x(cols/tile)
    // blocks of `inputDim` accumulations each, so cycles grow as dimension^3 / tile^2. Asserting
    // the count rather than reporting it is what caught the clear-too-early bug — the sequencing
    // was provably right, which left only the datapath.
    cases.groupBy(c => (c.inputDim, c.outputDim, c.tile)).toSeq.sortBy(_._1).foreach {
      case ((inputDim, outputDim, tile), group) =>
        val c = group.head
        val predicted = c.cyclesPerOperator

        info(s"$inputDim->$outputDim at tile $tile: $predicted cycles per operator, " +
          s"${group.map(g => runs(g.index)._2).distinct.sorted.mkString("/")} total over " +
          s"${group.map(_.krausCount).distinct.mkString("/")} operator(s)")

        group.foreach { g =>
          withClue(s"case ${g.index} (${g.name}, ${g.shape}): ") {
            assert(runs(g.index)._2 == g.krausCount * predicted)
          }
        }
    }
  }

  it should "handle channels with different Kraus counts" in {
    // Four operators for depolarizing, three for erasure, two for the others. The sequencing is a
    // counter over them, so a case with a different count exercises a different path.
    val counts = cases.map(c => c.name -> c.krausCount).distinct.toMap

    assert(counts("depolarizing") == 4)
    assert(counts("amplitudeDamping") == 2)
    assert(counts("dephasing") == 2)
    assert(counts("erasure") == 3)
    info(s"Kraus counts exercised: ${counts.values.toSeq.distinct.sorted.mkString(", ")}")
  }

  it should "run a channel that changes the dimension" in {
    // Erasure maps a qubit to a qutrit, so K is 3x2 and the result is one dimension wider than the
    // state. This was a stated gap while the engine assumed one square size; it is the case that
    // tells a genuinely separated pair of dimensions from a renamed one.
    val rectangular = cases.filter(!_.square)

    assert(rectangular.nonEmpty)

    rectangular.groupBy(c => (c.trueInputDim, c.trueOutputDim)).toSeq.sortBy(_._1).foreach {
      case ((trueIn, trueOut), group) =>
        val c = group.head
        val note = if (c.padded) s"padded to ${c.inputDim}->${c.outputDim}" else "exactly"

        info(s"erasure $trueIn->$trueOut, $note, tile ${c.tile}")

        // Everything outside the true output lives only because the dimension was padded up, and
        // a zero row of K contributes nothing, so it has to come back zero.
        group.foreach { g =>
          for (row <- 0 until g.outputDim; col <- 0 until g.outputDim if row >= trueOut || col >= trueOut) {
            val (re, im) = results(g.index)(row * g.outputDim + col)

            withClue(s"case ${g.index} padding at ($row,$col): ") {
              assert(math.abs(re) < 1e-9 && math.abs(im) < 1e-9)
            }
          }
        }
    }

    // The flag state is basis element `trueOutputDim - 1` of the output, and erasure sends weight
    // there that the input had nowhere to put: the corner of the true result is the erasure
    // probability, 0.35, whatever the state was.
    cases.filter(c => c.name == "erasure" && c.trueInputDim == 2).foreach { c =>
      val corner = results(c.index)((c.trueOutputDim - 1) * c.outputDim + (c.trueOutputDim - 1))._1

      withClue(s"case ${c.index} flag weight: ") {
        assert(math.abs(corner - 0.35) < 1e-4, f"flag weight is $corner%.6f")
      }
    }
  }
}
