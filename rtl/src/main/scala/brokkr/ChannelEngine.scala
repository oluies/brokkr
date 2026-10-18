package brokkr

import chisel3._
import chisel3.util._

/** A whole Kraus channel in hardware: `rho -> sum K rho K†`, tiled.
  *
  * [[ComplexGemm]] computes a `tile × tile` block of a matrix product. This sequences those blocks
  * into a channel: for each Kraus operator it forms `T = K · rho`, keeps the intermediate, forms
  * `R = T · K†` — whose right operand is the conjugate of the same column, so the same storage is
  * read with the imaginary part negated — and accumulates `R`. That is `Channel.apply`, in gates.
  *
  * ==Tiling==
  * An earlier version held the dimension equal to the tile, which made the control a counter but
  * capped the engine at whatever width of multiplier array one is willing to build. Ten qubits
  * would have meant a 1024-wide tile. Here the dimension is independent: the engine loops over
  * `(dimension / tile)²` output blocks, and each block accumulates over the full `dimension`-long
  * inner dimension before being written out. The array size is now a cost decision rather than a
  * limit on the problem.
  *
  * The depth of an accumulation is therefore `dimension`, not `tile`, which is why
  * [[ComplexGemm]]'s widened accumulator matters: a block rounds once however deep the product is,
  * and the measured error does not grow with depth.
  *
  * Both matrices still live in registers, which is the next thing that has to change — at ten
  * qubits `rho` alone is 16 MiB and belongs in SRAM. The control above it would not.
  *
  * @param dimension  the state is `dimension × dimension`, and so is each Kraus operator
  * @param krausCount how many operators the channel has
  * @param dataWidth  bits per real component, including the sign
  * @param tile       the multiply array is `tile × tile`; must divide `dimension`
  */
class ChannelEngine(val dimension: Int, val krausCount: Int, val dataWidth: Int, val tile: Int)
    extends Module {
  require(isPow2(dimension), "the dimension has to be a power of two")
  require(isPow2(tile) && tile <= dimension, "the tile has to be a power of two no larger than the dimension")
  require(krausCount >= 1, "a channel needs at least one Kraus operator")

  val cells: Int = dimension * dimension
  val tilesPerSide: Int = dimension / tile
  val fractionBits: Int = Fixed.fractionBits(dataWidth)
  val accWidth: Int = dataWidth + log2Ceil(krausCount) + 1

  private val dimBits = log2Ceil(dimension)
  private val tileBits = log2Ceil(tile)
  private val gridBits = log2Ceil(tilesPerSide + 1)
  private val stepBits = log2Ceil(dimension + 1)
  private val krausBits = log2Ceil(krausCount + 1)

  object State extends ChiselEnum {
    val idle, firstPass, firstWrite, secondPass, secondWrite, done = Value
  }

  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeKraus = Input(Bool())
    val writeAddr = Input(UInt(log2Ceil(cells * krausCount).W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())

    val outRe = Output(Vec(cells, SInt(dataWidth.W)))
    val outIm = Output(Vec(cells, SInt(dataWidth.W)))
  })

  val rhoRe = Reg(Vec(cells, SInt(dataWidth.W)))
  val rhoIm = Reg(Vec(cells, SInt(dataWidth.W)))
  val krausRe = Reg(Vec(cells * krausCount, SInt(dataWidth.W)))
  val krausIm = Reg(Vec(cells * krausCount, SInt(dataWidth.W)))
  val tempRe = Reg(Vec(cells, SInt(dataWidth.W)))
  val tempIm = Reg(Vec(cells, SInt(dataWidth.W)))
  val accRe = Reg(Vec(cells, SInt(accWidth.W)))
  val accIm = Reg(Vec(cells, SInt(accWidth.W)))

  when(io.writeEnable) {
    when(io.writeKraus) {
      krausRe(io.writeAddr) := io.writeRe
      krausIm(io.writeAddr) := io.writeIm
    }.otherwise {
      val cell = io.writeAddr(log2Ceil(cells) - 1, 0)

      rhoRe(cell) := io.writeRe
      rhoIm(cell) := io.writeIm
    }
  }

  // The accumulation runs the full inner dimension, so the array must hold that many terms.
  val gemm = Module(new ComplexGemm(tile, dataWidth, maxDepth = dimension))
  val state = RegInit(State.idle)
  val step = RegInit(0.U(stepBits.W))
  val kraus = RegInit(0.U(krausBits.W))
  val tileRow = RegInit(0.U(gridBits.W))
  val tileCol = RegInit(0.U(gridBits.W))

  /** Row `lane` of the block at `tileIndex`. */
  private def rowOf(tileIndex: UInt, lane: Int): UInt =
    (((tileIndex << tileBits).asUInt + lane.U)(dimBits - 1, 0))

  private def cellOf(row: UInt, col: UInt): UInt =
    (((row << dimBits).asUInt | col)(2 * dimBits - 1, 0))

  private def krausCellOf(row: UInt, col: UInt): UInt =
    (((kraus << (2 * dimBits)).asUInt | cellOf(row, col))(log2Ceil(cells * krausCount) - 1, 0))

  val first = state === State.firstPass
  val stepIndex = step(dimBits - 1, 0)

  gemm.io.clear := state === State.firstWrite || state === State.secondWrite || io.start
  gemm.io.inValid := first || state === State.secondPass

  (0 until tile).foreach { lane =>
    val leftRow = rowOf(tileRow, lane)
    val rightCol = rowOf(tileCol, lane)

    // First pass:  a = column `step` of K's block row, b = row `step` of rho's block column.
    // Second pass: a = column `step` of T's block row, b = row `step` of K†, which is the
    //              conjugate of column `step` of K — the same address, imaginary part negated.
    gemm.io.aRe(lane) := Mux(first, krausRe(krausCellOf(leftRow, stepIndex)), tempRe(cellOf(leftRow, stepIndex)))
    gemm.io.aIm(lane) := Mux(first, krausIm(krausCellOf(leftRow, stepIndex)), tempIm(cellOf(leftRow, stepIndex)))
    gemm.io.bRe(lane) := Mux(first, rhoRe(cellOf(stepIndex, rightCol)), krausRe(krausCellOf(rightCol, stepIndex)))
    gemm.io.bIm(lane) := Mux(first, rhoIm(cellOf(stepIndex, rightCol)), -krausIm(krausCellOf(rightCol, stepIndex)))
  }

  /** Walk the output blocks in row-major order; true on the last one. */
  private def advanceTile(): Bool = {
    val lastCol = tileCol === (tilesPerSide - 1).U
    val lastRow = tileRow === (tilesPerSide - 1).U

    tileCol := Mux(lastCol, 0.U, tileCol + 1.U)
    when(lastCol) { tileRow := Mux(lastRow, 0.U, tileRow + 1.U) }

    lastCol && lastRow
  }

  switch(state) {
    is(State.idle) {
      when(io.start) {
        kraus := 0.U
        step := 0.U
        tileRow := 0.U
        tileCol := 0.U
        (0 until cells).foreach { i =>
          accRe(i) := 0.S
          accIm(i) := 0.S
        }
        state := State.firstPass
      }
    }

    is(State.firstPass) {
      step := step + 1.U
      when(step === (dimension - 1).U) {
        step := 0.U
        state := State.firstWrite
      }
    }

    is(State.firstWrite) {
      for (i <- 0 until tile; j <- 0 until tile) {
        val target = cellOf(rowOf(tileRow, i), rowOf(tileCol, j))

        tempRe(target) := gemm.io.outRe(i * tile + j)
        tempIm(target) := gemm.io.outIm(i * tile + j)
      }

      state := Mux(advanceTile(), State.secondPass, State.firstPass)
    }

    is(State.secondPass) {
      step := step + 1.U
      when(step === (dimension - 1).U) {
        step := 0.U
        state := State.secondWrite
      }
    }

    is(State.secondWrite) {
      for (i <- 0 until tile; j <- 0 until tile) {
        val target = cellOf(rowOf(tileRow, i), rowOf(tileCol, j))

        accRe(target) := accRe(target) + gemm.io.outRe(i * tile + j)
        accIm(target) := accIm(target) + gemm.io.outIm(i * tile + j)
      }

      when(advanceTile()) {
        kraus := kraus + 1.U
        state := Mux(kraus === (krausCount - 1).U, State.done, State.firstPass)
      }.otherwise {
        state := State.secondPass
      }
    }

    is(State.done) {
      when(io.start) { state := State.idle }
    }
  }

  (0 until cells).foreach { i =>
    io.outRe(i) := accRe(i)(dataWidth - 1, 0).asSInt
    io.outIm(i) := accIm(i)(dataWidth - 1, 0).asSInt
  }

  io.busy := state =/= State.idle && state =/= State.done
  io.done := state === State.done
}
