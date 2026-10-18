package brokkr.model

import scotty.simulator.fusion.GateFusion

/** Can a re-tiling be hidden behind computation, or is it a wall?
  *
  * [[Refill]] counts passes over off-chip memory and leaves them as a stall. They need not be. A
  * re-tiling already streams every amplitude through the engine, which means two different kinds of
  * overlap are available, and they are worth separating because only one of them is interesting.
  *
  *   - '''Double buffering.''' Prefetch the next tile while computing on this one. Standard, and it
  *     turns a sum into a maximum: `max(compute, transfer)` rather than `compute + transfer`.
  *   - '''Folding.''' A block can be applied to amplitudes as they stream past during the
  *     re-tiling itself, so the pass carries useful work rather than only moving data. How much it
  *     can carry is set by the ratio of on-chip to off-chip bandwidth.
  *
  * The second is the one that decides the shape of the answer, because that ratio is large. An
  * on-chip banked store serves tens of amplitudes per cycle — the banking models above put it near
  * 32 — while off-chip memory delivers a few. Every amplitude that arrives can therefore have many
  * blocks' worth of arithmetic done to it before the next one lands.
  *
  * Cost here is in units of a '''state traversal''': `2^n` amplitudes read and written once.
  */
object Overlap:
  /** On-chip amplitudes served per cycle, from the banking model's best mapping. */
  val OnChip: Double = 32.0

  final case class Cost(compute: Double, transfer: Double):
    /** Sequential: compute every block, then move the state for every re-tiling. */
    def serial: Double = compute + transfer

    /** Double buffered: the two proceed together. */
    def overlapped: Double = math.max(compute, transfer)

    def transferBound: Boolean = transfer >= compute

    /** Blocks that can be folded into one transfer pass before compute becomes the limit. */
    def foldable(offChip: Double): Double = OnChip / offChip

  def cost(blocks: Int, passes: Int, offChip: Double): Cost =
    Cost(compute = blocks / OnChip, transfer = passes / offChip)

  def main(args: Array[String]): Unit =
    val qubitCount = 24
    val localQubits = 20
    val offChipRates = Seq(1.0, 2.0, 4.0, 8.0, 16.0)
    val workloads = Seq(
      ("brickwork", Refill.brickwork(qubitCount, layers = 4)),
      ("Grover", Refill.grover(qubitCount, iterations = 2)),
      ("diagonal-heavy", Refill.diagonalHeavy(qubitCount, layers = 4))
    )

    println(s"$qubitCount qubits, $localQubits local. Costs are state traversals: 2^$qubitCount")
    println(s"amplitudes read and written once. On-chip serves ${OnChip.toInt} amplitudes per cycle.")
    println()

    workloads.foreach: (name, circuit) =>
      val fused = GateFusion.fuse(circuit, maxWidth = 5)
      val plan = Refill.steps(fused)
      val blocks = fused.ops.length
      val passes = Refill.passes(plan, qubitCount, localQubits, lookahead = 64)

      println(s"$name: $blocks blocks, $passes re-tilings")
      println(f"  ${"off-chip"}%9s${"compute"}%9s${"transfer"}%10s${"serial"}%8s${"overlapped"}%12s${"saving"}%8s${"bound by"}%10s")

      offChipRates.foreach: rate =>
        val c = cost(blocks, passes, rate)
        val saving = 100 * (1 - c.overlapped / c.serial)

        println(
          f"  $rate%9.0f${c.compute}%9.2f${c.transfer}%10.2f${c.serial}%8.2f${c.overlapped}%12.2f" +
            f"$saving%7.0f%%${if c.transferBound then "transfer" else "compute"}%10s"
        )

      println()

    println("== How much work a transfer pass can carry ==")
    println()
    println("A re-tiling streams the whole state past the engine. While off-chip delivers one")
    println("amplitude, on-chip can do many blocks' arithmetic on it, so blocks fold into the pass")
    println("rather than queueing behind it.")
    println()
    println(f"${"off-chip"}%9s${"blocks per pass"}%18s${"brickwork"}%12s${"Grover"}%10s")

    val folded = workloads.map: (name, circuit) =>
      val fused = GateFusion.fuse(circuit, maxWidth = 5)
      val passes = Refill.passes(Refill.steps(fused), qubitCount, localQubits, lookahead = 64)

      (name, fused.ops.length, passes)

    offChipRates.foreach: rate =>
      val capacity = OnChip / rate

      def residual(entry: (String, Int, Int)): String =
        val (_, blocks, passes) = entry
        val carried = math.min(blocks.toDouble, capacity * passes)

        f"${100 * carried / blocks}%.0f%%"

      println(f"$rate%9.0f${capacity}%18.0f${residual(folded(0))}%12s${residual(folded(1))}%10s")

    println()
    println("At one amplitude per cycle off chip, a single pass carries 32 blocks' worth of work —")
    println("more than either circuit has per re-tiling. So with folding the computation disappears")
    println("into the transfers entirely, and the cost of a spilled circuit is exactly its pass")
    println("count. That is a comfortable result and an uncomfortable one: the engine's datapath,")
    println("which the rest of this page is about, stops being the thing that matters.")
