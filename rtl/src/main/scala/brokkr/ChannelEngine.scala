package brokkr

import chisel3._
import chisel3.util._

/** A whole Kraus channel in hardware: `rho -> sum K rho K†`.
  *
  * This is the first thing in the project that is an engine rather than a datapath. [[ComplexGemm]]
  * computes a tile of a matrix product; this sequences two of them per Kraus operator, keeps the
  * intermediate, accumulates the terms, and produces the evolved state — which is what a
  * density-matrix simulator actually does, and what `Channel.apply` does in Scotty.
  *
  * The dimension is held equal to the tile, so one accumulation pass covers the whole matrix and
  * the control is a counter rather than a tiling loop. That is the right first version: it makes
  * the sequencing visible and checkable against the simulator, and tiling a larger matrix is a
  * loop around it rather than a different machine.
  *
  * ==The sequence==
  * For each Kraus operator `K`, with the outer-product array consuming one column of the left
  * operand and one row of the right per cycle:
  *
  *   1. `T = K · rho` — column `j` of `K` against row `j` of `rho`;
  *   2. `R = T · K†` — column `j` of `T` against row `j` of `K†`, which is the conjugate of column
  *      `j` of `K`, so the same storage is read with the imaginary part negated;
  *   3. accumulate `R`.
  *
  * @param dimension the state is `dimension × dimension`, and so is each Kraus operator
  * @param krausCount how many operators the channel has
  * @param dataWidth bits per real component, including the sign
  */
class ChannelEngine(val dimension: Int, val krausCount: Int, val dataWidth: Int) extends Module {
  require(isPow2(dimension), "the dimension has to be a power of two")
  require(krausCount >= 1, "a channel needs at least one Kraus operator")

  val cells: Int = dimension * dimension
  val fractionBits: Int = Fixed.fractionBits(dataWidth)

  /** Wide enough to sum `krausCount` terms without overflowing. */
  val accWidth: Int = dataWidth + log2Ceil(krausCount) + 1

  object State extends ChiselEnum {
    val idle, first, capture, second, accumulate, next, done = Value
  }

  val io = IO(new Bundle {
    /** Load port. `target` selects rho or the Kraus store; the address is row-major. */
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
      // The write address is sized for the Kraus store, which is the larger of the two.
      val cell = io.writeAddr(log2Ceil(cells) - 1, 0)

      rhoRe(cell) := io.writeRe
      rhoIm(cell) := io.writeIm
    }
  }

  val gemm = Module(new ComplexGemm(dimension, dataWidth, maxDepth = dimension))
  val state = RegInit(State.idle)
  val step = RegInit(0.U(log2Ceil(dimension + 1).W))
  val kraus = RegInit(0.U(log2Ceil(krausCount + 1).W))

  // The dimension is a power of two, so a row-major address is a concatenation rather than a
  // multiply-add. The counters carry one extra bit for their terminal values, which is why they are
  // narrowed here before being used as index fields.
  val indexBits = log2Ceil(dimension)
  val stepIndex = step(indexBits - 1, 0)
  val krausIndex = kraus(log2Ceil(krausCount) - 1, 0)

  gemm.io.clear := state === State.capture || state === State.accumulate || io.start
  gemm.io.inValid := state === State.first || state === State.second

  // Column `step` of the left operand, row `step` of the right. In the second product the right
  // operand is K†, whose row `step` is the conjugate of K's column `step` — the same addresses,
  // read with the imaginary part negated.
  (0 until dimension).foreach { lane =>
    val laneField = lane.U(indexBits.W)
    val krausColumn = Cat(krausIndex, laneField, stepIndex)
    val tempColumn = Cat(laneField, stepIndex)
    val rhoRow = Cat(stepIndex, laneField)

    gemm.io.aRe(lane) := Mux(state === State.first, krausRe(krausColumn), tempRe(tempColumn))
    gemm.io.aIm(lane) := Mux(state === State.first, krausIm(krausColumn), tempIm(tempColumn))
    gemm.io.bRe(lane) := Mux(state === State.first, rhoRe(rhoRow), krausRe(krausColumn))
    gemm.io.bIm(lane) := Mux(state === State.first, rhoIm(rhoRow), -krausIm(krausColumn))
  }

  switch(state) {
    is(State.idle) {
      when(io.start) {
        kraus := 0.U
        step := 0.U
        (0 until cells).foreach { i =>
          accRe(i) := 0.S
          accIm(i) := 0.S
        }
        state := State.first
      }
    }

    is(State.first) {
      step := step + 1.U
      when(step === (dimension - 1).U) {
        step := 0.U
        state := State.capture
      }
    }

    // One cycle for the last accumulation to land before it is read.
    is(State.capture) {
      (0 until cells).foreach { i =>
        tempRe(i) := gemm.io.outRe(i)
        tempIm(i) := gemm.io.outIm(i)
      }
      state := State.second
    }

    is(State.second) {
      step := step + 1.U
      when(step === (dimension - 1).U) {
        step := 0.U
        state := State.accumulate
      }
    }

    is(State.accumulate) {
      (0 until cells).foreach { i =>
        accRe(i) := accRe(i) + gemm.io.outRe(i)
        accIm(i) := accIm(i) + gemm.io.outIm(i)
      }
      state := State.next
    }

    is(State.next) {
      kraus := kraus + 1.U
      state := Mux(kraus === (krausCount - 1).U, State.done, State.first)
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
