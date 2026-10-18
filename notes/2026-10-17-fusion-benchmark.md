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

## Addendum 5: the cycle-approximate model — bank conflicts

Stage 2 of the roadmap, and the stated go/no-go for RTL. It has an exact answer rather than a
statistical one.

### The structure collapses the problem

Within a slice the `2^k` addresses differ only in the bits at the supported qubit positions, so the
address set is `base + V` where `V` is the linear span of `e_q` over GF(2) — addition is XOR,
because `base` is zero at exactly those positions. A bank mapping worth building is linear too,
`bank = L * address`, which in hardware is a tree of XOR gates. Then

    bank(base + v) = bank(base) XOR bank(v)

so the multiset of banks a slice touches is a translate of `L(V)`, and **its shape does not depend
on the slice**. Every slice conflicts identically, and

    cycles per slice = 2^(k - rank of L's columns at the support)

The model checks this against counting banks directly, for every slice, rather than trusting it.

### So it is a coding-theory question

A mapping gathers every width-`k` support in one cycle iff every `k` of its columns are linearly
independent — iff `L` is the parity-check matrix of a code with minimum distance `k+1`.

Bank bits needed, 20 qubits, all supports enumerated:

```
  width k      low bits      xor fold      distinct  ext. Hamming       BCH d=5
        1          none             1             1             1             7
        2          none          none             5             6            11
        3          none          none          none             6            11
        4          none          none          none          none            11
        5          none          none          none          none            11
        6          none          none          none          none          none
```

BCH d=5 reaching k=5 exceeds its guarantee — designed distance 5 only promises every 4 columns
independent. With only 20 columns drawn from a 32-element field the true distance is higher. Worth
noting as measured rather than claimed.

### The practical answer

Single-cycle gathering for every support is unaffordable past k=3. It is also not what sets
throughput. Mean cycles as a multiple of the floor, 32 banks:

```
mapping         k=3     k=4     k=5     k=6
low bits      5.31x   9.19x  15.87x  13.64x
xor fold      1.47x   2.02x   2.96x   2.28x
distinct      1.04x   1.19x   1.61x   1.27x
ext. Hamming  1.06x   1.20x   1.55x   1.20x
```

**The obvious mapping never works at any bank count** — its columns beyond `bankBits` are zero, so a
block touching a high qubit puts all `2^k` addresses in one bank. **A handful of XOR gates gets
within 20% of the floor.**

The risk resolves, and it resolves into a bank mapping rather than a fabric.

### What this does not answer

It assumes one access per bank per cycle with no queuing, so it bounds cycles from below rather than
describing a working memory controller. And it says nothing about routing amplitudes from banks to
lanes once fetched — though the permute path's measurement, where a full Benes network cost 1.4% of
the datapath, suggests that fabric is not where the difficulty lies.

## Addendum 6: the memory controller schedule

The per-slice bound assumes one access per bank per cycle and no queuing, which makes it a statement
about a slice rather than about throughput. Slices are independent: one whose addresses pile into a
single bank leaves the other banks idle, and the next slice can use them. So the real question is
how much buffering a mapping costs.

Bandwidth in accesses per cycle against reorder window depth, 32 banks, width-6 blocks, each mapping
run on the support it handles worst, 14 qubits so that every slice is enumerated:

```
mapping       worst support      d=1   d=2   d=4   d=8  d=16  d=32   reachable
low bits      5,6,7,8,9,10       1.0   2.0   4.0   8.0  16.0  32.0   32 of 32
xor fold      0,1,5,6,10,11      4.0   8.0  16.0  32.0  32.0  32.0   32 of 32
distinct      0,1,2,3,4,5        8.0  12.0  16.0  16.0  16.0  16.0   16 of 32
ext. Hamming  0,1,2,3,4,5       16.0  19.1  27.3  32.0  32.0  32.0   32 of 32
```

### This softens the previous headline and sharpens it

With enough slices in flight even low bits reaches full bandwidth, so its 13.6x is a **latency**
penalty rather than a bandwidth one. It buys its way out with buffering — depth 32 against extended
Hamming's depth 8. A slice in flight carries 64 outstanding accesses, so that is 2048 against 512:
four times the queue for the same bandwidth.

### What only the schedule could see

`distinct` is capped at **half the bandwidth however deep the window**, because half the banks are
not reachable at all. Its columns are the values 1 to 14, none of which sets the fifth bank bit, so
the upper sixteen banks are never addressed by any access. No amount of queuing fixes a bank that is
never asked for.

The per-slice model could not see this. It measures conflicts *within* a slice and rated `distinct`
among the better mappings, at 1.27x the floor at k=6. **Coverage and conflict are different
properties and both have to be checked.**

### A methodological note

The first run sampled 256 of 16384 slices and reported `distinct` as reaching 16 banks — true of the
run, false of the mapping, because a short prefix leaves the high address bits always zero and the
banks they would reach never appear. The numbers above enumerate every slice. That `distinct` is
genuinely capped is the conclusion after fixing that artifact, not before it; the artifact and the
real effect happened to point the same way, which is the dangerous case.

### Still unanswered

The scheduler may serve any pending access, so it is optimistic and its depths are lower bounds on
what real hardware needs. Nothing here models the bank arbiter, the crossbar from banks to lanes, or
what happens when a block's operator load contends with its amplitude traffic.

## Addendum 7: the bank arbiter and the crossbar

### The arbiter takes back the previous conclusion

`Schedule` allowed any pending access to be served in any order — unlimited reordering, unlimited
queues. Adding finite per-bank queues, finite issue width and in-order issue, 32 banks, depth 4,
width-6 blocks, worst support:

```
mapping        in order   look 8  look 32  look 256   ceiling
low bits            1.1      1.2      2.1       9.7        32
xor fold            5.3      5.8     10.3      29.4        32
distinct            9.6      9.6     11.3      16.0        32
ext. Hamming       18.2     18.2     20.1      31.7        32
```

Addendum 6 said low bits "buys its way to full bandwidth with buffering". **That is false under
realistic issue.** With depth-4 queues it reaches 9.7 of 32 even at a lookahead of 256.

The reason is a burst-length effect the first model could not express. Each slice sends all `2^k` of
its accesses to one bank, so the issue window has to reach *past an entire slice* — 64 requests —
before it finds anything for an idle bank. Queue depth barely helps:

```
mapping       q=1    q=2    q=4    q=8    q=16
low bits      2.0    2.1    2.1    2.4    2.9
xor fold      7.8    8.9   10.3   19.0   28.3
distinct      9.6   10.1   11.3   13.2   15.9
ext. Hamming 17.4   18.2   20.1   24.5   31.4
```

**In order, with modest queues, the bank mapping is worth 16x in bandwidth** — 1.1 against 18.2.
That is the number to design against, not the per-slice floor and not the optimistic schedule.

### The crossbar is the easy half

Lane `l` holds the amplitude at `base + offset(l)` and `offset` is linear in `l`, so

    bank(l) = L(base) XOR L(offset(l))

is affine in the lane index, its linear part being `L`'s columns at the support. Classifying every
gather over every support and slice confirms it: a gather is affine, or it conflicts and is not a
permutation of lanes at all. **Nothing is ever arbitrary**, against 58.8% for operator permutations.

Bit permutations are nearly absent though — 0 to 1.4%. The address stride *is* a bit permutation of
the index, but the bank mapping XORs bits together on the way, and that is exactly what makes the
gather conflict-free. **What fixes bank conflicts is what stops the crossbar being a bit
permutation.** The two are coupled, and since a full Benes network measured at 1.4% of a datapath,
the resolution is to spend it rather than optimise.

### Not modelled

A single global issue port rather than a per-bank request network; no contention between operator
loads and amplitude traffic; no refill or writeback path. Stage 3 integration questions.

## Addendum 8: the request network, operator contention, and writeback

The three gaps left by addendum 7. Two needed arithmetic rather than simulation.

### The request network buys almost nothing

```
mapping         global port   no blocking   1 port/bank   ceiling
low bits                2.1           2.1           2.0        32
xor fold               10.3           9.9           7.4        32
distinct               11.3          10.9           9.4        32
ext. Hamming           20.1          19.6          17.0        32
```

Removing head-of-line blocking entirely lands within a few percent of the single global port, in
both directions. (Neither strictly bounds the other: they also differ in how the issue window
advances within a cycle. The closeness is the point, not the sign.)

**Blocking was never what cost bandwidth.** A mapping that sends a whole slice to one bank is
*starved*, not blocked — there is nothing for the other banks to accept. No interconnect fixes a
bank nobody is asking for. Adding the single request port per bank a real network would have costs
about 15%, which is more than the blocking it replaces.

This was worth measuring mainly to find out the interconnect is not where the difficulty lives.

### Operator traffic is not in contention

A block loads its operator once and then streams the whole state past it, so the only question is
amortisation:

```
   n   k    operator   amplitudes  operator share
  20   4         256      1048576           0.02%
  20   6        4096      1048576           0.39%
  24   6        4096     16777216           0.02%
  24   8       65536     16777216           0.39%
  12   6        4096         4096          50.00%
```

Under a percent at any size worth building for. It stops being negligible only when the state is
small enough to be comparable to the operator, which is the regime where the engine has nothing to
do.

### Writeback halves everything, and need not

Every amplitude read is transformed and written back to the same address, hence the same bank. So
traffic doubles while the distribution stays *identical* — the bank histogram does not change at
all, and every bandwidth figure halves.

But the write targets the address the read just used, so a memory supporting read-modify-write
recovers the entire factor of two. That is a memory feature rather than an arbiter one, and it is
the largest single lever left in the memory path.

### Now genuinely not modelled

Refill from a level below when the state does not fit on chip, and the CPU-side cost of computing
fusion, Benes routing and bank descriptors per block. Both are questions about a system rather than
about this datapath.

## Addendum 9: two model bugs, and the numbers they moved

A review of addendum 7's commit found six issues, three of which changed published figures. Both of
the substantive ones are recorded here because the corrected numbers supersede what addenda 7 and 8
reported.

### The lookahead parameter was not the lookahead

`Arbiter.bandwidth` refilled its issue window *inside* the issue loop, so one cycle's scan could walk
`lookahead + issueWidth - 1` requests past the blocked head rather than `lookahead`. Every
intermediate column was optimistic by an amount depending on how many requests issued that cycle,
and the "in order" column was not in order at all.

Corrected:

```
mapping         in order   look 8  look 32  look 256   ceiling
low bits             1.0      1.2      2.1       9.4        32
xor fold             1.0      5.3      9.5      28.8        32
distinct             1.0      8.0     10.6      16.0        32
ext. Hamming         1.0      8.0     19.1      31.7        32
```

**The headline changes.** Addendum 7 said the bank mapping is worth 16x in bandwidth, 1.1 against
18.2 in order. That was the bug. Strictly in-order issue caps *every* mapping at 1.0, because a
window holding one request can issue at most one per cycle whatever its bank — the window is
binding and the mapping is irrelevant. The mapping matters once there is somewhere to look: at a
lookahead of 32 it is worth **9x**, 2.1 against 19.1.

### The crossbar sampled 16 of 1024 slices

`Crossbar.main` classified slices 0..15 while its own banner and all three documents claimed every
slice. The affine/conflicting split is slice-independent so those figures stood, but the
bit-permutation test turns on `L(base)`, which is not — so the "0 to 1.4%" figure was a 1.5% biased
sample. Over all 1024 slices it is **0.00% to 0.90%**.

This is precisely the trap documented in `Schedule.scala` after addendum 6 hit it: a prefix of
slices leaves the high address bits always zero. Writing the warning did not prevent repeating the
mistake two models later.

### The classifier could not have failed

`Crossbar.classify` folded "permutation but not affine" into the same bucket as "conflicts", so a
non-affine permutation would have been counted as a conflict and the conclusion "nothing is ever
arbitrary" printed regardless. It now has an `Arbitrary` case, the conclusion is printed only when
that case is empty, and it is empty — but the claim is now one the model could have refuted.

### Also fixed

The address model was copied into four places and is now one helper in `BankConflicts`; the arbiter
guards against degenerate arguments that previously hung it; and the "worst support" caption now
says it is the worst per-slice conflict count rather than the worst bandwidth, which are different
and were being conflated.

## Addendum 10: the request network, measured properly

Addendum 8 reported that a per-bank request network buys almost nothing, and that the interconnect
is not where this design's difficulty lives. **Both are wrong**, and the experiment behind them was
not an experiment.

The configuration labelled "no head-of-line blocking" kept the same single bounded window as the
baseline it was compared against, so a backlog at one bank still consumed slots that every other
bank needed. The two models were nearly identical by construction; their agreement within a few
percent was a tautology. A review caught it.

### With genuine per-bank queues

Each bank with its own queue, fed in stream order, so a backlog occupies nothing another bank needs.
The baseline is the shared-window arbiter at lookahead 1, which is the configuration that actually
blocks:

```
mapping         blocked      d=4     d=16     d=64    d=256  ceiling
low bits            1.0      1.1      1.3     28.5     28.5       32
xor fold            1.0      5.3     25.6     31.1     31.1       32
distinct            1.0      9.6     15.9     15.9     15.9       32
ext. Hamming        1.0     18.2     30.2     31.4     31.4       32
```

The shape is the burst length. A slice sends all `2^k` of its accesses — 64 here — to however many
banks the mapping spreads it across, and the generator can only move on once the current slice fits
in queues. **Low bits needs a queue the size of a whole slice** before it overlaps slices at all:
1.3 at depth 16, 28.5 at depth 64.

### What the mapping is actually worth

Not bandwidth, once the queues are right: both reach the high twenties. It is worth **queue depth**,
16 against 64 — a factor of four in request buffers, across all 32 banks. That is a real cost, and a
different claim from the one addendum 8 made.

### Writeback, restated on the corrected numbers

Extended Hamming goes 31.4 read-only to 15.7 with writeback, and back to 31.4 with a
read-modify-write memory.
