package brokkr

import circt.stage.ChiselStage

/** Emits SystemVerilog for a module.
  *
  * {{{
  * ./mill rtl.runMain brokkr.Generate build
  * }}}
  *
  * The same `--target systemverilog --split-verilog` flow CUTE's Makefile uses, which is what a
  * Verilator or FPGA run would consume.
  */
object Generate {
  private val modules: Map[String, () => chisel3.RawModule] = Map(
    "AddressGen" -> (() => new AddressGen(qubitCount = 6, maxWidth = 3)),
    "DiagonalKernel" -> (() => new DiagonalKernel(qubitCount = 6, maxWidth = 3, dataWidth = 24)),
    // Same width and format as the diagonal kernel, so a synthesis run compares the datapaths
    // rather than two differently sized designs.
    "DenseKernel" -> (() => new DenseKernel(maxWidth = 3, dataWidth = 24)),
    "MonomialKernel" -> (() => new MonomialKernel(maxWidth = 3, dataWidth = 24)),
    "BenesOnly" -> (() => new BenesOnly(maxWidth = 3, dataWidth = 24)),
    // The density-matrix engine's datapath. Tile 4 is 16 complex multiply-accumulates per cycle,
    // against the dense state-vector kernel's 8, so the comparison is per complex MAC.
    "ComplexGemm" -> (() => new ComplexGemm(tile = 4, dataWidth = 24, maxDepth = 1024))
  )

  /** `Generate [module ...]`, or no arguments for all of them. Writes into `build/`. */
  def main(args: Array[String]): Unit = {
    val wanted = if (args.isEmpty) modules.keys.toSeq.sorted else args.toSeq

    wanted.foreach { name =>
      val build = modules.getOrElse(
        name,
        sys.error(s"no such module: $name, have ${modules.keys.toSeq.sorted.mkString(", ")}")
      )

      ChiselStage.emitSystemVerilogFile(
        build(),
        args = Array("--target-dir", "build"),
        // disallowLocalVariables stops CIRCT emitting `automatic logic` inside always blocks,
        // which Yosys rejects with "Invalid nesting of always blocks". Verilator accepts either,
        // so the conservative form costs nothing and keeps one netlist good for both.
        firtoolOpts = Array(
          "-disable-all-randomization",
          "-strip-debug-info",
          "--lowering-options=disallowLocalVariables,disallowPackedArrays"
        )
      )

      println(s"wrote build/$name.sv")
    }
  }
}
