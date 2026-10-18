#!/usr/bin/env bash
# Synthesise a generated module with Yosys and report its size.
#
# The three datapaths are supposed to cost very different amounts of silicon — that is the whole
# argument for keeping them apart — and until this existed the claim was structural rather than
# measured. Run after `./mill rtl.runMain brokkr.Generate`.
#
#   scripts/area.sh DiagonalKernel
#
# Cell counts after mapping to a generic gate library. That is a proxy for area, not a substitute
# for a standard-cell flow with a liberty file: it has no notion of drive strength, wire load or
# the fact that a real multiplier would be a hardened block.
set -euo pipefail

module="${1:?usage: scripts/area.sh <Module>}"
source_file="build/${module}/${module}.sv"

if [[ ! -f "$source_file" ]]; then
  echo "missing $source_file — run ./mill rtl.runMain brokkr.Generate $module" >&2
  exit 1
fi

# Every file this module generated, not just the top: firtool emits submodules to their own files,
# and reading the top alone leaves them as blackboxes — which silently reports a design as far
# smaller than it is. Reading only this module's own directory is the other half of that: submodule
# names depend only on shape, so `mem_16x24.sv` from one configuration would otherwise be read in
# place of another's. `hierarchy -top` then prunes whatever the named module does not reach.
sources=$(ls "build/${module}"/*.sv | tr '\n' ' ')

yosys -p "
  read_verilog -sv ${sources}
  hierarchy -top ${module}
  # Inline submodules, so the reported count is the whole design rather than the top's own cells.
  flatten
  proc; opt; fsm; opt; memory; opt
  techmap; opt
  abc -g AND,NAND,OR,NOR,XOR,XNOR,ANDNOT,ORNOT,MUX,NMUX
  opt -purge
  stat
" 2>&1 | sed -n '/Printing statistics/,/^End of script/p' | grep -vE 'Printing statistics|^End of script|^$'
