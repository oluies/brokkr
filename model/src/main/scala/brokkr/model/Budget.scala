package brokkr.model

import brokkr.model.BankConflicts.*

/** Where to spend a fixed buffer budget: per-bank queue depth, or the reorder window?
  *
  * This is the question the earlier interconnect claims were groping at and never posed properly.
  * [[Arbiter]] already models a bypassing generator with per-bank queues — its `occupancy` array is
  * the per-bank queues, its window is the reorder buffer, and `lookahead` is how far the generator
  * may bypass a full bank. Nothing further had to be built; what was missing was a fair comparison.
  *
  * Fair means equal storage. A design has some number of request slots to spend, and it can
  * partition them across banks, where a slot only ever serves its own bank, or pool them in a
  * reorder window, where any slot can hold any request but a blocked request occupies one until its
  * bank drains. Those are the two classic buffer organisations and they fail differently:
  * partitioned storage is stranded when traffic concentrates, pooled storage is consumed by
  * whichever bank is backed up.
  *
  * Budget here is `banks * depth + window`, the slots that actually hold requests.
  */
object Budget:
  def bandwidth(
      mapping: Mapping,
      support: Seq[Int],
      slices: Int,
      depth: Int,
      window: Int,
      issueWidth: Int
  ): Double =
    Arbiter.bandwidth(mapping, support, slices, depth, issueWidth, window)

  /** Every split of `budget` into per-bank depth and reorder window, coarsely. */
  def splits(budget: Int, banks: Int): Seq[(Int, Int)] =
    Seq(1, 2, 4, 8, 16, 32, 64)
      .filter(depth => banks * depth < budget)
      .map(depth => (depth, budget - banks * depth))

  def main(args: Array[String]): Unit =
    val qubitCount = 14
    val bankBits = 5
    val width = 6
    val slices = 1 << (qubitCount - width)
    val banks = 1 << bankBits
    val mappings = Seq(
      lowBits(qubitCount, bankBits),
      xorFold(qubitCount, bankBits),
      extendedHamming(qubitCount, bankBits)
    )
    val budgets = Seq(64, 128, 256, 512, 1024, 2048)

    println(s"$qubitCount qubits, $banks banks, width-$width blocks, issue width $banks.")
    println("A fixed budget of request slots, split between per-bank queues and the reorder")
    println("window. Every split of a given budget spends the same storage a different way.")
    println()
    println(f"${"mapping"}%-14s${"budget"}%8s${"pooled"}%9s${"split"}%9s${"partitioned"}%13s${"best"}%22s")
    println(f"${""}%-14s${""}%8s${"d=1"}%9s${"d=4"}%9s${"deepest"}%13s")

    mappings.foreach: mapping =>
      val support = Schedule.worstSupport(mapping, qubitCount, width)

      budgets.foreach: budget =>
        val options = splits(budget, banks)
        val results = options.map: (depth, window) =>
          (depth, window, bandwidth(mapping, support, slices, depth, window, banks))

        def at(depth: Int): String =
          results.find(_._1 == depth).map(r => f"${r._3}%.1f").getOrElse("-")

        val best = results.maxBy(_._3)
        val deepest = results.last

        println(
          f"${mapping.name}%-14s$budget%8d${at(1)}%9s${at(4)}%9s${f"${deepest._3}%.1f (d=${deepest._1})"}%13s" +
            f"${f"d=${best._1}, w=${best._2} -> ${best._3}%.1f"}%22s"
        )

      println()

    println("Pooled storage wins everywhere, and the reason is structural rather than close. A bank")
    println("drains one request per cycle, so a queue deeper than one buys nothing unless the")
    println("generator cannot refill it — and a generator with a large reorder window and the")
    println("freedom to bypass always can. Depth absorbs bursts that bypass has already absorbed.")
    println()
    println("So the earlier instinct to reach for per-bank queues was backwards. The slots belong")
    println("in the window, and what the bank mapping buys is how small that window can be.")
