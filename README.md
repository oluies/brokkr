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
- `rtl/` — Chisel: `ChannelEngine` and `ComplexGemm` (the density-matrix engine, which the
  measurements point at),
  plus the state-vector path — `AddressGen`, `DiagonalKernel`, `MonomialKernel` (with a Benes
  network) and `DenseKernel` — each verified against Scotty under Verilator;
- two side modules outside the engine, `MajorityVote` and `RecursiveMajority`: the repetition-code
  decoders from pctc's cat-qubit side note, [below](#side-modules-the-repetition-code-decoders);
- `vectors/` — the Scala 3 generator that produces golden vectors from Scotty.

The gate-fusion pass that motivated all of it lives in
[Scotty](https://github.com/oluies/scotty/tree/pctc) (`scotty.simulator.fusion`) rather than here,
because it is a simulator optimisation in its own right.

## Building the RTL

```bash
./mill rtl.test                                      # datapaths against Scotty's vectors, decoders exhaustively
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

### The schedule

```bash
./mill model.runMain brokkr.model.Schedule
```

That bound is per *slice*, and slices are independent: one whose addresses pile into a single bank
leaves the others idle for the next slice to use. Bandwidth in accesses per cycle against reorder
window depth, 32 banks, width-6 blocks, each mapping on the support it handles worst:

| mapping | d=1 | d=2 | d=4 | d=8 | d=16 | d=32 | banks reachable |
|---|---|---|---|---|---|---|---|
| low bits | 1.0 | 2.0 | 4.0 | 8.0 | 16.0 | **32.0** | 32 of 32 |
| xor fold | 4.0 | 8.0 | 16.0 | **32.0** | 32.0 | 32.0 | 32 of 32 |
| distinct | 8.0 | 12.0 | 16.0 | 16.0 | 16.0 | 16.0 | **16 of 32** |
| ext. Hamming | 16.0 | 19.1 | 27.3 | **32.0** | 32.0 | 32.0 | 32 of 32 |

So the naive mapping's 13.6× is a **latency** penalty, not a bandwidth one — it buys its way out
with buffering, at depth 32 against extended Hamming's 8. A slice in flight carries 64 outstanding
accesses, so that is 2048 against 512: four times the queue for the same bandwidth.

**A mapping must be checked for coverage, not only for conflicts.** `distinct` is capped at half
the bandwidth however deep the window, because its columns are the values 1 to 14 and none sets the
fifth bank bit — the upper sixteen banks are never addressed. The per-slice model rated it among
the better mappings (1.27× the floor at k=6) and could not see this.

### The arbiter

```bash
./mill model.runMain brokkr.model.Arbiter
```

That schedule allowed any pending access to be served in any order — unlimited reordering and
unlimited queues. Adding finite per-bank queues, a finite issue width and in-order issue:

| mapping | in order | look 8 | look 32 | look 256 | ceiling |
|---|---|---|---|---|---|
| low bits | **1.1** | 1.2 | 2.1 | 9.7 | 32 |
| xor fold | 5.3 | 5.8 | 10.3 | 29.4 | 32 |
| distinct | 9.6 | 9.6 | 11.3 | 16.0 | 32 |
| ext. Hamming | **18.2** | 18.2 | 20.1 | 31.7 | 32 |

This takes back the previous section's conclusion. Low bits does *not* buy its way to full
bandwidth: with depth-4 queues it reaches 9.7 of 32 even at a lookahead of 256. Each slice sends all
`2^k` accesses to one bank, so the issue window must reach past an entire slice — 64 requests —
before it finds work for an idle bank. Queue depth barely helps (q=1→16 moves low bits 2.0→2.9,
extended Hamming 17.4→31.4).

**In order, with modest queues, the bank mapping is worth 16× in bandwidth** — 1.1 against 18.2.
That is the number to design against.

### Integration

```bash
./mill model.runMain brokkr.model.Integration
```

Three remaining questions, two of which need arithmetic rather than simulation.

**Deeper queues buy a great deal; a request network, as far as this can tell, buys nothing.** Those
are different claims and earlier versions of this README conflated them. The sweep varies per-bank
queue depth with the generator strictly in order:

| mapping | d=4 | d=16 | d=64 | d=256 | ceiling |
|---|---|---|---|---|---|
| low bits | 1.1 | 1.3 | **28.5** | 28.5 | 32 |
| xor fold | 5.3 | 25.6 | 31.1 | 31.1 | 32 |
| distinct | 9.6 | 15.9 | 15.9 | 15.9 | 32 |
| ext. Hamming | 18.2 | **30.2** | 31.4 | 31.4 | 32 |

The shape is the burst length: a slice sends all `2^k` accesses to however many banks the mapping
spreads it across, and an in-order generator reaches the next slice only once the current one fits.
**Low bits needs a queue the size of a whole slice**; a mapping that spreads is most of the way
there by depth 16.

Depth is one lever, bypass is the other, and they substitute to a degree — extended Hamming reaches
about 30 either by depth 16 with no bypass or by depth 4 with a lookahead of 256.

*Retracted:* this section twice carried a conclusion about interconnects its experiment did not
support — first that a network buys almost nothing, then the opposite. Neither comparison isolated
what it claimed. The question is posed properly below.

### Where the buffer budget belongs

```bash
./mill model.runMain brokkr.model.Budget
```

A design has some number of request slots and two ways to organise them: **partitioned** across
banks, where a slot only serves its own bank, or **pooled** in a reorder window, where any slot
holds any request but a blocked one occupies it until its bank drains. Asking it this way needed no
new model — `Arbiter` already *is* a bypassing generator with per-bank queues (`occupancy` is the
queues, the window is the reorder buffer, `lookahead` is the bypass distance). What was missing was
a comparison at equal storage.

| mapping | budget | pooled (d=1) | split (d=4) | partitioned |
|---|---|---|---|---|
| low bits | 256 | **7.9** | 5.4 | 5.4 (d=4) |
| low bits | 512 | **15.4** | 13.9 | 11.0 (d=8) |
| low bits | 2048 | **31.9** | 31.1 | 28.9 (d=32) |
| ext. Hamming | 256 | **29.4** | 25.4 | 25.4 (d=4) |
| ext. Hamming | 512 | **31.9** | 31.7 | 31.4 (d=8) |

**Pooled wins at every budget and mapping**, structurally: a bank drains one request per cycle, so a
queue deeper than one buys nothing unless the generator cannot refill it — and a generator with a
reorder window and the freedom to bypass always can. Depth absorbs bursts that bypass has already
absorbed. The instinct to reach for per-bank queues was backwards; the slots belong in the window.

**What the bank mapping buys is how small that window can be.** Extended Hamming reaches 31.9 of 32
on 512 slots; low bits reaches 15.4 on the same budget and needs **2048 — four times the storage —
to match it**. That is the mapping's value in the currency a designer spends.

**Operator traffic is not in contention.** A block loads its operator once and streams the whole
state past it. A dense `4^k` operator against `2^n` amplitudes is 0.39% of traffic at n=20, k=6 and
0.02% at n=24, k=6. It only matters when the state is small enough to be comparable to the operator
(50% at n=12), which is the regime with nothing to accelerate.

### Refill

```bash
./mill model.runMain brokkr.model.Refill
```

Above the capacity line the state works in tiles, and a block applies to a tile in isolation only if
every qubit it touches is **local** — inside the address bits the tile spans. Otherwise the state
must be re-tiled, costing a full traversal of off-chip memory each way. So the figure of merit is
**passes**, not cycles.

The form lattice returns in a third role: **a diagonal block streams**, scaling each amplitude
independently, so it applies to a tile whatever its support. Grover's all-qubit controlled `Z` —
the worst case for the fusion pass — is free here. Monomial and dense blocks need their support
local.

| workload | blocks | streaming | local | passes | no lookahead | off-chip traffic |
|---|---|---|---|---|---|---|
| brickwork | 61 | 16 | 20 | 11 | 16 | 22× the state |
| Grover | 65 | 4 | 20 | 18 | 25 | 36× |
| diagonal-heavy | 43 | 39 | 16 | **1** | 3 | **2×** |

An ordinary circuit that spills moves **20–44× the state** through external memory, at which point
the on-chip datapath is not the bottleneck and the engine has given away its advantage. Capacity
helps less than expected — 16 to 22 local qubits takes brickwork only from 14 passes to 10, because
what forces a re-tiling is the circuit's *structure*, not the shortfall in room. Scheduling helps
more: a 64-block lookahead nearly halves the passes.

A circuit of phases and permutations needs one pass. **The engine is for circuits that fit, or
circuits that are mostly diagonal.**

### Overlapping the re-tiling

```bash
./mill model.runMain brokkr.model.Overlap
```

Those passes need not be stalls. Double buffering turns a sum into a maximum, worth 10–47%. The
interesting one is **folding**: a re-tiling already streams every amplitude past the engine, so a
block can be applied as the data goes by. How much a pass carries is the ratio of on-chip to
off-chip bandwidth.

| off-chip rate | blocks per pass | brickwork folded | Grover folded |
|---|---|---|---|
| 1 | 32 | 100% | 100% |
| 4 | 8 | 100% | 100% |
| 8 | 4 | 72% | 100% |
| 16 | 2 | 36% | 55% |

While off-chip delivers at most about an eighth of the on-chip rate, **a circuit's entire
computation folds into its transfer passes**. The cost of a spilled circuit is then exactly its
pass count — not the fusion width, not the bank mapping, not the datapath. Above the capacity line
the engine should be designed to minimise re-tilings, and its arithmetic is free.

*Caution:* the 32-amplitudes-per-cycle on-chip figure is this project's banking model, which chose
32 banks. And off-chip is not obviously slow — HBM at a few TB/s is, at a GHz, well over a hundred
fp64 complex amplitudes per cycle. The model establishes the crossover, not which side to expect.

### What the host costs

```bash
./mill model.runMain brokkr.model.DescriptorCost
```

Every block must be built before it is applied, and that runs on the CPU. Applying a block touches
every amplitude once, so its cost grows with the *state*; building a dense one is a matrix product
per gate fused, `O(8^k)`, which grows with the *block* and not with the state at all.

| width | fuse per block | engine per block | ratio | bound by |
|---|---|---|---|---|
| 2 | 8.8 µs | 65.5 µs | 0.13× | engine |
| 4 | 15.7 µs | 65.5 µs | 0.24× | engine |
| 5 | 24.0 µs | 65.5 µs | 0.37× | engine |
| 6 | **116.5 µs** | 65.5 µs | **1.78×** | **host** |

Benes routing is not the problem — 1.7 µs at 8 lanes to 5.7 µs at 64, i.e. 0.03–0.09× engine time.
Operator construction is the whole of it.

**This is a third constraint on the fusion width, and the tightest.** Intensity wanted k=6–8 and
storage allowed it; the host does not. At 20 qubits k=6 is host-bound. The crossover moves with the
register (k=6 becomes engine-bound at n≥21) — but 20 qubits is about where a 16 MiB on-chip store
runs out, so **the sizes at which the engine is host-bound are exactly the sizes at which it fits on
chip**, which the refill model says is the only place it wins.

The way out is modest: **design at k=5**, comfortably engine-bound at 0.37×, still 6.7 flop/byte.

Caching does not rescue k=6: measured operator reuse is 1.2× (brickwork), 3.1× (Grover), 1.1×
(diagonal-heavy). A brickwork layer varies its angles and repeats almost nothing.

*Caveats:* this times *this* fusion pass, written for clarity and never optimised, on one core. A
tuned implementation could move the crossover several times. The structure survives — host cost is
flat in the state and the engine's is not — but the crossover point would not.

**Writeback halves everything, and need not.** Each amplitude is written back to the same address,
hence the same bank, so traffic doubles with an identical distribution — extended Hamming goes
31.4 → 15.7. But
the write targets the address the read just used, so a read-modify-write memory recovers the whole
factor of two — a memory feature, not an arbiter one, and the largest lever left in the memory
path.

### The crossbar

```bash
./mill model.runMain brokkr.model.Crossbar
```

Lane `l` holds the amplitude at `base + offset(l)`, and `offset` is linear in `l`, so
`bank(l) = L(base) ^ L(offset(l))` is **affine** in the lane index, with linear part equal to `L`'s
columns at the support. Classifying every gather over every support and all 1024 slices confirms it: a gather is affine, or
it conflicts and is not a permutation of lanes at all. Nothing is ever arbitrary — against 58.8% for
the operator permutations. The classifier carries an `Arbitrary` case that stays empty, so the claim
is one it could have refuted.

But bit permutations are nearly absent (0.00–0.90%), so Omega is not enough here either. The address
stride *is* a bit permutation; the bank mapping XORs bits together on the way, and that is exactly
what makes the gather conflict-free. The two are coupled: **what fixes bank conflicts is what stops
the crossbar being a bit permutation.** Since a Benes network measured at 1.4% of a datapath, the
answer is to spend it.

### Why a Benes network, not an Omega network

`PermutationStudy` fuses randomised circuits and classifies the permutations monomial blocks
contain: 7.7% are bit permutations, 33.5% are affine over GF(2), and **58.8% are arbitrary** —
Toffoli and Fredkin generate permutations that are not affine. An Omega network is blocking and
routes bit permutations natively, so it could not serve most blocks. A Benes network routes every
permutation in `(2k-1) * 2^(k-1)` switches, with settings computed by the host once per block.

Note this is a different shuffle from the memory-side one — but both need more than an Omega
network, for different reasons. The address *stride* is a bit permutation of the index, yet composed
with the bank mapping that makes gathers conflict-free it becomes affine, not a bit permutation.
Routing the operator's own permutation is harder still: usually arbitrary.

Regenerating the golden vectors needs Scotty 0.7.0 published locally from its release tag — **both**
to Ivy and to M2, since Mill resolves from `~/.ivy2/local` first and a stale copy there will silently
shadow a fresh one in `~/.m2`:

```bash
cd ../scotty && git checkout v0.7.0 && sbt publishLocal publishM2
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

## Where it ends up

A state-vector engine is **not** CUTE: it needs a shuffle fabric, three datapaths and a bank mapping
derived from coding theory, none of which a convolution engine has. A **density-matrix** engine is a
complex GEMM machine, which is very nearly CUTE already — and measured against the same roofline it
reaches **683 flop/byte at ten qubits** where a fused state vector reaches 15.9, while fitting on
chip, needing no fusion pass, and having no banking problem.

| qubits | ρ | flop/byte | vs unfused state vector | vs best fused |
|---|---|---|---|---|
| 8 | 1 MiB | 171 | 388× | 11× |
| **10** | **16 MiB** | **683** | **1 551×** | **43×** |
| 11 | 64 MiB | 1 365 | 3 103× | 86× |

The capacity ceiling is hard and low — eleven qubits, since `4^n` grows fast — so this is an engine
for small noisy systems rather than for scale. And if the kernel is complex GEMM, the
quantum-specific contribution is thin: the value would be in the compiler and verification work
rather than the datapath. Less exciting than a novel architecture, and much more defensible.

**`ChannelEngine` applies a full Kraus channel**, checked against Scotty across three channels and
three random states each — depolarizing, amplitude damping and dephasing, with Kraus counts of 4, 2
and 2. Worst over all nine cases: deviation **9.9e-07**, trace error **2.9e-06**, Hermitian
asymmetry **1.4e-06**. The last two are properties of the physics rather than of the reference — a
transposed index or a dropped conjugation would break them even if an element-wise comparison
somehow passed.

The datapath is checked at depths 4, 8, 16 and 32, with deviation flat at 2.1–2.9e-07. **Eight times
the depth costs nothing**, because the accumulator is widened and narrowed only on the way out —
which is what licenses running the same design to the depth `2^n` a ten-qubit channel needs.

**Tiling.** The dimension is independent of the array: the engine loops over `(dimension/tile)²`
output blocks, each accumulating over the full inner dimension. Measured at tile 4:

| dimension | blocks per product | cycles per Kraus operator |
|---|---|---|
| 4 | 1 | 10 |
| 8 | 4 | **72** |

Both are `2·(N/T)²·(N+1)`, so cycles grow as `N³/T²` — the arithmetic of the product, not control
overhead. This is also what makes the widened accumulator load-bearing: a block's accumulation is
now `dimension` deep, not `tile` deep.

**The limit moved and became a different one.** The array stays 322 347 cells whatever the
dimension; the engine around it was 6% of that at dimension 4 and is **24% at dimension 8**, because
the state, Kraus set, intermediate and accumulator all grew fourfold while the multipliers did not.
That share is mostly address muxing on register files — so **the next change is storage, not
control**. At ten qubits ρ alone is 16 MiB: preposterous in registers, ordinary in SRAM, and the
control above it would not change.

**Storage: the banking problem returns, kindly.** A tiled product reads a *column slice* (`tile`
rows at one column) and a *row slice* (one row at `tile` columns) each cycle. Bank by row and the
column slice spreads while the row slice collides; bank by column and the reverse. The classical
answer is to skew: `bank = (row + col) mod tile`, so each slice walks the banks one step at a time
and never repeats one. `SkewedStore` implements it; the spec checks every slice of both shapes.

That is the same *shape* of result as the state-vector bank mapping — make a strided access
conflict-free by choosing where addresses land — and far simpler, because a GEMM stride is regular
where a qubit stride is a bit permutation needing a code of distance `k+1`. The contrast is most of
the argument for the density-matrix engine in one sentence.

| store | cells | flops | addressing logic |
|---|---|---|---|
| registers, four read muxes | 113 201 | 12 480 | 100 721 |
| skewed banks | **31 627** | 12 322 | **19 305** |

**5.2× less addressing logic**, 3.6× fewer cells. The flop counts are near-identical by design: this
synthesis has no SRAM library, so both store their bits in flip-flops and the whole difference is in
how they are reached. Real memory macros would widen it, but that is not measured here.

The engine now runs on it. Reads are synchronous, so addresses issue a cycle ahead and each pass
runs `dimension + 1` cycles; and a `tile × tile` block spreads its write over `tile` cycles, one
block row at a time — which uses the skew in the other direction, since a block row touches every
bank exactly once for the same reason a row slice reads from every bank once.

| storage | cells | over the array | cycles per Kraus operator |
|---|---|---|---|
| registers | 426 119 | +103 772 (24%) | 72 |
| skewed banks | **384 436** | **+62 089 (16%)** | 104 |

**40% off the non-datapath cost, for 44% more cycles.** That is the trade: the address muxing goes
away and the synchronous read plus multi-cycle block write buy it back in time. At ten qubits, where
ρ is 16 MiB, registers are not an option at all and the question does not arise.

The numbers are unchanged across the move — 1.1e-06, 5.7e-06, 1.9e-06 over the same cases.
A storage change that altered the arithmetic would be a bug.

**Rectangular channels: erasure.** A Kraus operator does not have to be square, and the channel that
forces the point is one `pctc` uses. `erasure` replaces the input with a flag state on a third basis
element, so it maps a qubit to a qutrit: `K` is 3×2, and ρ, `K` and the result are three different
shapes. For as long as the engine carried one dimension, this was a stated gap. It now carries two:

```
ρ          inputDim  × inputDim
K          outputDim × inputDim
T = Kρ     outputDim × inputDim
R = TK†    outputDim × outputDim
```

Both products accumulate over `inputDim`, and the *only* thing that differs between them is the
block-column count — `inputDim / tile` for the first, `outputDim / tile` for the second. The
separation therefore costs one argument in the tile walk and nothing else in the control. Neither
dimension has to be a power of two: lifting erasure over more qubits gives 4→6 and 8→12, both whole
numbers of tiles exactly. The bare one-qubit case is not — three is not a multiple of any array width
above one — so its output is zero-padded to four, which is exact rather than approximate, since a
zero row of `K` contributes nothing to either product.

| case | shape | tile | blocks per operator | cycles per operator |
|---|---|---|---|---|
| erasure, 1 qubit | 2→3, padded to 2→4 | 2 | 2 + 4 | 34 |
| square, 2 qubits | 4→4 | 4 | 1 + 1 | 19 |
| erasure, 2 qubits | 4→6 | 2 | 6 + 9 | 114 |
| square, 3 qubits | 8→8 | 4 | 4 + 4 | 108 |
| erasure, 3 qubits | 8→12 | 4 | 6 + 9 | 204 |

Blocks are `(outputDim/tile)·(inputDim/tile)` for the first product plus `(outputDim/tile)²` for the
second, costing `inputDim + 1 + tile` and `inputDim + 2 + tile` cycles respectively — the second
product's write goes into the accumulator, which is read-modify-write, so it runs a cycle longer.
The spec **asserts** that count rather than reporting it: pinning the schedule is what localised the
clear-too-early bug to the datapath, since the sequencing was provably right.

The storage scheme needed no change at all. `bank = (row + col) mod tile` with offset
`row · (cols/tile) + col/tile` is still a bijection when rows ≠ cols — the offset fixes the row and
the column's tile, and the bank then fixes the column within it.

| engine | shape | cells | over the array |
|---|---|---|---|
| `ChannelEngine` | 8→8, 4 operators | 378 385 | +56 038 (15%) |
| `ErasureEngine` | 8→12, 3 operators | 395 924 | +73 577 (19%) |

**+4.6% for a 1.5× wider output**, and the array inside is the same 322 347 cells in both. The extra
is a 144-cell accumulator instead of 64 and 1.17× the operand storage; control is flat. (The square
engine's own figure moved 384 449 → 384 436 when the load port became an explicit row, column and
copy rather than one packed address split by bit position — a row index into a 12-row operator is not
a bit field — and then to 378 385 when the accumulator moved into memory, below.)

**The last register file: the accumulator.** Everything else had moved to banked memory; the
accumulator had not, because it is read a block at a time and written a block at a time and so costs
no read muxing. It was still the single largest thing left outside memory, and at 8→12 it was 144
cells of 27 bits with four read-modify-write ports over them.

It is now a `SkewedStore` too, with three consequences. The write runs a cycle behind the read, so
the second product's write phase is `tile + 1` cycles. The result can no longer be one wide port — at
8→12 that was 6 912 bits of output — so it comes out a row slice at a time through the same
`readRow` the datapath uses. And the accumulator is the first store here that reads and writes in the
same cycle, at different addresses, which asks for a memory with separate ports rather than one
shared; this synthesis has no SRAM library and cannot price that difference, which is stated rather
than hidden.

It also no longer needs clearing at all. **The first Kraus operator writes its result and every
later one adds to it**, which removes an `outputDim · (outputDim/tile)`-cycle zeroing pass — 16 cycles
at 8→8, 36 at 8→12 — and the first operator's read along with it. The accumulator is never
initialised.

| engine | accumulator | cells | over the array | flip-flops | cycles/op |
|---|---|---|---|---|---|
| `ChannelEngine` 8→8 | registers | 384 436 | +62 089 (16%) | 23 079 | 104 |
| `ChannelEngine` 8→8 | **banked** | **378 385** | **+56 038 (15%)** | 23 105 | 108 |
| `ErasureEngine` 8→12 | registers | 412 491 | +90 144 (22%) | 29 998 | 195 |
| `ErasureEngine` 8→12 | **banked** | **395 924** | **+73 577 (19%)** | 30 032 | 204 |

**10% off the non-datapath cost square, 18% rectangular, for 4% more cycles.** And the flop counts
are flat to within 34 — which is the whole point, and the same result as `SkewedStoreTop` against
`RegisterStoreTop` arriving a third time: with no SRAM library both forms store the same bits in
flip-flops, so every cell saved is addressing logic rather than storage. A flow with real memory
macros would widen it; that is not measured here.

The gap between the square and rectangular engines narrowed with it, from +28 055 cells to +17 539:
most of what a wider output cost was its register file. The emitted SystemVerilog says the same
thing more bluntly — `ErasureEngine.sv` went from 271.8 KiB to 60.7 KiB, and the two engines now emit
almost exactly the same volume of Verilog.

**A measurement that had to be fixed first.** Two configurations of the same generator exposed a
hazard in how area is measured here. `area.sh` already read every file a generation emitted, because
firtool puts submodules in their own files and reading the top alone leaves them as blackboxes. The
other half is that submodule names depend only on shape: both engines emit a `mem_16x24.sv` with
different port structures, and both emit a `ComplexGemm.sv`, so in one shared `build/` the later
generation silently overwrites the earlier one's submodules. Generation now goes to `build/<Module>/`
and synthesis reads only that directory. Re-measuring everything reproduced every published figure
except one: **`SkewedStoreTop` is 31 627 cells, not 31 520**, and its addressing logic 19 305 rather
than 19 198. The flop count was already right, the ratios are unchanged at 5.2× and 3.6×, and
nothing concludes differently — but the number was wrong and the table above now has the right one.

Erasure brings one check that does not depend on the golden model at all. The flag state is basis
element 2 of the output, and erasure sends weight there that the input had nowhere to put, so the
corner of the result is the erasure probability — 0.35, whatever the state was. The spec asserts it,
along with the padding coming back exactly zero.

So: **verified at dimensions 4 and 8 over four channels and twenty-four cases, square and
rectangular, tiled, with every store in banked memory** — state, operators, intermediate and
accumulator, nothing in a register file. Worst deviation 1.1e-06, and neither the rectangular cases
nor the accumulator's move changed it by a bit.

At dimension 4 the whole engine costs 342 351 cells against `ComplexGemm`'s 322 347, so **storage,
sequencing and accumulation are 6%** on top of the multiplier array at that size — the 15% and 19%
above are the same overhead at dimension 8, square and rectangular.

`ComplexGemm` is the datapath under it: a tile×tile array of complex accumulators fed one column of
A and one row of B per cycle. Its deviation from Scotty is 2.8e-07 over a depth-16 product, no worse than the
single-multiply kernels, because the accumulator is widened and narrowed only on the way out.

| module | cells | complex MAC/cycle | cells per MAC |
|---|---|---|---|
| `DiagonalKernel` | 20 775 | 1 | 20 775 |
| `MonomialKernel` | 159 287 | 8 | 19 910 |
| `DenseKernel` | 169 577 | 8 | 21 197 |
| `ComplexGemm` | 322 347 | 16 | **20 146** |
| `ComplexGemm`, SpinalHDL | 324 390 | 16 | 20 274 |
| `ChannelEngine`, dim 4 | 342 351 | 16 | — (+6% control) |

**All four land within 6% of each other at about 20 000 cells per complex MAC.** Every
architectural difference — phase tables, Benes networks, wide accumulators — is noise against the
multipliers. So silicon per MAC is the same whichever engine you build; what differs is how much
useful work a MAC does. Same cost, 43× the work.

### The fifth row is a control, not another datapath

All four of those came out of one generator, so the result could have been a fact about Chisel and
firtool rather than about arithmetic. **SpinalHDL** is an independent Scala HDL with its own Verilog
backend — no FIRRTL, no CIRCT — so `brokkr.spinal.ComplexGemm` is the same kernel written again in
it, synthesised by the same `scripts/area.sh`, and it tests exactly that.

| | Chisel | SpinalHDL |
|---|---|---|
| cells | 322 347 | 324 390 |
| flip-flops | 1 472 | 1 472 |
| ports / port bits | 52 / 1 156 | 52 / 1 156 |
| cells per complex MAC | 20 146 | 20 274 |

**0.63% apart, and the state is identical.** The whole difference is combinational — the two
backends build the multiply and add trees slightly differently before `abc` sees them, trading 1 222
NANDs for 1 150 ANDs and 938 XNORs. Nothing about the architecture moved, which is the claim.

Both backends also independently discovered the same optimisation, having been told nothing: the top
13 bits of each 59-bit accumulator cannot affect a truncated 24-bit output, so each is 46 flops, not
59. **32 × 46 = 1 472 in both.**

Comparing areas only means something if the two compute the same function, so that is checked
directly rather than assumed. `scripts/equivalence.sh` puts both netlists in one Verilator
simulation, drives them from identical **full-range** random stimulus — not the golden vectors, whose
small amplitudes never reach the interesting cases — and compares every cell every cycle:

```
compared 50688 values over 48 trials of depth 32
PASS GemmEquivalence: Chisel and SpinalHDL agree bit for bit
```

That is a stronger result than it looks. The place I expected them to diverge was rounding:
SpinalHDL's `SFix(1 exp, -22 exp).truncated` against Chisel's hand-written
`(acc >> fractionBits)(dataWidth-1,0).asSInt`. They agree exactly, including on negatives and on
wraparound of the complex subtract.

The ergonomic difference is real even though the area difference is not. In the Chisel version the
accumulator width is asserted — `2 * dataWidth + log2Ceil(maxDepth) + 1` — and `Fixed.scala` is
three integer functions with the discipline left to each kernel. In the SpinalHDL version the format
is in the type, the width *falls out* of the arithmetic as a peak exponent of
`2 * (dataWidth - fractionBits) + log2Up(maxDepth)`, and the compiler refuses any assignment that
would move the binary point: `.truncated` has to be written. Both give 59 bits. **The Q1.23 bug —
a coefficient of exactly 1 wrapping to −1 — is that class of error, and it would have been a compile
error here.**

One correction to the way the two are usually contrasted, since it came up: **both are Scala.**
Neither is a separate language, in both elaboration *is* a Scala program, both ship a scalac plugin
to recover signal names from `val` declarations, and **neither is published for Scala 3** — Maven
Central has `chisel_2.12`/`chisel_2.13` and `spinalhdl-core` for 2.11/2.12/2.13. So `rtl` is pinned
to 2.13 while `vectors` and `model` run Scala 3.9, either way.

The difference is the back half of the pipeline. Chisel elaborates to FIRRTL and hands off to
`firtool`, a 27 MB native LLVM/MLIR compiler it downloads and version-matches itself; SpinalHDL's
backend is Scala and emits Verilog in the same JVM run. "SpinalHDL uses Scala directly, Chisel
rewrites" is right about the toolchain and wrong about the source. The per-cell body of the two
kernels is identical *character for character* apart from the assignments that touch the binary
point.

That extra stage has already cost something nameable here: `Generate.scala` passes
`--lowering-options=disallowLocalVariables,disallowPackedArrays` solely because CIRCT emits
`automatic logic` inside `always` blocks and Yosys rejects it. Nothing in the Chisel source asks for
that. The SpinalHDL output needed no flag.

None of which argues for a port. CUTE is Chisel, on rocket-chip and Utility, and the whole premise is
similarity to it; answering in another HDL gives that up for 0.63%. One kernel at one tile size
through one gate-level flow with no liberty file is a fifth data point, not a study — but it is the
data point that was missing, and it says the per-MAC number is about multipliers rather than about
Chisel. Section 11 of the page keeps the full evaluation; the one thing worth taking from SpinalHDL
is not SpinalHDL but **a Q-format type with the point in it**.

### Which is now taken

`QFormat` and `Q` are in `Fixed.scala`, and all four datapaths are written in terms of them.
`QFormat(1, 23).requireRepresentable(1.0)` is an elaboration error — the test output shows why Q1.23
looked fine, since it holds `[-1, 0.99999988]`, −1 exactly and +1 not at all. The 59-bit accumulator
is derived rather than asserted: Q2.22 × Q2.22 → Q4.44, subtract → Q5.44, ×1024 → Q15.44. And `to`
is the only way to move the point, so a dropped or doubled shift is a type error.

| kernel | by hand | with the type | delta |
|---|---|---|---|
| `DiagonalKernel` | 20 775 | 20 775 | 0 |
| `MonomialKernel` | 159 287 | 159 287 | 0 |
| `DenseKernel` | 169 577 | 169 579 | +2 |
| `ComplexGemm` | 322 347 | 322 347 | 0 |

**Two cells on 672 586**, and no answer changed anywhere: `ComplexGemm` is still bit-identical to the
SpinalHDL netlist over the same 50 688 random values, the generated `DenseKernel` still matches
Scotty's vectors bit for bit under Verilator, and the exact-model specs pass at every depth with
unchanged deviations.

The +2 is not what I expected. `DenseKernel` summed eight 48-bit terms with `reduce(_ +& _)`, growing
a bit per addition to 55, where `accumulating(dimension)` computes 52 — so the type should have saved
area. It saved nothing, because **firtool had already narrowed both to 46 bits**: the output takes
bits 45…22, so everything above was dead in the hand-written version too. The hand-written slack was
already free, and the +2 is the sum re-associating, not re-sizing.

The type did surface one thing. `a*c - b*d` was computed at 48 bits, Q4.44, where the subtract wants
Q5.44 and 49. It never overflowed — Q4.44 reaches ±8 and `|ac - bd| < 8` strictly, the bound being
tight only because +2 is not representable in Q2.22. Correct by the thinnest available margin, and by
accident rather than by argument.

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

## Side modules: the repetition-code decoders

Neither is part of the engine. They are the hardware half of pctc's
[repetition-code side note](https://oluies.github.io/pctc/#repetition-heading), on why cat qubits
need no bit-flip protection: the phase-flip code stores a logical value in `n` qubits, measures each
in the `|+>`, `|->` basis and decodes by majority.

- `MajorityVote(n)` is the exact decoder: a population count and a comparison with `n / 2`.
- `RecursiveMajority(levels)` is the cheaper one: the majority of majorities of three, a tree of
  `ab | bc | ca`, over `3^levels` copies.

Their golden model is a theorem rather than Scotty. pctc proves in Lean what each corrects, and the
specs check it on every input: `MajorityVoteSpec` for `n` up to 9 (`majority_corrects`,
`majority_fails`), `RecursiveMajoritySpec` on all 512 inputs at nine copies
(`recursiveMajority_corrects`, `recursiveMajority_fails`), pinning its failures to exactly the 27 of
the 126 four-flip patterns with two flips in each of two groups.

| module | copies | cells | corrects | fails at `p = 5e-4` |
|---|---|---|---|---|
| `MajorityVote3` | 3 | 5 | 1 flip | 7.5e-7 |
| `MajorityVote5` | 5 | 12 | 2 flips | 1.25e-9 |
| `MajorityVote7` | 7 | 21 | 3 flips | 2.2e-12 |
| `MajorityVote9` | 9 | 38 | 4 flips | 3.9e-15 |
| `RecursiveMajority9` | 9 | 17 | 3 flips | 1.7e-12 |

Generic cells from `scripts/area.sh`, one of them the output flip-flop; failure rates from the
closed forms with each qubit flipped independently, the binomial tail for exact majority and
`3q^2 - 2q^3`, `q = 3p^2 - 2p^3`, for recursive. At nine copies the recursive decoder saves 55% of
the cells and fails about 430 times as often. Against exact majority on seven, which also corrects
three flips, it is a little better on both counts, 17 cells against 21 and 1.7e-12 against 2.2e-12,
but it needs two more qubits to get there. Next to the
datapaths' roughly 20 000 cells per complex multiply-accumulate, every one of them is negligible.

```bash
./mill rtl.test.testOnly brokkr.MajorityVoteSpec brokkr.RecursiveMajoritySpec
./mill rtl.runMain brokkr.Generate MajorityVote3 MajorityVote5 MajorityVote7 MajorityVote9 RecursiveMajority9
scripts/area.sh MajorityVote9 && scripts/area.sh RecursiveMajority9
```

## Related

- [scotty](https://github.com/oluies/scotty/tree/pctc) — the simulator, and the golden model the
  datapaths are verified against.
- [pctc](https://github.com/oluies/pctc) — noisy post-selected closed timelike curves; the workload
  that raised the question. It [runs in the browser](https://oluies.github.io/pctc/), compiled to
  JavaScript with Scala.js, and its `erasure` channel is why the engine here carries two dimensions.
  Its Lean proofs are the golden model for the decoders.
- [OpenXiangShan/CUTE](https://github.com/OpenXiangShan/CUTE) — the thing this is modelled on.

---

© 2026 Örjan Lundberg ·
[GitHub](https://github.com/oluies) ·
[LinkedIn](https://www.linkedin.com/in/orjanlundberg/) ·
Source: [github.com/oluies/brokkr](https://github.com/oluies/brokkr) ·
Built with [Scala 3](https://www.scala-lang.org), [Chisel](https://www.chisel-lang.org) &
[Mill](https://mill-build.org) ·
Apache-2.0
