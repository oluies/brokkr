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
    "ComplexGemm" -> (() => new ComplexGemm(tile = 4, dataWidth = 24, maxDepth = 1024)),
    // The whole engine: a Kraus channel on a three-qubit state with a four-wide array, so the
    // control actually loops over tiles rather than covering the matrix in one pass.
    "ChannelEngine" -> (() =>
      new ChannelEngine(inputDim = 8, outputDim = 8, krausCount = 4, dataWidth = 24, tile = 4)),
    // The same engine on a channel that changes the dimension: erasure over three qubits is
    // 8 -> 12, so K is 12x8 and the result is 12x12. What the rectangular case costs over the
    // square one is storage and an accumulator, not control, and this is how that gets weighed.
    "ErasureEngine" -> (() =>
      new ChannelEngine(inputDim = 8, outputDim = 12, krausCount = 3, dataWidth = 24, tile = 4) {
        // It is the same class, so it would otherwise emit over `ChannelEngine.sv`.
        override def desiredName = "ErasureEngine"
      }),
    // The same capacity two ways, to weigh skewed SRAM against the register files it replaces.
    "SkewedStoreTop" -> (() => new SkewedStoreTop(8, 4, 24, copies = 4)),
    "RegisterStoreTop" -> (() => new RegisterStoreTop(8, 4, 24, copies = 4)),
    // pctc's repetition-code decoder, at the distances its side note tabulates.
    "MajorityVote3" -> (() => new MajorityVote(3) { override def desiredName = "MajorityVote3" }),
    "MajorityVote5" -> (() => new MajorityVote(5) { override def desiredName = "MajorityVote5" }),
    "MajorityVote7" -> (() => new MajorityVote(7) { override def desiredName = "MajorityVote7" }),
    // Exact and recursive majority at the same nine copies, to weigh what the cheaper one saves.
    "MajorityVote9" -> (() => new MajorityVote(9) { override def desiredName = "MajorityVote9" }),
    "RecursiveMajority9" -> (() => new RecursiveMajority(levels = 2) { override def desiredName = "RecursiveMajority9" })
  )

  /** `Generate [module ...]`, or no arguments for all of them. Writes into `build/`. */
  def main(args: Array[String]): Unit = {
    val wanted = if (args.isEmpty) modules.keys.toSeq.sorted else args.toSeq

    wanted.foreach { name =>
      val build = modules.getOrElse(
        name,
        sys.error(s"no such module: $name, have ${modules.keys.toSeq.sorted.mkString(", ")}")
      )

      // One directory per module, not one shared `build/`. Two configurations of the same
      // generator emit submodules under names that depend only on their shape — `mem_16x24.sv` —
      // so a shared directory lets the later run silently overwrite the earlier one's submodule
      // and quietly changes what a synthesis run is measuring.
      ChiselStage.emitSystemVerilogFile(
        build(),
        args = Array("--target-dir", s"build/$name"),
        // disallowLocalVariables stops CIRCT emitting `automatic logic` inside always blocks,
        // which Yosys rejects with "Invalid nesting of always blocks". Verilator accepts either,
        // so the conservative form costs nothing and keeps one netlist good for both.
        firtoolOpts = Array(
          "-disable-all-randomization",
          "-strip-debug-info",
          "--lowering-options=disallowLocalVariables,disallowPackedArrays"
        )
      )

      println(s"wrote build/$name/$name.sv")
    }
  }
}
