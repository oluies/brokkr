# Brokkr

*Brokkr forged Mjölnir and Draupnir; Skíðblaðnir was dwarf-work too.*

A design assessment, and eventually an implementation, of a
[CUTE](https://github.com/OpenXiangShan/CUTE)-style Chisel accelerator for quantum simulation.

**[Read the design assessment →](https://oluies.github.io/brokkr/)**

## Status

All three datapaths exist as RTL. The engine does not. What is here:

- the design assessment in `docs/`, published to GitHub Pages;
- the measurement record in `notes/`;
- `model/` — the cycle-approximate model: bank conflicts, in Scala;
- `rtl/` — Chisel: `AddressGen` (slice addressing), `DiagonalKernel` (phase path),
  `MonomialKernel` (permute path, with a Benes network) and `DenseKernel` (MAC array), each
  verified against Scotty under Verilator;
- `vectors/` — the Scala 3 generator that produces golden vectors from Scotty.

The gate-fusion pass that motivated all of it lives in
[Scotty](https://github.com/oluies/scotty/tree/pctc) (`scotty.simulator.fusion`) rather than here,
because it is a simulator optimisation in its own right.

## Building the RTL

```bash
./mill rtl.test                                      # both blocks, against Scotty's vectors
./mill rtl.runMain brokkr.Generate                   # emit SystemVerilog via CIRCT
scripts/area.sh DenseKernel                          # synthesise with Yosys, report cells
scripts/verilate.sh DenseKernel                      # simulate the netlist under Verilator
```

`area.sh` needs Yosys; the tests and `verilate.sh` need Verilator. firtool arrives as an ordinary
Maven dependency.

Tests run under Verilator through **ChiselSim**, which needs Chisel 7: on 6.7.0 (the version CUTE
pins) ChiselSim's svsim desynchronises with `Unexpected message: Ready` on the first clock edge
against Verilator 5.052, and `chiseltest` — which stopped at 6.0.0 — fails to compile its own C++
harness with `unknown type name 'WData'`. On Chisel 7.0.0-RC1 it works.

A SystemVerilog testbench driving Verilator through `$readmemh` is kept alongside for the dense
kernel, since testing the *generated netlist* with the toolchain an FPGA bring-up would use is a
slightly different question from testing the elaborated design.

### What it costs in silicon

| module | cells | amplitudes/cycle | cells per amplitude/cycle |
|---|---|---|---|
| `AddressGen` | 79 | — | — |
| `BenesOnly` | 2 304 | — | — |
| `DiagonalKernel` | 20 775 | 1 | 20 775 |
| `MonomialKernel` | 159 287 | 8 | **19 910** |
| `DenseKernel` | 169 577 | 1 | **169 577** |

Same width (k=3) and format, so this compares datapaths. Three things fall out:

- **the permute path costs what the phase path costs** — within 4% per unit throughput;
- **the dense path costs 8.5× either**, which is `2^k` as predicted, so the gap widens with the
  fusion width: around 64× at k=6;
- **the shuffle fabric is 1.4% of the permute path**. The thing that looked like the architectural
  risk is a rounding error next to the multipliers.

### Bank conflicts

```bash
./mill model.runMain brokkr.model.BankReport
```

Within a slice the `2^k` addresses differ only in the bits at the supported qubit positions, so the
address set is `base + V` for a linear subspace `V` over GF(2). A linear bank mapping — XOR gates,
nothing else — then gives `bank(base + v) = bank(base) ^ bank(v)`, so the conflict pattern is
identical for every slice, and

```
cycles per slice = 2^(k - rank of the mapping's columns at the support)
```

which makes the design question a coding-theory one: single-cycle gathering for every width-`k`
support holds exactly when every `k` columns are independent, i.e. the mapping is the parity check
of a code with minimum distance `k+1`.

**The obvious mapping never works.** Taking the low address bits leaves every column beyond
`bankBits` equal to zero, so any block touching a high qubit puts all `2^k` of its addresses in one
bank — 13.6× the floor at k=6, and never conflict-free at any bank count.

**A cheap XOR mapping essentially fixes it.** With 32 banks, mean cycles as a multiple of the floor:

| mapping | k=3 | k=4 | k=5 | k=6 |
|---|---|---|---|---|
| low bits | 5.31× | 9.19× | 15.87× | 13.64× |
| xor fold | 1.47× | 2.02× | 2.96× | 2.28× |
| ext. Hamming | 1.06× | 1.20× | 1.55× | **1.20×** |

Single-cycle gathering for *every* support stops being affordable past k=3 (64 banks via extended
Hamming; 2048 to reach k=5 via BCH), but the mean is what sets throughput.

### Why a Benes network, not an Omega network

`PermutationStudy` fuses randomised circuits and classifies the permutations monomial blocks
contain: 7.7% are bit permutations, 33.5% are affine over GF(2), and **58.8% are arbitrary** —
Toffoli and Fredkin generate permutations that are not affine. An Omega network is blocking and
routes bit permutations natively, so it could not serve most blocks. A Benes network routes every
permutation in `(2k-1) * 2^(k-1)` switches, with settings computed by the host once per block.

Note this is a different shuffle from the memory-side one. Moving amplitudes between lanes and banks
*is* a bit permutation, set by the qubit support, and an Omega network suits it. Routing the
operator's own permutation is not.

Regenerating the golden vectors needs Scotty published locally — **both** to Ivy and to M2, since
Mill resolves a `SNAPSHOT` from `~/.ivy2/local` first and a stale copy there will silently shadow a
fresh one in `~/.m2`:

```bash
cd ../scotty && sbt publishLocal publishM2
cd ../brokkr && ./mill vectors.runMain brokkr.vectors.AddressVectors rtl/src/test/resources/address-vectors.txt
```

### Why two Scala versions

Chisel is published for Scala 2.13 and Scotty is Scala 3.9, and they cannot call each other. CUTE's
build sets `-Ytasty-reader`, which in principle lets 2.13 read Scala 3 signatures, so it was worth
testing: 2.13.15 resolves the package and then fails on every type with `class file Complex.tasty is
broken`. Scotty's TASTy is newer than that reader understands.

So the golden model reaches the testbench as a generated file. That is the better arrangement
anyway — it is the same mechanism a C++ difftest harness or an FPGA bring-up would use.

## The question

CUTE is "a scalable CPU-centric and ultra-utilized tensor engine for convolutions" — Chisel 6.7 on
Scala 2.13, coupled to `rocket-chip`, with `XSAICache` on the memory side and difftest against a
C++ golden model. The hot kernel of state-vector quantum simulation is structurally the same thing
its datapath does: a strided gather, a small dense complex multiply-accumulate, a scatter. Qubit
addressing is pure bit permutation, so CUTE's `OmegaNetworkRouter` is arguably a better fit for
quantum simulation than for convolution.

## The finding so far

Gate fusion is what makes the kernel tensor-engine-shaped, and the measurements say something
sharper than expected: **fusion does not reduce the work, it converts memory traffic into
arithmetic**, at the exchange rate `8·2^k / 14k` flops per `k`× of bandwidth saved. On an M5 Pro the
fused path is up to 3.2× *slower* than the specialised 2×2 kernel. That kills fusion as a free
software win and simultaneously sharpens the hardware case: the engine is worth building exactly
when its flop-to-byte balance beats that exchange rate, and not otherwise.

See the [assessment](https://oluies.github.io/brokkr/) for the numbers, the capacity ceiling
(which no amount of silicon fixes), the proposed architecture and the staged plan.

## Reproducing the measurements

The fusion pass and the algorithm vectors live in
[Scotty](https://github.com/oluies/scotty) under `scotty.simulator.fusion` and
`scotty.algorithm`. From that checkout:

```bash
sbt test                                                    # 197 tests
sbt 'Test/runMain scotty.simulator.fusion.FusionBench 22'    # brickwork, 22 qubits
sbt 'Test/runMain scotty.simulator.fusion.FusionBench grover 10 4'
```

## Related

- [scotty](https://github.com/oluies/scotty/tree/pctc) — the simulator, and the golden model any
  hardware here would be verified against.
- [pctc](https://github.com/oluies/pctc) — noisy post-selected closed timelike curves; the workload
  that raised the question.
- [OpenXiangShan/CUTE](https://github.com/OpenXiangShan/CUTE) — the thing this is modelled on.

---

© 2026 Örjan Lundberg ·
[GitHub](https://github.com/oluies) ·
[LinkedIn](https://www.linkedin.com/in/orjanlundberg/) ·
Source: [github.com/oluies/brokkr](https://github.com/oluies/brokkr) ·
Built with [Scala 3](https://www.scala-lang.org), [Chisel](https://www.chisel-lang.org) &
[Mill](https://mill-build.org) ·
Apache-2.0
