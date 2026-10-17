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
