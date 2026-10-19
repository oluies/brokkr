package brokkr

import chisel3._
import chisel3.util._

/** A whole Kraus channel in hardware: `rho -> sum K rho K†`, tiled, on banked memory.
  *
  * [[ComplexGemm]] computes a `tile × tile` block of a matrix product. This sequences those blocks
  * into a channel: for each Kraus operator it forms `T = K · rho`, keeps the intermediate, forms
  * `R = T · K†` — whose right operand is the conjugate of the same column, so the same storage is
  * read with the imaginary part negated — and accumulates `R`.
  *
  * ==Tiling==
  * The dimension is independent of the array: the engine walks the output blocks of each product,
  * each accumulating over the full `inputDim`-long inner dimension. The array size is a cost
  * decision rather than a limit on the problem, and the accumulation depth is `inputDim`, which is
  * why [[ComplexGemm]]'s widened accumulator matters — a block rounds once however deep it is.
  *
  * ==Rectangular channels==
  * A Kraus operator does not have to be square, and the channel that forced this is one `pctc`
  * uses: '''erasure''' replaces the input with a flag state on a third basis element, so its
  * operators are `3 × 2` and it maps a qubit to a qutrit. The engine therefore carries two
  * dimensions rather than one:
  *
  * {{{
  * rho     inputDim  × inputDim
  * K       outputDim × inputDim
  * T = K·rho    outputDim × inputDim
  * R = T·K†     outputDim × outputDim
  * }}}
  *
  * Both products accumulate over `inputDim`, and the only thing that differs between them is how
  * many block columns there are: `inputDim / tile` for the first, `outputDim / tile` for the
  * second. Neither dimension has to be a power of two — erasure on three qubits is `8 → 12` — but
  * both have to be whole numbers of tiles. A dimension that is not, such as the one-qubit case's
  * output of three, is zero-padded by the host up to one; that is exact rather than approximate,
  * since a zero row of `K` contributes nothing to either product, and it costs `(padded/true)²` in
  * work on the second product.
  *
  * ==Storage==
  * Everything with a dimension in it lives in [[SkewedStore]]: `rho`, the Kraus set, the
  * intermediate `T`, and the accumulator. Nothing is held in a register file. Register files made
  * the address muxing 24% of the engine at dimension eight and would be preposterous at ten qubits,
  * where `rho` alone is 16 MiB. Three consequences shape the control:
  *
  *   - reads are synchronous, so an address is issued a cycle before the lane consumes it, and each
  *     accumulation pass runs `inputDim + 1` cycles rather than `inputDim`;
  *   - a `tile × tile` block cannot be written in one cycle, since `tile` single-ported banks
  *     cannot absorb `tile²` writes. It goes out over `tile` cycles, one block row at a time, each
  *     touching every bank exactly once;
  *   - the accumulator is read-modify-write, so its write runs a cycle behind its read and the
  *     second product's write phase is `tile + 1` cycles. It is the only store here that reads and
  *     writes in the same cycle, which asks for a memory with separate ports rather than one
  *     shared; the generic synthesis below cannot price that difference and does not claim to.
  *
  * The accumulator never has to be cleared. The first Kraus operator '''writes''' its result and
  * every later one adds to it, which removes an `outputDim · (outputDim/tile)`-cycle zeroing pass
  * and the first operator's read along with it.
  *
  * Because the result is in memory it cannot be presented as one wide port. It comes out a row
  * slice at a time, through the same `readRow` the datapath uses, while the engine sits in `done`.
  *
  * @param inputDim   the state is `inputDim × inputDim`
  * @param outputDim  the result is `outputDim × outputDim`, and each operator `outputDim × inputDim`
  * @param krausCount how many operators the channel has
  * @param dataWidth  bits per real component, including the sign
  * @param tile       the multiply array is `tile × tile`; must divide both dimensions
  */
class ChannelEngine(
    val inputDim: Int,
    val outputDim: Int,
    val krausCount: Int,
    val dataWidth: Int,
    val tile: Int
) extends Module {
  require(isPow2(tile) && tile >= 2, "the array is a power of two at least two wide")
  require(
    inputDim % tile == 0 && outputDim % tile == 0,
    "both dimensions have to be whole numbers of tiles; pad the odd one out with zeros"
  )
  require(krausCount >= 1, "a channel needs at least one Kraus operator")

  /** The result and the accumulator are `outputDim × outputDim`. */
  val cells: Int = outputDim * outputDim

  val inTiles: Int = inputDim / tile
  val outTiles: Int = outputDim / tile

  /** Headroom for summing `krausCount` results without rounding between them. */
  val accWidth: Int = dataWidth + log2Ceil(krausCount) + 1

  private val inBits = log2Ceil(inputDim)
  private val outBits = log2Ceil(outputDim)
  private val tileBits = log2Ceil(tile)
  private val gridBits = log2Ceil(math.max(inTiles, outTiles) + 1)
  private val stepBits = log2Ceil(inputDim + 2)
  private val krausBits = log2Ceil(krausCount + 1)
  private val rowBits = log2Ceil(tile + 2)

  object State extends ChiselEnum {
    val idle, firstPass, firstWrite, secondPass, secondWrite, done = Value
  }

  val io = IO(new Bundle {
    /** Load port. `rho` is addressed `inputDim × inputDim`, an operator `outputDim × inputDim`. */
    val writeEnable = Input(Bool())
    val writeKraus = Input(Bool())
    val writeCopy = Input(UInt(krausBits.W))
    val writeRow = Input(UInt(math.max(inBits, outBits).W))
    val writeCol = Input(UInt(inBits.W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())

    /** Result read port, live in `done`: a row slice in, `tile` cells out a cycle later. */
    val resultRow = Input(UInt(outBits.W))
    val resultTileCol = Input(UInt(gridBits.W))
    val resultRe = Output(Vec(tile, SInt(dataWidth.W)))
    val resultIm = Output(Vec(tile, SInt(dataWidth.W)))
  })

  val rho = new SkewedStore(inputDim, inputDim, tile, dataWidth, copies = 1)
  val kraus = new SkewedStore(outputDim, inputDim, tile, dataWidth, copies = krausCount)
  val temp = new SkewedStore(outputDim, inputDim, tile, dataWidth, copies = 1)
  val acc = new SkewedStore(outputDim, outputDim, tile, accWidth, copies = 1)

  val gemm = Module(new ComplexGemm(tile, dataWidth, maxDepth = inputDim))
  val state = RegInit(State.idle)
  val step = RegInit(0.U(stepBits.W))
  val operator = RegInit(0.U(krausBits.W))
  val tileRow = RegInit(0.U(gridBits.W))
  val tileCol = RegInit(0.U(gridBits.W))
  val blockRow = RegInit(0.U(rowBits.W))

  rho.write(
    0.U,
    io.writeRow(inBits - 1, 0),
    io.writeCol,
    io.writeRe,
    io.writeIm,
    io.writeEnable && !io.writeKraus
  )
  kraus.write(
    io.writeCopy,
    io.writeRow(outBits - 1, 0),
    io.writeCol,
    io.writeRe,
    io.writeIm,
    io.writeEnable && io.writeKraus
  )

  private val reading = state === State.firstPass || state === State.secondPass
  private val issuing = reading && step < inputDim.U
  private val first = state === State.firstPass
  private val stepIndex = step(inBits - 1, 0)

  // Addresses go out a cycle ahead of the lane that consumes them. The left operand of the second
  // product is `T`, whose block row is the output's; the right operand is `K†`, whose block column
  // is a block row of `K`, so the same column read serves both with `tileCol` in place of `tileRow`.
  kraus.readColumn(operator, Mux(first, tileRow, tileCol), stepIndex, issuing)
  rho.readRow(0.U, stepIndex, tileCol, issuing && first)
  temp.readColumn(0.U, tileRow, stepIndex, issuing && !first)

  private val firstWriting = state === State.firstWrite
  private val secondWriting = state === State.secondWrite

  // The second write is a read-modify-write pipeline over the block's rows: issue the slice at
  // `blockRow`, add into the slice read last cycle at `blockRow - 1`. So it runs one cycle longer
  // than the block has rows, and the gemm cannot be cleared until that last cycle.
  private val accumulating = operator =/= 0.U
  private val issuingAcc = secondWriting && blockRow < tile.U && accumulating
  private val writingAcc = secondWriting && blockRow > 0.U

  gemm.io.clear := (firstWriting && blockRow === (tile - 1).U) ||
    (secondWriting && blockRow === tile.U) || io.start
  gemm.io.inValid := reading && step > 0.U

  (0 until tile).foreach { lane =>
    // First pass:  a = a column of K, b = a row of rho.
    // Second pass: a = a column of T, b = a row of K†, which is the conjugate of a column of K.
    gemm.io.aRe(lane) := Mux(first, kraus.dataRe(lane), temp.dataRe(lane))
    gemm.io.aIm(lane) := Mux(first, kraus.dataIm(lane), temp.dataIm(lane))
    gemm.io.bRe(lane) := Mux(first, rho.dataRe(lane), kraus.dataRe(lane))
    gemm.io.bIm(lane) := Mux(first, rho.dataIm(lane), -kraus.dataIm(lane))
  }

  /** The row of the block being written: `blockRow` in the first write, one behind in the second. */
  private val rowIndex = Mux(secondWriting, blockRow - 1.U, blockRow)(tileBits - 1, 0)
  private val writingRow = (((tileRow << tileBits).asUInt + rowIndex)(outBits - 1, 0))
  private val readingRow =
    (((tileRow << tileBits).asUInt + blockRow(tileBits - 1, 0))(outBits - 1, 0))

  private val blockValuesRe =
    (0 until tile).map(j => gemm.io.outRe(((rowIndex << tileBits).asUInt + j.U)(2 * tileBits - 1, 0)))
  private val blockValuesIm =
    (0 until tile).map(j => gemm.io.outIm(((rowIndex << tileBits).asUInt + j.U)(2 * tileBits - 1, 0)))

  temp.writeSlice(0.U, writingRow, tileCol, blockValuesRe, blockValuesIm, firstWriting)

  // The first operator writes its result; every later one adds to what is there. That is what lets
  // the accumulator start uninitialised, with no zeroing pass and no read on the first operator.
  private val heldRe = (0 until tile).map(lane => Mux(accumulating, acc.dataRe(lane), 0.S))
  private val heldIm = (0 until tile).map(lane => Mux(accumulating, acc.dataIm(lane), 0.S))
  private val sumRe =
    (0 until tile).map(lane => (heldRe(lane) +& blockValuesRe(lane))(accWidth - 1, 0).asSInt)
  private val sumIm =
    (0 until tile).map(lane => (heldIm(lane) +& blockValuesIm(lane))(accWidth - 1, 0).asSInt)

  // One read port, two users: the pipeline while the second product runs, the host once it is done.
  acc.readRow(0.U, readingRow, tileCol, issuingAcc)
  acc.readRow(0.U, io.resultRow, io.resultTileCol, state === State.done)
  acc.writeSlice(0.U, writingRow, tileCol, sumRe, sumIm, writingAcc)

  /** Walk the output blocks in row-major order; true on the last one.
    *
    * `colLimit` differs between the products: `T` is `outputDim × inputDim` and `R` is
    * `outputDim × outputDim`, so only the block-column count changes.
    */
  private def advanceTile(colLimit: Int): Bool = {
    val lastCol = tileCol === (colLimit - 1).U
    val lastRow = tileRow === (outTiles - 1).U

    tileCol := Mux(lastCol, 0.U, tileCol + 1.U)
    when(lastCol) { tileRow := Mux(lastRow, 0.U, tileRow + 1.U) }

    lastCol && lastRow
  }

  switch(state) {
    is(State.idle) {
      when(io.start) {
        operator := 0.U
        step := 0.U
        tileRow := 0.U
        tileCol := 0.U
        blockRow := 0.U
        state := State.firstPass
      }
    }

    // One extra cycle: the last address issues at `inputDim - 1` and is consumed at `inputDim`.
    is(State.firstPass) {
      step := step + 1.U
      when(step === inputDim.U) {
        step := 0.U
        blockRow := 0.U
        state := State.firstWrite
      }
    }

    is(State.firstWrite) {
      blockRow := blockRow + 1.U
      when(blockRow === (tile - 1).U) {
        blockRow := 0.U
        state := Mux(advanceTile(inTiles), State.secondPass, State.firstPass)
      }
    }

    is(State.secondPass) {
      step := step + 1.U
      when(step === inputDim.U) {
        step := 0.U
        blockRow := 0.U
        state := State.secondWrite
      }
    }

    is(State.secondWrite) {
      blockRow := blockRow + 1.U
      when(blockRow === tile.U) {
        blockRow := 0.U
        when(advanceTile(outTiles)) {
          operator := operator + 1.U
          state := Mux(operator === (krausCount - 1).U, State.done, State.firstPass)
        }.otherwise {
          state := State.secondPass
        }
      }
    }

    is(State.done) {
      when(io.start) { state := State.idle }
    }
  }

  (0 until tile).foreach { lane =>
    io.resultRe(lane) := acc.dataRe(lane)(dataWidth - 1, 0).asSInt
    io.resultIm(lane) := acc.dataIm(lane)(dataWidth - 1, 0).asSInt
  }

  io.busy := state =/= State.idle && state =/= State.done
  io.done := state === State.done
}
