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
    "DiagonalKernel" -> (() => new DiagonalKernel(qubitCount = 6, maxWidth = 3, dataWidth = 24))
  )

  def main(args: Array[String]): Unit = {
    val name = args.headOption.getOrElse("AddressGen")
    val build = modules.getOrElse(name, sys.error(s"no such module: $name, have ${modules.keys.mkString(", ")}"))

    println(
      ChiselStage.emitSystemVerilog(
        build(),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
      )
    )
  }
}
