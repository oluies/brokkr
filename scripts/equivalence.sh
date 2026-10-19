#!/usr/bin/env bash
# Check that the Chisel and the SpinalHDL ComplexGemm are the same circuit.
#
#   ./mill rtl.runMain brokkr.Generate ComplexGemm
#   ./mill spinal.runMain brokkr.spinal.Generate
#   scripts/equivalence.sh
#
# Both netlists in one Verilator simulation, driven from identical full-range random stimulus and
# compared bit for bit on every cell every cycle. This is what makes comparing their areas mean
# anything: two generators agreeing on a number is only interesting if they agree on the function.
#
# Full-range random rather than the golden vectors, because the vectors are small amplitudes and the
# disagreements worth finding — truncation of negatives, wraparound of the complex subtract — live at
# the extremes.
set -euo pipefail

chisel="build/ComplexGemm/ComplexGemm.sv"
spinal="build/SpinalComplexGemm/SpinalComplexGemm.v"
bench="rtl/src/test/sv/GemmEquivalenceTb.sv"

for f in "$chisel" "$spinal" "$bench"; do
  [[ -f "$f" ]] || { echo "missing $f" >&2; exit 1; }
done

# MULTITOP because two unconnected module trees are read; TIMESCALEMOD because SpinalHDL emits a
# `timescale directive and firtool does not.
verilator --binary -j 0 --timing \
  -Wno-WIDTHEXPAND -Wno-WIDTHTRUNC -Wno-UNUSEDSIGNAL -Wno-DECLFILENAME \
  -Wno-MULTITOP -Wno-TIMESCALEMOD \
  --top-module GemmEquivalenceTb \
  -Mdir build/verilator-GemmEquivalence \
  -o GemmEquivalenceTb \
  "$chisel" "$spinal" "$bench"

build/verilator-GemmEquivalence/GemmEquivalenceTb
