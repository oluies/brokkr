package brokkr

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.log2Ceil
import org.scalatest.flatspec.AnyFlatSpec

/** A harness for [[SkewedStore]], which is a plain class rather than a Module. */
class SkewedStoreHarness(val dimension: Int, val tile: Int, val dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeRow = Input(UInt(log2Ceil(dimension).W))
    val writeCol = Input(UInt(log2Ceil(dimension).W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    val readEnable = Input(Bool())
    /** True for a column slice, false for a row slice. */
    val column = Input(Bool())
    val index = Input(UInt(log2Ceil(dimension).W))
    val position = Input(UInt(log2Ceil(dimension).W))

    val outRe = Output(Vec(tile, SInt(dataWidth.W)))
    val outIm = Output(Vec(tile, SInt(dataWidth.W)))
  })

  val store = new SkewedStore(dimension, tile, dataWidth, copies = 1)

  store.write(0.U, io.writeRow, io.writeCol, io.writeRe, io.writeIm, io.writeEnable)
  store.readColumn(0.U, io.index, io.position, io.readEnable && io.column)
  store.readRow(0.U, io.index, io.position, io.readEnable && !io.column)

  io.outRe := store.dataRe
  io.outIm := store.dataIm
}

/** Checks that skewed storage serves both slice shapes, for every slice.
  *
  * The property being tested is the one the whole arrangement exists for: a column slice and a row
  * slice each touch `tile` distinct banks, so either can be served in one cycle. If the skew were
  * wrong — banking by row, say — one of the two shapes would collide and return the wrong lane's
  * data, which is exactly what an exhaustive readback catches.
  */
class SkewedStoreSpec extends AnyFlatSpec with ChiselSim {
  val Dimension = 8
  val Tile = 4
  val DataWidth = 24

  /** A distinctive value per cell, so a mis-routed lane is visible rather than plausible. */
  def value(row: Int, col: Int): Int = row * Dimension + col + 1

  def load(dut: SkewedStoreHarness): Unit = {
    for (row <- 0 until Dimension; col <- 0 until Dimension) {
      dut.io.writeEnable.poke(true.B)
      dut.io.writeRow.poke(row.U)
      dut.io.writeCol.poke(col.U)
      dut.io.writeRe.poke(value(row, col).S)
      dut.io.writeIm.poke((-value(row, col)).S)
      dut.clock.step()
    }

    dut.io.writeEnable.poke(false.B)
  }

  "the skew" should "place a column slice in distinct banks" in {
    // Checked in Scala first, because if this is false no amount of simulation will help.
    for (tileRow <- 0 until Dimension / Tile; col <- 0 until Dimension) {
      val banks = (0 until Tile).map(lane => (tileRow * Tile + lane + col) % Tile)

      withClue(s"column slice at tileRow $tileRow, col $col: ") {
        assert(banks.distinct.length == Tile, s"banks $banks collide")
      }
    }
  }

  it should "place a row slice in distinct banks" in {
    for (row <- 0 until Dimension; tileCol <- 0 until Dimension / Tile) {
      val banks = (0 until Tile).map(lane => (row + tileCol * Tile + lane) % Tile)

      withClue(s"row slice at row $row, tileCol $tileCol: ") {
        assert(banks.distinct.length == Tile, s"banks $banks collide")
      }
    }
  }

  "SkewedStore" should "return every column slice correctly" in {
    simulate(new SkewedStoreHarness(Dimension, Tile, DataWidth)) { dut =>
      load(dut)

      for (tileRow <- 0 until Dimension / Tile; col <- 0 until Dimension) {
        dut.io.readEnable.poke(true.B)
        dut.io.column.poke(true.B)
        dut.io.index.poke(tileRow.U)
        dut.io.position.poke(col.U)
        dut.clock.step()

        (0 until Tile).foreach { lane =>
          val row = tileRow * Tile + lane

          withClue(s"column slice tileRow $tileRow col $col lane $lane (row $row): ") {
            dut.io.outRe(lane).expect(value(row, col).S)
            dut.io.outIm(lane).expect((-value(row, col)).S)
          }
        }
      }
    }
  }

  it should "return every row slice correctly" in {
    simulate(new SkewedStoreHarness(Dimension, Tile, DataWidth)) { dut =>
      load(dut)

      for (row <- 0 until Dimension; tileCol <- 0 until Dimension / Tile) {
        dut.io.readEnable.poke(true.B)
        dut.io.column.poke(false.B)
        dut.io.index.poke(row.U)
        dut.io.position.poke(tileCol.U)
        dut.clock.step()

        (0 until Tile).foreach { lane =>
          val col = tileCol * Tile + lane

          withClue(s"row slice row $row tileCol $tileCol lane $lane (col $col): ") {
            dut.io.outRe(lane).expect(value(row, col).S)
            dut.io.outIm(lane).expect((-value(row, col)).S)
          }
        }
      }
    }
  }

  it should "divide the store evenly across banks" in {
    val counts = Array.fill(Tile)(0)

    for (row <- 0 until Dimension; col <- 0 until Dimension) counts((row + col) % Tile) += 1

    assert(counts.distinct.length == 1, s"banks hold $counts, which is uneven")
    info(s"${counts.head} entries in each of $Tile banks")
  }
}
