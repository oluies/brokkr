package brokkr.model

import brokkr.model.BankConflicts.*
import scala.collection.mutable

/** The three things [[Arbiter]] left out: a per-bank request network, operator traffic competing
  * with amplitude traffic, and the writeback path.
  *
  * Two of them turn out to have arithmetic rather than simulation as their answer, which is worth
  * knowing before building a model for them.
  */
object Integration:
  /** Bandwidth with genuine per-bank request queues.
    *
    * An earlier version of this model claimed to remove head-of-line blocking and did not. It kept
    * a single shared pending buffer, so a backlog at one bank still consumed slots every other bank
    * needed — the same constraint as the baseline it was compared against. The two were nearly
    * identical by construction and their agreement was not evidence about interconnects.
    *
    * Here each bank has its own queue, `depth` entries, fed in stream order. A request to a full
    * bank stalls the generator, which is real backpressure rather than an artifact, but a backlog
    * at one bank no longer occupies anything another bank could have used.
    *
    * That makes the queue depth the interesting parameter. A slice sends all `2^k` of its accesses
    * to however many banks the mapping spreads it over, so for a mapping that concentrates, the
    * generator can only move on to the next slice once the current one fits in a queue.
    */
  def perBankQueues(
      mapping: Mapping,
      support: Seq[Int],
      slices: Int,
      depth: Int,
      issueWidth: Int
  ): Double =
    require(depth >= 1 && issueWidth >= 1, "a queue and an issue port are both needed")

    val requests = Arbiter.stream(mapping, support, slices)
    val queued = new Array[Int](mapping.banks)
    var next = 0
    var served = 0L
    var cycles = 0L

    while served < requests.length do
      (0 until mapping.banks).foreach: bank =>
        if queued(bank) > 0 then
          queued(bank) -= 1
          served += 1

      // In-order generation. It stalls on a full target bank, which is backpressure that a real
      // network also has; what it no longer does is block on a bank other than its own target.
      var issued = 0
      var stalled = false

      while !stalled && issued < issueWidth && next < requests.length do
        if queued(requests(next)) < depth then
          queued(requests(next)) += 1
          next += 1
          issued += 1
        else stalled = true

      cycles += 1

    requests.length.toDouble / cycles

  def main(args: Array[String]): Unit =
    val qubitCount = 14
    val bankBits = 5
    val width = 6
    val slices = 1 << (qubitCount - width)
    val banks = 1 << bankBits
    val mappings = Seq(
      lowBits(qubitCount, bankBits),
      xorFold(qubitCount, bankBits),
      distinct(qubitCount, bankBits),
      extendedHamming(qubitCount, bankBits)
    )

    println("== 1. Per-bank request queues ==")
    println()
    println(s"$qubitCount qubits, $banks banks, width-$width blocks, issue width $banks.")
    println("Each bank has its own queue of the given depth, fed in stream order. The baseline is")
    println("the shared-window arbiter at lookahead 1, which is the configuration that genuinely")
    println("blocks: one request at the head, and if its bank is full nothing else moves.")
    println()
    println(f"${"mapping"}%-14s${"blocked"}%9s" + Seq(4, 16, 64, 256).map(d => f"${s"d=$d"}%9s").mkString + f"${"ceiling"}%9s")

    val perBank = mappings.map: mapping =>
      val support = Schedule.worstSupport(mapping, qubitCount, width)
      val blocked = Arbiter.bandwidth(mapping, support, slices, 4, banks, 1)
      val depths = Seq(4, 16, 64, 256).map(perBankQueues(mapping, support, slices, _, banks))

      println(
        f"${mapping.name}%-14s$blocked%9.1f" + depths.map(b => f"$b%9.1f").mkString + f"$banks%9d"
      )

      mapping -> (support, depths.last)

    println()
    println(s"A slice sends all ${1 << width} of its accesses to however many banks the mapping")
    println("spreads it over, so the generator can only move on to the next slice once the current")
    println("one fits. That is why depth matters and why it matters most to the mapping that")
    println("concentrates: low bits needs a queue the size of a whole slice before it overlaps")
    println("slices at all, while a mapping that spreads needs almost nothing.")
    println()
    println("This supersedes an earlier version of this section, which compared two configurations")
    println("that shared one bounded window and were therefore nearly the same model. It reported")
    println("that a request network buys almost nothing. The numbers above say otherwise.")
    println()

    println("== 2. Operator traffic against amplitude traffic ==")
    println()
    println("A block loads its operator once and then streams the whole state past it, so the two")
    println("are not really in contention — the question is only whether the load amortises.")
    println()
    println(f"${"n"}%4s${"k"}%4s${"operator"}%12s${"amplitudes"}%13s${"operator share"}%16s")

    Seq((20, 4), (20, 6), (24, 6), (24, 8), (12, 6), (10, 6)).foreach: (n, k) =>
      val dense = Math.pow(4, k.toDouble)
      val amplitudes = Math.pow(2, n.toDouble)
      val share = 100 * dense / (dense + amplitudes)

      println(f"$n%4d$k%4d${dense.toLong}%12d${amplitudes.toLong}%13d$share%15.2f%%")

    println()
    println("At any size worth building for, the dense operator is under a percent of the traffic.")
    println("It stops being negligible only when the state is small enough to fit the operator —")
    println("n = 12 at k = 6 — which is the regime where the engine has nothing to do anyway.")
    println()

    println("== 3. The writeback path ==")
    println()
    println("Every amplitude read is transformed and written back, to the same address, hence the")
    println("same bank. So writeback doubles the traffic without changing its distribution: the")
    println("bank histogram is identical and every bandwidth figure above halves.")
    println()
    println(f"${"mapping"}%-14s${"read only"}%11s${"read+write"}%12s${"RMW"}%8s")

    perBank.foreach: (mapping, result) =>
      val read = result._2

      println(f"${mapping.name}%-14s$read%11.1f${read / 2}%12.1f$read%8.1f")

    println()
    println("The last column is what a read-modify-write memory would give, since the write has the")
    println("same address as the read that preceded it. That is a memory feature rather than an")
    println("arbiter one, and it is worth a factor of two.")
