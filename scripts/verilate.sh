#!/usr/bin/env bash
# Simulate a generated netlist under Verilator against Scotty's golden vectors.
#
#   ./mill rtl.runMain brokkr.Generate DenseKernel
#   scripts/verilate.sh DenseKernel
#
# This drives Verilator directly rather than through a Chisel harness. As of Chisel 6.7.0 neither
# chiseltest's Verilator backend nor ChiselSim's svsim works with Verilator 5.052.
set -euo pipefail

module="${1:?usage: scripts/verilate.sh <Module>}"
netlist="build/${module}.sv"
bench="rtl/src/test/sv/${module}Tb.sv"

for f in "$netlist" "$bench"; do
  [[ -f "$f" ]] || { echo "missing $f" >&2; exit 1; }
done

verilator --binary -j 0 --timing \
  -Wno-WIDTHEXPAND -Wno-WIDTHTRUNC -Wno-UNUSEDSIGNAL -Wno-DECLFILENAME \
  --top-module "${module}Tb" \
  -Mdir "build/verilator-${module}" \
  -o "${module}Tb" \
  "$netlist" "$bench"

"build/verilator-${module}/${module}Tb"
