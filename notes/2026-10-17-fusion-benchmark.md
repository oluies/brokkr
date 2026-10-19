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

**Superseded by addendum 10.** The comparison below is not an experiment — both configurations
shared one bounded window — and the conclusion drawn from it is wrong. The table is kept so the
correction in addendum 10 has something to point at.

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

**Superseded by addendum 11.** The table and the 9x conclusion below are the artifact; the original
figures were right. Kept so addendum 11 has something to point at.

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

**Retracted by addendum 12.** Neither this addendum nor the one it corrects measured a request
network. Kept for the record.

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


## Addendum 11: the arbiter table went round a loop

Addendum 9 reported corrected arbiter figures and a headline of 9x. **Those were the artifact, and
the original numbers were right.** The sequence is worth recording because the intermediate state
was published.

1. The first model refilled its issue window inside the issue loop. A review found that
   `lookahead` therefore did not bound what it claimed to bound.
2. The correction moved the refill to the cycle boundary. That fixed the scan distance and
   simultaneously made `lookahead` the window *capacity*, which caps throughput at `lookahead`
   accesses per cycle. The resulting table read 1.0 everywhere in order and exactly 8.0 at look 8 —
   the cap, not a property of any bank mapping. A second review caught it.
3. The model now sizes its window to reach the issue width and bounds only the scan distance, which
   is what the parameter always claimed. That reproduces the original numbers.

```
mapping         in order   look 8  look 32  look 256   ceiling
low bits             1.1      1.2      2.1       9.7        32
xor fold             5.3      5.8     10.3      29.4        32
distinct             9.6      9.6     11.3      16.0        32
ext. Hamming        18.2     18.2     20.1      31.7        32
```

So **the bank mapping is worth 16x in order**, 1.1 against 18.2, as first reported. The first
version's figures were right and its code was unclear; the correction was wrong; this version is
both right and clear.

The lesson is narrower than "review catches bugs". The first review's finding was about the code
saying something it did not do — a real defect, correctly identified. The mistake was mine in
treating a clarity finding as a numerical one and changing behaviour to fix it. A fix that moves
published numbers deserves the same scrutiny as the original measurement, and this one did not get
it until a second review.

### Also fixed from the same review

The notes' addendum 8 table is now marked superseded rather than left to contradict addendum 10. And
three places still said the memory-side shuffle *is* a bit permutation that an Omega network suits,
which the 0.00-0.90% measurement contradicts: the address stride is a bit permutation, but composed
with the bank mapping that makes gathers conflict-free it is affine. Both shuffles need more than
Omega, for different reasons.


## Addendum 12: the interconnect claim, retracted

Three versions of this model have now carried a conclusion about interconnects that the experiment
did not support. The claim is withdrawn rather than corrected a third time.

1. Addendum 8 reported that a request network buys almost nothing. Its two configurations shared
   one bounded window and were nearly the same model; their agreement was a tautology.
2. Addendum 10 reported the opposite. Its "per-bank queues" model still stalled the generator at
   the first full target bank, so head-of-line blocking was never removed and the per-bank queues
   were unobservable — at equal depth it is the *same algorithm* as the arbiter at lookahead 1,
   and gives the same numbers. Its baseline column was separately capped at 1.0 by construction.
3. A review pointed out that the repo's own figures contradicted the headline: at the same depth 4,
   the shared-window arbiter at lookahead 32 beat the per-bank model on all four mappings, fifteen
   lines above the claim that the network "buys a great deal".

### What is actually measured

Two levers, both real, neither an interconnect:

```
# queue depth, generator strictly in order
mapping             d=4     d=16     d=64    d=256  ceiling
low bits            1.1      1.3     28.5     28.5       32
xor fold            5.3     25.6     31.1     31.1       32
distinct            9.6     15.9     15.9     15.9       32
ext. Hamming       18.2     30.2     31.4     31.4       32

# bypass distance, queue depth 4 (addendum 11)
mapping        in order   look 8  look 32  look 256
ext. Hamming       18.2     18.2     20.1      31.7
```

They substitute for each other to a degree: extended Hamming reaches about 30 either by depth 16
with no bypass, or by depth 4 with a lookahead of 256.

**Whether a network that decouples them is worth its area is a question these models cannot answer**,
because none of them has an in-order generator that can bypass a full bank. Building a fourth model
to settle it would be the obvious move; recording that the previous three did not settle it is the
necessary one.

### On the pattern

Five reviews, five Fails, and the same error three times: attributing a measured effect to the
feature I was interested in rather than to the parameter I had actually varied. The design
conclusions have been stable throughout — XOR banking, Benes for both shuffles, queue depth at the
burst length — while the *causal* claims around them have been wrong repeatedly. Numbers that move
when a model is corrected deserve more suspicion than numbers that do not.


## Addendum 13: where the buffer budget belongs

The question addenda 8 and 10 kept failing to ask. It needed no new model — `Arbiter` already *is* a
bypassing generator with per-bank queues: `occupancy` is the per-bank queues, the window is the
reorder buffer, `lookahead` is how far the generator may bypass a full bank. I had been mislabelling
my own model for three addenda. What was missing was a comparison at equal storage.

A design has some number of request slots and two ways to organise them:

- **partitioned** across banks — a slot only ever serves its own bank, and is stranded when traffic
  concentrates elsewhere;
- **pooled** in a reorder window — any slot holds any request, but a blocked request occupies one
  until its bank drains.

Budget is `banks * depth + window`; every row of a budget spends the same storage.

```
mapping         budget   pooled    split  partitioned                  best
                            d=1      d=4      deepest
low bits            64      2.0        -    2.0 (d=1)      d=1, w=32 -> 2.0
low bits           256      7.9      5.4    5.4 (d=4)     d=1, w=224 -> 7.9
low bits           512     15.4     13.9   11.0 (d=8)     d=1, w=480 -> 15.4
low bits          1024     29.0     28.7  26.6 (d=16)     d=1, w=992 -> 29.0
low bits          2048     31.9     31.1  28.9 (d=32)    d=1, w=2016 -> 31.9

xor fold           256     26.8     25.2   25.2 (d=4)     d=1, w=224 -> 26.8
xor fold           512     31.9     31.4   29.7 (d=8)     d=1, w=480 -> 31.9

ext. Hamming       256     29.4     25.4   25.4 (d=4)     d=1, w=224 -> 29.4
ext. Hamming       512     31.9     31.7   31.4 (d=8)     d=1, w=480 -> 31.9
```

### Pooled wins, structurally

At every budget and every mapping. The reason is not close: **a bank drains one request per cycle**,
so a queue deeper than one buys nothing unless the generator cannot refill it — and a generator with
a reorder window and the freedom to bypass always can. Depth absorbs bursts that bypass has already
absorbed.

So the instinct that produced addendum 10 — reach for per-bank queues — was backwards. The slots
belong in the window.

### What the mapping is worth, finally in the right currency

Extended Hamming reaches 31.9 of 32 on a budget of 512 slots. Low bits reaches 15.4 on the same
budget and needs **2048, four times the storage, to match it**.

That is the first version of this number resting on a comparison between two things that differ in
exactly one respect. The earlier attempts — 16x in bandwidth, 9x, "a great deal" — were each
measuring a confound.


## Addendum 14: the refill path, and what it does to the case

Everything modelled so far assumed the state is on chip. Past about twenty qubits it is not.

The engine then works in tiles, and a block applies to a tile in isolation only if every qubit it
touches is **local** — inside the address bits the tile spans. A block reaching above that line
needs amplitudes from several tiles at once, so the state must be re-tiled first: a full traversal
of off-chip memory each way. Above the capacity line the figure of merit is **passes**, not cycles.

### The form lattice, in a third role

A **diagonal** block streams. It scales each amplitude independently, so it applies to a tile
whatever its support — including a support spanning every qubit. Grover's all-qubit controlled `Z`,
which forced a native escape in the fusion pass and was the worst case there, is **free** here.
Monomial and dense blocks move amplitudes between lanes and do need their support local.

### Measured at 24 qubits

```
brickwork: 61 blocks, 16 diagonal
    local   passes   lookahead 1   off-chip x state
       16       14            24                28x
       20       11            16                22x
       22       10            12                20x

Grover: 65 blocks, 4 diagonal
       16       22            38                44x
       20       18            25                36x
       22       14            24                28x

diagonal-heavy: 43 blocks, 39 diagonal
       16        1             3                 2x
       22        1             3                 2x
```

### Three things follow

**An ordinary circuit that spills moves 20-44x the state through off-chip memory.** At that point
the datapath is not the bottleneck and none of addenda 1-13 matters much. The engine's whole
advantage is that its traffic stays on chip; spilling gives it away.

**Capacity helps less than expected.** 16 to 22 local qubits is sixty-four times the on-chip store
and takes brickwork from 14 passes to 10. What forces a re-tiling is the circuit's *structure* —
blocks reaching across the register — not the shortfall in room. Scheduling helps more than capacity
does: a 64-block lookahead nearly halves the passes.

**A circuit of phases and permutations needs one pass.** That is the shape of the answer rather than
a detail.

### So the win conditions narrow

The assessment listed three cases where the engine could win. Two survive, in a sharper form:
**small enough to fit on chip**, or **diagonal-heavy enough to stream**. The density-matrix case is
the strongest because it is both — 10 to 12 qubits is `4^n` entries that fit on chip, and a Kraus
channel is a dense superoperator applied in place.

### Not modelled

The CPU-side cost of computing fusion, Benes routing and bank descriptors per block; and whether a
re-tiling can be overlapped with computation rather than stalling it, which would soften the pass
count without changing the traffic.


## Addendum 15: overlapping the re-tiling

Addendum 14 left the passes as stalls. They need not be, and there are two distinct ways to hide
them — only one of which matters.

### Double buffering: a sum becomes a maximum

Prefetch the next tile while computing on the current one. Worth 10% to 47% depending on the
bandwidth ratio. Useful and unremarkable.

```
brickwork, 61 blocks, 11 re-tilings
   off-chip  compute  transfer  serial  overlapped  saving  bound by
          1     1.91     11.00   12.91       11.00     15%  transfer
          2     1.91      5.50    7.41        5.50     26%  transfer
          8     1.91      1.38    3.28        1.91     42%   compute
```

### Folding: the transfer carries the work

A re-tiling already streams every amplitude past the engine, so a block can be applied as the data
goes by rather than queueing behind it. How much a pass carries is the on-chip to off-chip ratio:
while off-chip delivers one amplitude, on-chip can do many blocks' arithmetic on it.

```
 off-chip   blocks per pass   brickwork    Grover
        1                32        100%      100%
        2                16        100%      100%
        4                 8        100%      100%
        8                 4         72%      100%
       16                 2         36%       55%
```

**While off-chip delivers at most about an eighth of the on-chip rate, a circuit's entire
computation folds into its transfer passes.** The cost of a spilled circuit is then exactly its pass
count and nothing else — not the fusion width, not the bank mapping, not the datapath that addenda
1 to 13 are about.

So the answer to "is the traffic a wall or a background cost" is: **a wall, and computation hides
behind it completely.** Above the capacity line the engine should be designed to minimise
re-tilings, and its arithmetic is free.

### Do not read the regime as settled

The 32-amplitudes-per-cycle on-chip figure is this project's banking model, which chose 32 banks; a
wider store raises it. And off-chip is not obviously slow — HBM at a few TB/s is, at a GHz, well
over a hundred fp64 complex amplitudes per cycle, which is the far side of the table. Where a real
part sits depends on a bank count that is a design choice and a memory technology that is not.

**The model establishes the crossover, not which side of it to expect.** That distinction is worth
keeping given how many times in this file a number has been read as a conclusion.


## Addendum 16: the host-side descriptor cost

The last unmodelled piece, and it turns out to bind.

Every block must be built before the engine applies it: gates fused into one operator, the form
classified, a Benes routing computed if monomial, strides worked out. The asymmetry is not in the
engine's favour — applying a block touches every amplitude once, so its cost grows with the
**state**, while building a dense one is a matrix product per gate fused, `O(8^k)`, growing with the
**block** and not with the state at all.

Measured against an engine at 1 GHz and 32 amplitudes per cycle, 20 qubits:

```
 width  blocks   fuse/block  engine/block    ratio       bound by
     2     118        8.8 us        65.5 us    0.13x        engine
     4      59       15.7 us        65.5 us    0.24x        engine
     5      51       24.0 us        65.5 us    0.37x        engine
     6      40      116.5 us        65.5 us    1.78x          host
```

Benes routing is not the problem: 1.7 us at 8 lanes to 5.7 us at 64, i.e. 0.03 to 0.09 of engine
time. Operator construction is the whole of it.

*(An earlier version of this measurement reported 56 us per route, which was an artifact of timing
four blocks with all the surrounding work included. Caught before it was published, unlike some.)*

### A third constraint on the fusion width, and the tightest

Section 03 of the assessment argued for k=6 to 8 from arithmetic intensity, bounded above by
operator storage. The host adds a third bound and it binds first: **at 20 qubits, k=6 is already
host-bound**, with the engine waiting 1.78 cycles for every one it works.

The crossover moves with the register, since engine time grows as `2^n` and host time does not —
k=6 becomes engine-bound at n >= 21. But 20 qubits is about where a 16 MiB on-chip store runs out.
**So the sizes at which the engine is host-bound are exactly the sizes at which it fits on chip**,
which the refill model says is the only place it wins. The two constraints close on the same point
from opposite sides.

The way out is modest: design at **k=5**. Comfortably engine-bound at 0.37x, still 6.7 flop/byte and
a 16 KiB operator, and the measurements elsewhere say intensity was never the binding constraint.

### Caching does not rescue k=6

Measured operator reuse: 1.2x on brickwork, 3.1x on Grover, 1.1x on diagonal-heavy. Grover repeats
its oracle; a brickwork layer varies its angles and repeats almost nothing.

### Caveats, both pointing the same way

This times *this* fusion pass — written for clarity in Scala 3, never optimised — on one core
preparing for one engine. A tuned implementation in a systems language could plausibly move the
crossover by several times, and a real host would use several cores. The *structure* of the finding
survives both, because host cost is flat in the state and the engine's is not. The particular
crossover point would not.


## Addendum 17: the density-matrix measurement

The assessment pointed here twice from opposite directions — the arithmetic-intensity argument in
section 03, and the refill model's conclusion that the engine only wins where the state fits on
chip. Measured rather than argued:

```
  qubits  dimension         rho   flop/byte  vs unfused    vs k=6
       6         64      64 KiB          43         97x        3x
       8        256       1 MiB         171        388x       11x
      10       1024      16 MiB         683       1551x       43x
      11       2048      64 MiB        1365       3103x       86x
      12       4096     256 MiB        2731       6206x      172x
```

Measured on Scotty's density layer the intensity comes out as modelled — 5, 11, 21, 43, 85 flop/byte
at 3 through 7 qubits, doubling per qubit. The throughput alongside (7.3 Gflop/s peak) is a JVM
running a textbook cubic matmul; it validates the intensity figure, not any achievable rate.

### The difference is structural

A state vector is `2^n` amplitudes and a gate touches each once — work linear in the data, which is
why this entire project has been a bandwidth story. A density matrix is `4^n` entries and a channel
is two matrix products per Kraus operator: `O(8^n)` work against `O(4^n)` data. **The arithmetic per
byte grows with the system** instead of sitting at 0.44.

### Every problem in these notes disappears

- **banking** — a matrix product has regular dense access, so no bit-permutation stride and none of
  the coding theory from addendum 5. Ordinary GEMM blocking.
- **fusion and its host cost** — the operators are the Kraus set. Nothing to fuse, no 116 us per
  block, no k=5 cap.
- **refill** — at ten qubits rho is 16 MiB and fits on chip. None of the 20-44x traffic.
- **the three datapaths** — there is one, a MAC array, and it is busy.

### Which answers the original question

A state-vector engine is *not* CUTE. It needs a shuffle fabric, three datapaths and a bank mapping
from coding theory, none of which a convolution engine has. A density-matrix engine is a complex
GEMM machine, which is **very nearly CUTE already**: adapt MatrixTE and ReducePE to complex
arithmetic, keep ABMatrixReg, keep the Omega fabric for operand staging, and most of the design
carries over.

### Two caveats

The capacity ceiling is hard and low — eleven qubits, because `4^n` grows fast. This is an engine
for small noisy systems, not for scale.

And if the kernel is complex GEMM then the quantum-specific contribution is thin: much of the value
would sit in the compiler and verification work rather than in the datapath. That is less exciting
than a novel architecture and considerably more defensible.


## Addendum 18: the density-matrix datapath, built

`ComplexGemm`: a `tile x tile` array of complex accumulators fed one column of `A` and one row of
`B` per cycle, accumulating their outer product. After `depth` cycles it holds a tile of
`C = A · B`. That is the same structure as CUTE's MatrixTE, which is the point.

Verified against Scotty's `ComplexMatrix.multiply` the same two ways as everything else: exactly
against an integer model of its own arithmetic, and for drift against the doubles.

**Deviation is 2.8e-07 over a depth-16 product** — no worse than the single-multiply kernels at
1.2e-07 to 3.4e-07. Sixteen products accumulate before one rounding, because the accumulator is
widened and narrowed only on the way out. That is what makes a deep GEMM safe in fixed point, and
what lets the same design run to the depth `2^n` a ten-qubit channel needs. (Validated at depth 16;
the design extends by construction, since accumulator width scales with `log2(maxDepth)`.)

### The cleanest number in the project

```
module              cells   complex MAC/cyc   cells per MAC
DiagonalKernel      20775                 1           20775
MonomialKernel     159287                 8           19910
DenseKernel        169577                 8           21197
ComplexGemm        322347                16           20146
```

**All four within 6% of each other at about 20 000 cells per complex multiply-accumulate.** Phase
tables, Benes networks, wide accumulators — every architectural difference argued over in these
notes is noise against the multipliers.

Which settles the comparison the whole project has been circling. **Silicon per MAC is the same
whichever engine you build.** What differs is how much useful work a MAC does, and that is
arithmetic intensity: 0.44 flop/byte unfused, 15.9 for the best fused block, 683 for a ten-qubit
density matrix.

The density-matrix engine does not win because it is cheaper. It wins because its multipliers are
busy.


## Addendum 19: the engine, running a full Kraus channel

`ChannelEngine` sequences `ComplexGemm` into something that applies a channel. For each Kraus
operator: `T = K · rho`, keep the intermediate, `R = T · K†` — whose right operand is the conjugate
of the same column, so the same storage is read with the imaginary part negated — and accumulate.
That is `Channel.apply`, in gates.

Checked against Scotty on a depolarizing channel acting on one qubit of a two-qubit state, four
Kraus operators of 4x4:

```
cycles for the whole channel      44
worst deviation from Scotty       9.9e-07
trace of the result               0.999997
Hermitian symmetry                holds to 1e-05
```

### The physical checks are worth more than the numerical one

A channel is trace preserving and maps Hermitian states to Hermitian states. Those are properties of
the *physics*, not of the reference: a transposed index or a conjugation dropped from the second
product would break them even if the element-wise comparison somehow passed. They are the closest
thing available to a check that does not depend on the golden model being right.

### Cycles and area

Forty-four cycles is eleven per Kraus operator — four per matrix product, three for capture,
accumulate and sequencing. So 73% of cycles do arithmetic at this size, and the fixed three amortise
as the dimension grows: 91% at sixteen.

```
ComplexGemm     322347 cells    the multiplier array
ChannelEngine   342351 cells    +6% for storage, sequencing, accumulation
```

**Control and storage cost six percent.** The same lesson as the per-MAC table in addendum 18,
arriving from a different direction: in a design like this the multipliers *are* the design.

### A measurement error caught before publication

The first area figure for `ChannelEngine` read 20646 cells — less than the datapath it contains.
firtool emits submodules to their own files, and `scripts/area.sh` was reading only the named one,
leaving `ComplexGemm` as a blackbox. The script now reads every generated file and flattens the
hierarchy before counting. Leaf modules are unaffected, since none of them has a submodule, and
their published figures stand.

That is the third time in these notes a number has looked wrong and been wrong. The pattern worth
keeping is the one that caught it: a result that contradicts something already known — here, a
container smaller than its contents — is worth more suspicion than a result that merely surprises.


## Addendum 20: property-based tests for the engine

The engine was verified on one channel and one state, which was thin for the thing the whole
assessment rests on — the state-vector path it is compared against is checked against randomised
circuits over the entire gate set. Closed.

### Three channels, three states each

```
worst deviation from Scotty over 9 cases:   9.926e-07
worst trace error over 9 cases:             2.861e-06
worst Hermitian asymmetry over 9 cases:     1.431e-06
Kraus counts exercised:                     2, 4
```

Depolarizing (4 Kraus, unitary-weighted), amplitude damping (2, non-unitary) and dephasing (2,
diagonal) — different structures as well as different counts, so the sequencing counter takes more
than one path.

Trace preservation and Hermiticity are the valuable assertions. They are properties of the physics
rather than of the reference: a transposed index or a conjugation dropped from the second product
breaks them even if the element-wise comparison passes.

### The depth sweep pins the scalability claim

```
depth   4: worst deviation 2.108e-07
depth   8: worst deviation 2.865e-07
depth  16: worst deviation 2.365e-07
depth  32: worst deviation 2.392e-07
```

**Eight times the depth costs nothing.** The accumulator is widened and narrowed only on the way
out, so a depth-32 product rounds once rather than thirty-two times. That is the claim that licenses
running this design to the depth `2^n` a ten-qubit channel needs, and it is now measured rather than
asserted — had the error climbed with depth, the whole density-matrix argument would need a
different datapath.

### Two gaps, recorded rather than hidden

**Dimension.** All of this runs at dimension four. `ChannelEngine` holds the dimension equal to the
tile, so it cannot run at the ten or eleven qubits the assessment argues for — that would be a
1024-wide tile, some twenty million cells of multiplier. Reaching those sizes needs a loop over
output tiles with the intermediate in a memory rather than registers. **Verified at dimension four,
argued to 1024.** The arithmetic generalises, as the depth sweep shows; the engine does not, yet.

**Non-square channels.** Scotty's erasure channel maps dimension 2 to dimension 3. This engine
assumes rho, K and the result are all the same square size, so it cannot express erasure at all —
a channel `pctc` uses alongside depolarizing and amplitude damping. A gap on the motivating
workload, not an exotic case.


## Addendum 21: tiling

The engine held the dimension equal to the tile, which made the control a counter and capped it at
whatever width of multiplier array one is willing to build — ten qubits would have been a 1024-wide
tile. Now the dimension is independent: the engine loops over `(dimension/tile)²` output blocks,
each accumulating over the full `dimension`-long inner dimension before being written out.

Measured at tile 4, over eighteen cases - three channels, three states, at both dimensions:

```
dimension   blocks per product   cycles per Kraus operator
        4                    1                          10
        8                    4                          72

worst deviation from Scotty over 18 cases:   1.133e-06
worst trace error over 18 cases:             5.722e-06
worst Hermitian asymmetry over 18 cases:     1.907e-06
```

Ten and seventy-two are both `2·(N/T)²·(N+1)`, so cycles grow as `N³/T²` — the arithmetic of the
product rather than anything the control adds.

This is also what makes the widened accumulator load-bearing rather than tidy. A block's
accumulation is now `dimension` deep, not `tile` deep, and addendum 20's depth sweep is the evidence
that costs nothing.

### The limit moved, and became a different one

```
ComplexGemm, tile 4      322347 cells
ChannelEngine, dim 4     342351 cells   +6%
ChannelEngine, dim 8     426119 cells   +24%, all of it storage
```

The array is fixed whatever the dimension. The engine around it was 6% at dimension four and is 24%
at dimension eight, because the state, the Kraus set, the intermediate and the accumulator all grew
fourfold while the multipliers did not. That share is mostly address muxing on register files.

**So the next change is storage, not control.** At ten qubits rho alone is 16 MiB: preposterous held
in registers with dynamic indexing, ordinary in SRAM, and the control above it would not change —
which is the useful thing tiling established.

Verified at dimension eight, with the loop that reaches further. What stands between this and ten
qubits is a memory, not an idea.

Erasure remains unsupported: 2 -> 3, and this engine assumes square throughout.


## Addendum 22: skewed storage, and the banking problem returning

At dimension eight the storage and its address muxing were already 24% of the engine, and at ten
qubits `rho` alone is 16 MiB. It belongs in memory — and putting it there brings back the problem
the state-vector half of this project spent its length on, in a much kinder form.

A tiled product reads two slices per cycle:

- a **column slice**, `tile` consecutive rows at one column, for the left operand;
- a **row slice**, one row at `tile` consecutive columns, for the right operand.

Bank by row and the column slice spreads while the row slice collides. Bank by column and the
reverse. The classical answer is to **skew**: `bank = (row + col) mod tile`. A column slice varies
the row with the column fixed and a row slice does the reverse, so each walks the banks one step at
a time and neither ever repeats one.

`SkewedStore` implements it and `SkewedStoreSpec` checks every slice of both shapes reads back
correctly, plus that the store divides evenly — 16 entries in each of 4 banks.

### The same shape of result, far more cheaply

This is section 08's bank mapping again: make a strided access conflict-free by choosing where
addresses land. There it needed a parity-check matrix of a code with minimum distance `k+1`, because
a qubit stride is a bit permutation and an arbitrary support must be served. Here a `+` and a mask
suffice, because a GEMM stride is regular.

**That contrast is most of the argument for the density-matrix engine in one sentence.**

### What it costs

Same capacity — an 8x8 matrix in four copies, 24-bit components:

```
store                        cells    flops   addressing logic
registers, four read muxes  113201    12480             100721
skewed banks                 31520    12322              19198
```

5.2x less addressing logic, 3.6x fewer cells overall.

The flop counts are near-identical and deliberately so: this synthesis has no SRAM library, so both
forms store their bits in flip-flops and the entire difference is in how they are reached — four
independent full-width read muxes against one decoder per bank. A flow with real memory macros would
widen the gap again, since bitcells replace the flops too. **That is not measured here and is not
claimed.**

### What integration needs

Plumbing, not ideas, and both items are counted work:

1. Reads are synchronous, so an address must be issued a cycle ahead of the lane consuming it. The
   step counter runs one cycle longer per block.
2. A `tile x tile` block can no longer be written in one cycle: `tile` single-ported banks cannot
   absorb `tile²` writes. It spreads over `tile` cycles, one block row at a time, each row touching
   every bank exactly once — which is the same skew property read in the other direction.


## Addendum 23: the engine on banked memory

Integration done. The operands moved from register files to `SkewedStore`, and the two
consequences anticipated in addendum 22 both materialised exactly as described.

```
storage        cells    over the array   cycles per Kraus
registers     426119   +103772 (24%)                   72
skewed banks  384449    +62102 (16%)                  104
```

**40% off the non-datapath cost, for 44% more cycles.** The address muxing goes away; the
synchronous read and the multi-cycle block write buy it back in time. Which way that trade is worth
taking depends on whether a design is area- or throughput-limited — and at ten qubits, where rho is
16 MiB, registers are not an option and the question does not arise.

The accumulator stays in registers deliberately: it is written a block at a time and read only at
the end, so it costs no read muxing, unlike operands that every lane reads every cycle. Most of the
remaining 16% is there.

Results are unchanged across the move — 1.133e-06 worst deviation, 5.722e-06 worst trace error,
1.907e-06 worst Hermitian asymmetry, the same eighteen cases. A storage change that altered the
arithmetic would be a bug.

### The bug it did introduce, and what caught it

First run: every case wrong, trace 0.19 instead of 1.0. Not subtle, and not tiling — the
dimension-4 case failed too, which immediately ruled out the new loop and pointed at the new
storage.

`gemm.io.clear` was asserted for the whole of the write state. That was correct when the write was
one cycle; now it spans `tile` cycles and reads the accumulators throughout, so the first edge
zeroed them and block rows 1..3 wrote zeros. Clearing only on the last write cycle fixed it.

Worth noting what made it quick to find: the cycle counts were already exactly
`2·(N/T)²·((N+1)+T)` — 18 and 104 — so the sequencing was provably right and only the data path
could be wrong. A test that reports structure as well as values narrows a search much faster than
one that only reports a mismatch.


## Addendum 24: rectangular channels, and the gap that closes

Erasure has been recorded as a gap since addendum 20, and it is the one that mattered: it is a
channel `pctc` uses, not an exotic case. It replaces the input with a flag state on a third basis
element, so `K` is 3x2 and it maps a qubit to a qutrit. The engine assumed rho, K and the result were
all one square size, so it could not express erasure at all.

It now carries two dimensions:

```
rho        inputDim  x inputDim
K          outputDim x inputDim
T = K rho  outputDim x inputDim
R = T K+   outputDim x outputDim
```

Both products accumulate over `inputDim`, and the only thing that differs between them is the
block-column count — `inputDim / tile` for the first, `outputDim / tile` for the second. So
separating the dimensions cost one argument in the tile walk (`advanceTile(colLimit)`) and nothing
else in the control. That was the prediction when the gap was flagged — "mostly separating input and
output dimensions in the addressing" — and the area measurement below is what confirms it rather
than merely restating it.

### The storage scheme needed no change whatever

`bank = (row + col) mod tile` with offset `row * (cols/tile) + col/tile` is still a bijection when
rows != cols. The offset fixes the row and the column's tile; the bank then fixes the column within
it. `SkewedStore` went from one `dimension` to `rows` and `cols` and the skew itself was untouched.

The requirement that replaced "power of two" is "whole number of tiles", which is what keeps a slice
inside one row or one column group. Powers of two were never the real constraint, only a sufficient
one.

### Where the dimensions land, and what gets padded

Lifting erasure over more qubits makes it rectangular without being awkward:

```
case                shape                tile   blocks/op   cycles/op
erasure, 1 qubit    2 -> 3, padded to 4     2           6          30
square,  2 qubits   4 -> 4                  4           2          18
erasure, 2 qubits   4 -> 6                  2          15         105
square,  3 qubits   8 -> 8                  4           8         104
erasure, 3 qubits   8 -> 12                 4          15         195
```

4 -> 6 and 8 -> 12 need no padding at all at a two- and four-wide array. The bare one-qubit case
does: three is not a multiple of any array width above one, so its output is zero-padded to four.
That is exact rather than approximate, because a zero row of `K` contributes nothing to either
product, and the padding is written into the vectors so the comparison is against the padded answer
rather than a reshaped one. The cost of padding is `(padded/true)^2` on the second product, so it is
worth minimising the array width rather than maximising it when a dimension is awkward — hence
tile 2 for the two cases where 4 does not divide the output.

Blocks are `(outputDim/tile)*(inputDim/tile) + (outputDim/tile)^2`, each `inputDim + 1 + tile`
cycles. The spec now **asserts** that count rather than reporting it. That is a deliberate change of
habit from addendum 23: pinning the schedule is what localised the clear-too-early bug to the
datapath, since the sequencing was provably right. A reported number is a hint; an asserted one is a
bisection.

### What a rectangular channel costs

```
engine          shape                 cells     over the array
ChannelEngine   8 -> 8,  4 operators  384436    +62089 (16%)
ErasureEngine   8 -> 12, 3 operators  412491    +90144 (22%)
```

**+7.3% for an output half again as wide**, and the array inside is the same 322347 cells in both.
The extra splits into +6919 flip-flops and +21136 gates: a 144-cell accumulator instead of 64, and
1.17x the operand storage (per bank, 112 entries against 96). Control is flat — which is the claim
above, measured.

The square engine itself moved 384449 -> 384436, thirteen cells, because the load port is now an
explicit row, column and copy rather than one packed address split by bit position. That had to
change: a row index into a 12-row operator is not a bit field.

### A check that does not depend on the golden model

The flag state is basis element 2 of the output, and erasure sends weight there that the input had
nowhere to put. So the corner of the true result is the erasure probability — 0.35, whatever the
state was. The spec asserts that, and that everything outside the true output comes back exactly
zero. Both are independent of Scotty, in the same spirit as trace preservation and Hermiticity.

Four channels, twenty-four cases, two dimensions, square and rectangular, tiled, on banked memory.
Worst deviation from Scotty 1.133e-06, worst trace error 5.722e-06, worst Hermitian asymmetry
1.907e-06 — and adding the six rectangular cases did not move any of the three.

### A measurement that had to be fixed first

Two configurations of the same generator exposed a hazard in `scripts/area.sh`. Addendum 19 fixed one
half of it: read every file a generation emitted, because firtool puts submodules in their own files
and reading the top alone leaves them as blackboxes, which silently reports a design as far smaller
than it is.

The other half is that submodule file names depend only on shape. `ChannelEngine` and
`ErasureEngine` both emit a `mem_16x24.sv` — with different port structures, since one holds rho and
a 16-entry temp and the other only rho — and both emit a `ComplexGemm.sv`, as does the standalone
`ComplexGemm`. Into one shared `build/`, the later generation overwrites the earlier one's
submodules, and a synthesis run then measures a design that was never generated. Measured both ways,
`ErasureEngine` came out 412235 in a shared directory and 412491 in its own: 256 cells of a
netlist that did not exist.

Generation now goes to `build/<Module>/` and synthesis reads only that directory.

Re-measuring every module under the fix reproduced every published figure except one:

```
module             published   corrected
ComplexGemm           322347      322347
ChannelEngine         384449      384436   (load port changed; see above)
RegisterStoreTop      113201      113201
SkewedStoreTop         31520       31627
DiagonalKernel         20775       20775
MonomialKernel        159287      159287
DenseKernel           169577      169577
```

`SkewedStoreTop` is 31627 cells, not 31520, and its addressing logic 19305 rather than 19198. Its
flop count was already right at 12322, so the whole of the difference is in the addressing. The
ratios are unchanged — 5.2x less addressing logic, 3.6x fewer cells — and nothing here concludes
differently. But the number was wrong, and a measurement tool that can read the wrong file is worth
more attention than the 0.3% it moved.


## Addendum 25: the last register file

Everything with a dimension in it had moved to banked memory except the accumulator, and that was
deliberate: it is written a block at a time and read only at the end, so it costs no read muxing,
unlike operands that every lane reads every cycle. It was still the largest thing left outside
memory. At 8 -> 12 it was 144 cells of 27 bits with four read-modify-write ports over them, and it
was where most of the remaining non-datapath cost sat.

It is a `SkewedStore` now too. Three consequences, all of them foreseeable and all of them real:

**The write runs a cycle behind the read.** Accumulating is read-modify-write, so the second
product's write phase pipelines over `tile + 1` cycles rather than `tile`: issue the slice at block
row r, add into the slice read last cycle at row r-1. Cycles per operator go from
`(outTiles*inTiles + outTiles^2) * (inputDim + 1 + tile)` to
`outTiles*inTiles*(inputDim + 1 + tile) + outTiles^2*(inputDim + 2 + tile)`.

```
case                shape                tile   blocks/op   cycles/op   was
erasure, 1 qubit    2 -> 3, padded to 4     2       2 + 4          34    30
square,  2 qubits   4 -> 4                  4       1 + 1          19    18
erasure, 2 qubits   4 -> 6                  2       6 + 9         114   105
square,  3 qubits   8 -> 8                  4       4 + 4         108   104
erasure, 3 qubits   8 -> 12                 4       6 + 9         204   195
```

**The result cannot be one wide port.** At 8 -> 12 the output was 6912 bits of combinational port.
It now comes out a row slice at a time, through the same `readRow` the datapath uses, while the
engine sits in `done`. Two users share one read port with mutually exclusive enables: the pipeline
while the second product runs, the host afterwards.

**It is the first store here that reads and writes in the same cycle.** Different addresses, so a
1R1W memory serves it, but that is not the same macro as a 1RW and this synthesis — which has no
SRAM library at all — cannot price the difference. Stated rather than hidden.

### It no longer needs clearing

The first Kraus operator writes its result and every later one adds to it. That removes an
`outputDim * (outputDim/tile)`-cycle zeroing pass — 16 cycles at 8 -> 8, 36 at 8 -> 12 — and the
first operator's read along with it. The accumulator is never initialised, which is a better property
than initialising it quickly, and it is the reason the cycle count above grows by only `outTiles^2`
rather than by a clear phase as well.

It also gave a free check. The padded cells of the one-qubit erasure case have to come back exactly
zero, and with no zeroing pass they are zero only because operator 0 writes zeros there and the later
ones add zeros. That assertion already existed; it now tests something stronger than it did.

### What it bought

```
engine               accumulator   cells     over the array   flops    cycles/op
ChannelEngine 8->8    registers    384436    +62089 (16%)     23079          104
ChannelEngine 8->8    banked       378385    +56038 (15%)     23105          108
ErasureEngine 8->12   registers    412491    +90144 (22%)     29998          195
ErasureEngine 8->12   banked       395924    +73577 (19%)     30032          204
```

**10% off the non-datapath cost square, 18% rectangular, for 4% more cycles.**

The flop counts are flat to within 34. That is the whole point rather than a disappointment, and it
is the same result as `SkewedStoreTop` against `RegisterStoreTop` arriving for a third time: with no
SRAM library both forms store the same bits in flip-flops, so every cell saved is addressing logic
and not storage. A flow with real memory macros would widen it. Not measured, not claimed.

Two things worth noting beyond the headline. The gap between the square and the rectangular engine
narrowed from +28055 cells to +17539 — most of what a wider output cost was its register file, not
its arithmetic, which is the same lesson as the 24% at dimension eight. And the emitted Verilog says
it more bluntly than the cell count does: `ErasureEngine.sv` went from 271.8 KiB to 60.7 KiB, and
the two engines now emit almost exactly the same volume.

Results unchanged, again: 1.133e-06 worst deviation, 5.722e-06 trace error, 1.907e-06 Hermitian
asymmetry over the same 24 cases. Three storage changes in a row have now left the arithmetic
bit-identical, which is what makes the fourth one worth trusting.

Nothing in this engine is in a register file any more.


## Addendum 26: the same kernel in SpinalHDL, as a control

The cleanest number in this assessment is that four structurally different datapaths all land within
6% of each other at about 20000 cells per complex multiply-accumulate — phase tables, Benes networks
and wide accumulators all being noise against the multipliers. All four came out of one generator.
So the result could instead have been a fact about Chisel and firtool, and nothing measured so far
could tell the difference.

SpinalHDL is an independent Scala HDL with its own Verilog backend — no FIRRTL, no CIRCT. Writing
`ComplexGemm` again in it and synthesising through the same `scripts/area.sh` is the control that was
missing.

```
                      Chisel     SpinalHDL
cells                 322347        324390
flip-flops              1472          1472
ports / port bits    52 / 1156    52 / 1156
cells per MAC          20146         20274
```

**0.63% apart, with identical state and an identical interface.** The whole difference is
combinational: the two backends build the multiply and add trees slightly differently before `abc`
sees them. The cell mix moves while the total barely does — 1222 fewer NANDs, 1150 more ANDs, 938
more XNORs, 723 more ORNOTs — which is what "the same arithmetic expressed differently" looks like
after technology mapping.

Both backends also found the same optimisation independently, having been told nothing about it. The
accumulator is 59 bits; the output takes the Q2.22 window, bits 45 down to 22; the top 13 bits
therefore cannot affect anything, since truncation wraps rather than saturates. Each accumulator
comes out 46 flops rather than 59, and 32 * 46 = 1472 in both.

### The function, checked rather than assumed

Comparing two areas is meaningless if the two circuits compute different things, and this is a case
where that was a live risk rather than a formality.

`scripts/equivalence.sh` puts both netlists in one Verilator simulation, drives them from identical
full-range random stimulus and compares every cell on every cycle. Full-range rather than the golden
vectors, deliberately: the vectors are small amplitudes and the disagreements worth finding —
truncation of negatives, wraparound of the complex subtract — live at the extremes. 48 trials of
depth 32, with `inValid` dropped every fourth cycle so a spurious accumulate would show:

```
compared 50688 values over 48 trials of depth 32; 24576 were non-zero
PASS GemmEquivalence: Chisel and SpinalHDL agree bit for bit
```

The place I expected divergence was rounding: `SFix(1 exp, -22 exp).truncated` against the
hand-written `(acc >> fractionBits)(dataWidth - 1, 0).asSInt`. They agree exactly. That was worth
checking and would have been worth reporting either way.

One incidental convenience: both generators name scalarised vector ports the same way, `io_aRe_0`
and so on, so the testbench connects all sixteen shared inputs with SystemVerilog's `.*` and only
the outputs have to be separated by hand.

### What it says, and what it does not

The per-MAC number is about multipliers, not about Chisel. An independently written kernel from an
independent backend lands inside the existing band rather than widening it — 20274 sits between
ComplexGemm's 20146 and DiagonalKernel's 20775.

It does not argue for a port. CUTE is Chisel, on rocket-chip and Utility, and the whole premise of
this assessment is similarity to it; giving that up for 0.63% would be a bad trade. One kernel at one
tile size through one gate-level flow with no liberty file is a fifth data point, not a study.

The ergonomic difference is real even though the area difference is not, and it is worth recording
separately from the measurement. In the Chisel version the accumulator width is asserted as
`2 * dataWidth + log2Ceil(maxDepth) + 1`, and `Fixed.scala` is three integer functions with the
discipline left to each kernel to apply by hand. In the SpinalHDL version the format is in the type,
the width falls out of the arithmetic as a peak exponent of
`2 * (dataWidth - fractionBits) + log2Up(maxDepth)`, and the compiler refuses any assignment that
would move the binary point — `.truncated` has to be written explicitly. Both arrive at 59 bits.

Addendum 4's bug, Q1.23 being unable to hold a coefficient of exactly 1, is precisely that class of
error. It cost a sign flip on the permute path, an exact-model check to catch it, a format change
across three kernels and a regression spec to keep it fixed. Here it would have been a compile error.
That is not an argument for switching HDL this late, but it is the honest reckoning of what the
hand-rolled format cost.

### What the two toolchains actually are

Worth writing down, because the usual contrast — "SpinalHDL uses Scala directly, Chisel requires a
rewrite" — is half wrong, and the half that is right is not about the source language.

Both are Scala. Neither is a separate language with its own parser; in both, elaboration *is* a Scala
program. Both ship a scalac compiler plugin to recover signal names from `val` declarations:
`chisel-plugin` (published per full Scala version) and `spinalhdl-idsl-plugin` (per binary version).
And neither is published for Scala 3 — Maven Central carries `chisel_2.12` and `chisel_2.13`, and
`spinalhdl-core_2.11`, `_2.12`, `_2.13`. So this repository's `rtl` module is pinned to 2.13 while
`vectors` and `model` run Scala 3.9, and that holds whichever HDL it uses. The Scala-version split in
build.mill is not a Chisel tax.

The difference is the back half:

```
                      Chisel 7.0.0-RC1        SpinalHDL 1.15.0
source                Scala 2.13              Scala 2.13
scalac plugin         required                required
Scala 3               not published           not published
intermediate form     FIRRTL                  none, its own netlist
Verilog backend       firtool, LLVM/MLIR      Scala, in-process
external binary       27 MB, version-matched  none
emits                 SystemVerilog           Verilog and VHDL
```

firtool is fetched and version-matched by Chisel itself; this machine holds
`llvm-firtool/1.114.1` and `1.62.1` in a cache, built on LLVM 21. SpinalHDL has nothing to fetch.

That stage has already cost this project something nameable. `Generate.scala` passes
`--lowering-options=disallowLocalVariables,disallowPackedArrays` for exactly one reason: CIRCT emits
`automatic logic` inside `always` blocks and Yosys rejects it with "Invalid nesting of always
blocks". Nothing in the Chisel source asks for that — it is a property of a code generator two
stages downstream, and it had to be diagnosed by reading the generated Verilog. The SpinalHDL output
needed no flag. Against that, FIRRTL is a stable IR other tools consume, firtool is a real optimising
compiler, and CUTE is Chisel.

How little the source differs is itself a finding. The per-cell body of the two kernels is identical
character for character — the loop, the index, the comment, and both complex products:

```scala
val productRe = io.aRe(row) * io.bRe(col) - io.aIm(row) * io.bIm(col)
val productIm = io.aRe(row) * io.bIm(col) + io.aIm(row) * io.bRe(col)
```

Only the assignments diverge, and only where the binary point is involved:

```scala
// Chisel: the point is tracked by hand.
accRe(index) := accRe(index) + productRe
io.outRe(index) := (accRe(index) >> fractionBits)(dataWidth - 1, 0).asSInt

// SpinalHDL: the point is in the type, and moving it has to be said out loud.
accRe(index) := (accRe(index) + productRe).truncated
io.outRe(index) := accRe(index).truncated
```

So the choice is not between two languages. It is between tracking the format by hand and having a
type that refuses to let it drift — a difference in what the compiler rejects rather than in what the
silicon costs. Stay on Chisel for the premise; the thing worth taking from SpinalHDL is not
SpinalHDL but a Q-format type with the point in it, which is a few hundred lines on top of `SInt`.
