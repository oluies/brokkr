package brokkr

import chisel3._
import chisel3.util._

/** A thin wrapper so [[SkewedStore]] can be synthesised on its own.
  *
  * Not part of the engine — it exists so `scripts/area.sh` can weigh skewed SRAM against the
  * register files it replaces.
  */
class SkewedStoreTop(val dimension: Int, val tile: Int, val dataWidth: Int, val copies: Int)
    extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val copy = Input(UInt(log2Ceil(copies + 1).W))
    val row = Input(UInt(log2Ceil(dimension).W))
    val col = Input(UInt(log2Ceil(dimension).W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    val readEnable = Input(Bool())
    val column = Input(Bool())

    val outRe = Output(Vec(tile, SInt(dataWidth.W)))
    val outIm = Output(Vec(tile, SInt(dataWidth.W)))
  })

  val store = new SkewedStore(dimension, dimension, tile, dataWidth, copies)

  store.write(io.copy, io.row, io.col, io.writeRe, io.writeIm, io.writeEnable)
  store.readColumn(io.copy, io.row, io.col, io.readEnable && io.column)
  store.readRow(io.copy, io.row, io.col, io.readEnable && !io.column)

  io.outRe := store.dataRe
  io.outIm := store.dataIm
}

/** The same capacity held in registers, which is what the engine does today. */
class RegisterStoreTop(val dimension: Int, val tile: Int, val dataWidth: Int, val copies: Int)
    extends Module {
  val cells = dimension * dimension * copies

  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(log2Ceil(cells).W))
    val writeRe = Input(SInt(dataWidth.W))
    val writeIm = Input(SInt(dataWidth.W))

    val readAddr = Input(Vec(tile, UInt(log2Ceil(cells).W)))
    val outRe = Output(Vec(tile, SInt(dataWidth.W)))
    val outIm = Output(Vec(tile, SInt(dataWidth.W)))
  })

  val re = Reg(Vec(cells, SInt(dataWidth.W)))
  val im = Reg(Vec(cells, SInt(dataWidth.W)))

  when(io.writeEnable) {
    re(io.writeAddr) := io.writeRe
    im(io.writeAddr) := io.writeIm
  }

  (0 until tile).foreach { lane =>
    io.outRe(lane) := RegNext(re(io.readAddr(lane)), 0.S)
    io.outIm(lane) := RegNext(im(io.readAddr(lane)), 0.S)
  }
}
