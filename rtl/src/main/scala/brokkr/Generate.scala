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
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("build")

    println(
      ChiselStage.emitSystemVerilog(
        new AddressGen(qubitCount = 6, maxWidth = 3),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
      )
    )
  }
}
