# Fusion benchmark, 17 October 2026

First measurement of the greedy gate-fusion pass in `scotty.simulator.fusion`.

## Setup

- Apple M5 Pro, 18 cores, 48 GB
- OpenJDK 27 (Homebrew), Scala 3.9.0
- Scotty at branch `pctc`
- Workload: nearest-neighbour brickwork, 22 qubits, 6 layers of `H` + `RZ` with alternating `CNOT`
  rungs. 327 gates. 64 MiB state in fp64 complex.
- `sbt -J-Xmx6g 'Test/runMain scotty.simulator.fusion.FusionBench 22'`
- Best of 3 runs after 2 warmups.

## Result

```
width  blocks  flop/byte    flops  traffic  operator       ms   gate/s
 none     327       0.53     1.0x     1.0x         -    336.2      973
    2     195       0.94     1.0x     0.6x     256 B    459.4      712
    3     148       1.57     1.3x     0.5x     1 KiB    506.6      645
    4      98       3.91     2.2x     0.3x     4 KiB    442.1      740
    5      83       6.73     3.2x     0.3x    16 KiB    655.5      499
    6      65      15.93     5.9x     0.2x    64 KiB   1088.3      300
```

## Reading

Fusion behaves exactly as the roofline model predicts and still loses on a CPU, because the model
was answering the wrong question. Intensity rises because **flops rise faster than traffic falls**,
not because the work gets cheaper:

- traffic divides by roughly the fusion width `k`
- flops multiply by `8·2^k / (14k)` — 6.1× predicted at k=6, 5.9× measured

So fusion is an exchange rate between bandwidth and arithmetic. A CPU is already flop-rich relative
to this kernel, so paying 5.9× arithmetic for 5× less traffic is a loss at every width. A wide MAC
array with a banked scratchpad is the opposite, which is the actual case for the engine.

## Caveats, both favouring the hardware case

1. The greedy pass densifies operators that did not need densifying. `RZ`, `PHASE`, `CZ` and
   `CPHASE` are diagonal — one complex multiply per amplitude unfused, a full dense block fused.
   A sparsity-aware pass should cut the flop inflation substantially. This is the next thing to do.
2. The fused kernel is a generic `d × d` loop measured against a hand-specialised 2×2 one. Some of
   the gap is tuning rather than physics.

## Implementation note

The first version of the kernel allocated its gather buffers per slice — a quarter of a million
allocations per block at width 4 on 22 qubits — and was about 4× slower at width 6 than the chunked
version measured above. Worth remembering before reading any future regression as a finding.

## Addendum: Grover, and why dense-only is the wrong engine

Grover on 10 qubits, 4 iterations (capped; optimal is 25), 186 gates,
`FusionBench grover 10 4`:

```
width  blocks  flop/byte    flops  traffic  operator       ms   gate/s
 none     186       0.50     1.0x     1.0x         -      8.9    20821
    2      97       0.51     1.0x     1.0x    16 MiB     76.7     2426
    3      68       0.52     1.0x     1.0x    16 MiB     33.2     5607
    4      54       0.53     1.0x     1.0x    16 MiB     27.3     6819
    5      46       0.56     1.1x     1.0x    16 MiB     27.8     6701
    6      42       0.59     1.2x     1.0x    16 MiB     31.0     5994
```

Intensity barely moves and the fused path is 3-9x slower. The cause is that Grover's oracle and
diffusion operator both contain a controlled `Z` over *every* qubit. That gate is diagonal — one
sign flip per amplitude unfused — and densifying it produces a 16 MiB operator applied to a 16 KiB
state. Past 12 qubits the pass cannot represent the circuit at all; `FusedBlock.MaxDenseWidth`
refuses rather than exhausting memory.

Two conclusions, and the second is the one that matters for the architecture:

1. Sparsity-aware lowering is now the clear priority, not a nice-to-have.
2. A dense-only datapath is the wrong engine. Diagonal operators (multi-controlled `Z`, `RZ`,
   `PHASE`, `CPHASE`) are a large fraction of real circuits and want one multiplier and a phase
   table, not a MAC array. RTL that only does dense blocks would run Grover slower than the CPU it
   hangs off.

## Addendum 2: sparsity-aware lowering

Operators are now stored in the tightest of three forms, closed under composition:

| form | stores | flops/amplitude | datapath |
|---|---|---|---|
| Diagonal | 2^k | 6 | multiplier + phase table; no gather, scatter or shuffle |
| Monomial | 2^k | 6 | shuffle network, no multiplier array |
| Dense | 4^k | 8·2^k − 2 | MAC array |

Plus a `NativeOp` escape: gates wider than the fusion budget are not lowered at all, since the
simulator applies an all-qubit controlled gate in one pass.

### Brickwork, 22 qubits (model columns, deterministic)

```
width  blocks  flop/byte    flops  traffic  operator
 none     327       0.34     1.0x     1.0x         -
    2     195       0.44     0.8x     0.6x     256 B
    3     148       0.76     1.0x     0.5x     1 KiB
    4      98       1.57     1.4x     0.3x     4 KiB
    5      83       3.18     2.4x     0.3x    16 KiB
    6      65       6.49     3.8x     0.2x    64 KiB

form mix at width 6: 26 Dense, 18 Diagonal, 21 Monomial, 0 native
```

Flop inflation at width 4: 2.2x -> 1.4x. At width 6: 5.9x -> 3.8x. At width 2 the fused circuit now
does *fewer* flops than unfused. The unfused modelled intensity drops 0.53 -> 0.34 because the model
now correctly recognises `RZ` as diagonal and `CNOT` as monomial.

### Grover, 14 qubits — previously not expressible above 10

```
width  blocks  flop/byte    flops  traffic  operator
 none     254       0.32     1.0x     1.0x         -
    2     123       0.56     0.9x     0.5x     256 B
    3      85       1.08     1.3x     0.4x     1 KiB
    4      63       2.08     1.8x     0.3x     4 KiB
    5      51       3.93     2.9x     0.2x    16 KiB
    6      43       7.18     4.8x     0.2x    64 KiB

form mix at width 6: 27 Dense, 16 Monomial, 8 native
```

Eight native ops — one per oracle and one per diffusion operator across four iterations, exactly as
designed. Largest operator 64 KiB against 16 MiB before: a 256x reduction.

### Timings, re-measured

The first attempt was unusable: load average 36 on 18 cores (an unrelated project's Python process
at 922% CPU) and the *unchanged* unfused baseline swinging between 336 ms and 2362 ms. Re-measured
at load average 5.9, best of 7 after 3 warmups. The baseline came back at 344 ms, consistent with
the 333/336 ms measured before the machine got busy, which confirms the outliers were interference.

```
# brickwork, 22 qubits
width    ms   gate/s  vs unfused
 none  344.2     950  -
    2  287.4    1138  1.20x faster
    3  260.2    1257  1.32x faster   <- best
    4  263.9    1239  1.30x faster
    5  365.8     894  1.06x slower
    6  544.5     601  1.58x slower

# Grover, 14 qubits, 4 iterations
 none   22.9   11081  -
    2   12.0   21110  1.91x faster
    3    9.4   26939  2.44x faster
    4    8.2   31012  2.79x faster   <- best
    5    8.8   28832  2.60x faster
    6   12.8   19898  1.79x faster
```

**This corrects the earlier conclusion.** "Fusion loses on a CPU at every width" was true of the
dense-only pass and is false in general. Form-aware fusion wins at widths 2-4 on both workloads and
only loses past width 5, where the added arithmetic finally outweighs the traffic saved.

Grover — the workload the dense pass handled *worst*, at 3-9x slower — gains the most, at 2.8x.
Not a coincidence: it has the highest proportion of cheap operators, so it had the most to gain from
not densifying them.

### Architectural consequence

The engine needs **three paths, not one**, in roughly a 2:2:3 diagonal:monomial:dense mix on the
workloads measured so far. Sizing a MAC array for 100% of blocks would waste most of it. The block
diagram in the assessment has not been redrawn for this yet.

## Addendum 3: the first RTL, and a precision number

Two Chisel blocks now exist, both verified against Scotty, neither requiring an HDL toolchain to be
installed — chiseltest interprets on the JVM and firtool arrives as an ordinary Maven dependency.

### AddressGen

The bit insertion that turns a slice number into a base amplitude address. Combinational, one stage
per supported qubit. Checked against all 592 exhaustive vectors for 6 qubits and supports up to 3
wide, plus the defining property independently: a base address never has a bit set at a supported
qubit.

### DiagonalKernel

The phase datapath: a `2^k` phase table, one complex multiplier, one amplitude per cycle, no gather,
no scatter, no shuffle. The first block whose output is not bit-exact against the golden model, so
it is checked twice over:

1. **Against an exact integer model of its own arithmetic** (BigInt, no tolerance). This answers
   "does the RTL do what the fixed-point spec says" without any fudge.
2. **Against Scotty's doubles**, which answers "what does the fixed-point spec cost".

Result, over a fused `RZ`/`CPHASE`/`T` block on support {0,2} at Q1.23:

```
worst deviation from Scotty over 16 amplitudes: 1.943e-07
```

One quantisation step is `2^-23` = 1.19e-7, so the complex multiply costs about 1.6 steps. **Fixed
point is not disqualifying**, which was the open question. It is one block on one circuit, though —
error accumulates over a circuit's depth and nothing here measures that yet.

### Area, concretely

CIRCT emits the complex multiplier as **four real `24x24` products** (`ac`, `bd`, `ad`, `bc`) into
one add and one subtract, with the 47-bit products truncated at `[46:23]`. Against the dense path's
`2^k`-wide multiply-accumulate array, that is the whole argument for keeping the three forms apart:
at k=6 the dense path needs a 64-wide complex MAC array where the diagonal path needs four
multipliers.

### Interop, settled

`-Ytasty-reader` does not work: Scala 2.13.15 resolves Scotty's package and then fails on every type
with `class file Complex.tasty is broken`. The golden model reaches the testbench as a generated
file instead, which is what a C++ difftest harness or an FPGA bring-up would use anyway.

A trap worth recording: **Mill resolves a SNAPSHOT from `~/.ivy2/local` before `~/.m2`**, so a stale
`sbt publishLocal` silently shadowed a fresh `publishM2Local`, and the fusion package appeared not to
exist at all. Publish to both.

## Addendum 4: the permute path, and two corrections

### An Omega network cannot route the permute path

`PermutationStudy` fuses 400 randomised circuits on 6 qubits and classifies the permutations that
monomial blocks contain:

```
  Identity              0    0.0%
  BitPermutation      108    7.7%
  Affine              472   33.5%
  Arbitrary           829   58.8%
```

Toffoli and Fredkin generate permutations that are not affine over GF(2), so this is what a real
gate set produces. An Omega network is blocking and routes bit permutations natively — 7.7% of
blocks. A **Benes network** is rearrangeably non-blocking: every permutation, in `(2k-1) * 2^(k-1)`
switches, with the settings computed by the host once per block (the looping algorithm, in
`BenesRouter`, self-checked on 500 random permutations at each of sizes 2 through 32).

This is a *different* shuffle from the memory-side one. Moving amplitudes between lanes and banks is
a bit permutation set by the qubit support, and Omega suits it. The operator's own permutation is
not a bit permutation. Conflating the two is easy and was the mistake the earlier diagram made.

### Area, all three datapaths

```
module              cells  amps/cyc   cells per amp/cyc
AddressGen             79         -                   -
BenesOnly            2304         -                   -
DiagonalKernel      20775         1               20775
MonomialKernel     159287         8               19910
DenseKernel        169577         1              169577
```

- The permute path costs what the phase path costs, within 4% per unit throughput.
- The dense path costs 8.5x either — `2^k` as predicted, so ~64x at k=6.
- **The shuffle fabric is 1.4% of the permute path.** The thing that looked like the architectural
  risk is a rounding error next to the multipliers.

### Correction: Q1.23 cannot hold a coefficient of 1

The exact-model check caught a sign flip on the permute path. Q1.23 stops at `1 - 2^-23`, so a
coefficient of exactly 1.0 wraps to -1.0 — and unitary operators are full of ±1, since every X,
CNOT, SWAP and Toffoli is a permutation matrix of them. The diagonal and dense kernels had the same
latent bug and had merely been given vectors that avoided unit entries.

Everything is Q2.22 now: one bit of precision for correctness. Deviations from Scotty roughly
doubled as expected — 3.3e-07 diagonal, 3.4e-07 dense, 1.2e-07 permute — all far inside tolerance.

### Correction: ChiselSim works, on Chisel 7

The earlier note said ChiselSim's svsim does not work with Verilator 5.052. That is true of Chisel
**6.7.0**, the version CUTE pins, and false in general: on 7.0.0-RC1 the same test passes. The
project is now on Chisel 7 and every test runs under Verilator rather than an interpreter.
chiseltest remains unusable — it stopped at 6.0.0 and its Verilator backend fails to compile its own
harness against Verilator 5.
