package brokkr

import chisel3._
import chisel3.util._

/** A complex matrix in `tile` SRAM banks, skewed so both access patterns are conflict-free.
  *
  * Holding the operands in registers was fine while the dimension equalled the tile and became the
  * dominant cost once it did not — at dimension eight the storage and its address muxing were
  * already 24% of the engine, and at ten qubits `rho` alone is 16 MiB. It belongs in memory.
  *
  * Memory brings back the problem the state-vector half of this project spent its length on, in a
  * much kinder form. A tiled matrix product reads two slices per cycle:
  *
  *   - a '''column slice''' — `tile` consecutive rows at one column, for the left operand;
  *   - a '''row slice''' — one row at `tile` consecutive columns, for the right operand.
  *
  * Bank by row and the column slice spreads but the row slice collides; bank by column and the
  * reverse. The classical answer is to '''skew''': `bank = (row + col) mod tile`. A column slice
  * varies `row` with `col` fixed and a row slice varies `col` with `row` fixed, so each walks the
  * banks one step at a time and neither ever repeats one.
  *
  * This is the same shape of result as the bank mapping in section 08 of the assessment — make a
  * strided access conflict-free by choosing where addresses land — and far simpler, because a GEMM
  * stride is regular where a qubit stride is a bit permutation. That contrast is most of the
  * argument for the density-matrix engine in one sentence.
  *
  * Reads are synchronous, so an address issued this cycle produces data the next; callers drive the
  * address a cycle ahead of the consumer.
  *
  * @param dimension the matrix is `dimension × dimension`
  * @param tile      the number of banks, which is also the slice width
  * @param dataWidth bits per real component
  * @param copies    how many matrices share the store, as the Kraus set does
  */
class SkewedStore(val dimension: Int, val tile: Int, val dataWidth: Int, val copies: Int) {
  require(isPow2(dimension) && isPow2(tile) && tile <= dimension, "powers of two, tile within dimension")
  require(copies >= 1, "a store holds at least one matrix")

  private val tileBits = log2Ceil(tile)
  private val dimBits = log2Ceil(dimension)
  private val columnGroups = dimension / tile

  /** Entries per bank: the whole store divided evenly, which the skew guarantees it is. */
  val perBank: Int = dimension * dimension * copies / tile

  private val addressBits = log2Ceil(perBank)
  private val re = Seq.fill(tile)(SyncReadMem(perBank, SInt(dataWidth.W)))
  private val im = Seq.fill(tile)(SyncReadMem(perBank, SInt(dataWidth.W)))

  // Which bank each lane wants, remembered so the data can be routed back when it arrives.
  private val laneBank = Reg(Vec(tile, UInt(tileBits.W)))
  private val addresses = WireDefault(VecInit(Seq.fill(tile)(0.U(addressBits.W))))
  private val enables = WireDefault(VecInit(Seq.fill(tile)(false.B)))

  /** `(row + col) mod tile`, the skew. */
  def bankOf(row: UInt, col: UInt): UInt = (row +& col)(tileBits - 1, 0)

  /** Where within a bank. Unique per element because, for a fixed row, the column's low bits pick
    * the bank and its high bits pick the offset.
    */
  def addressOf(copy: UInt, row: UInt, col: UInt): UInt = {
    val within = row * columnGroups.U + (col >> tileBits).asUInt

    (copy * (dimension * columnGroups).U + within)(addressBits - 1, 0)
  }

  def write(copy: UInt, row: UInt, col: UInt, valueRe: SInt, valueIm: SInt, enable: Bool): Unit = {
    val bank = bankOf(row, col)
    val address = addressOf(copy, row, col)

    (0 until tile).foreach { index =>
      when(enable && bank === index.U) {
        re(index).write(address, valueRe)
        im(index).write(address, valueIm)
      }
    }
  }

  /** Write a row slice: row `row`, at columns `tileCol * tile + lane`, one value per lane.
    *
    * This is the skew read in the other direction. A block row touches every bank exactly once, for
    * the same reason a row slice reads from every bank exactly once, so a `tile × tile` block can
    * be written in `tile` cycles with single-ported banks — one block row per cycle.
    */
  def writeSlice(
      copy: UInt,
      row: UInt,
      tileCol: UInt,
      valuesRe: Seq[SInt],
      valuesIm: Seq[SInt],
      enable: Bool
  ): Unit = {
    require(valuesRe.length == tile && valuesIm.length == tile, "a slice is one value per lane")

    (0 until tile).foreach { lane =>
      val col = ((tileCol << tileBits).asUInt + lane.U)(dimBits - 1, 0)

      write(copy, row, col, valuesRe(lane), valuesIm(lane), enable)
    }
  }

  private def issue(enable: Bool, locate: Int => (UInt, UInt), copy: UInt): Unit =
    (0 until tile).foreach { lane =>
      val (row, col) = locate(lane)
      val bank = bankOf(row, col)

      when(enable) { laneBank(lane) := bank }

      (0 until tile).foreach { index =>
        when(enable && bank === index.U) {
          addresses(index) := addressOf(copy, row, col)
          enables(index) := true.B
        }
      }
    }

  /** Issue a column slice: rows `tileRow * tile + lane`, all at `col`. */
  def readColumn(copy: UInt, tileRow: UInt, col: UInt, enable: Bool): Unit =
    issue(enable, lane => (((tileRow << tileBits).asUInt + lane.U)(dimBits - 1, 0), col), copy)

  /** Issue a row slice: row `row`, at columns `tileCol * tile + lane`. */
  def readRow(copy: UInt, row: UInt, tileCol: UInt, enable: Bool): Unit =
    issue(enable, lane => (row, ((tileCol << tileBits).asUInt + lane.U)(dimBits - 1, 0)), copy)

  private val bankRe = VecInit((0 until tile).map(i => re(i).read(addresses(i), enables(i))))
  private val bankIm = VecInit((0 until tile).map(i => im(i).read(addresses(i), enables(i))))

  /** The slice issued last cycle, routed back from banks to lanes. */
  def dataRe: Vec[SInt] = VecInit((0 until tile).map(lane => bankRe(laneBank(lane))))

  def dataIm: Vec[SInt] = VecInit((0 until tile).map(lane => bankIm(laneBank(lane))))
}
