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
  /** Bandwidth with a per-bank request network instead of one global issue port.
    *
    * [[Arbiter]] issued from a single port that scanned a window, so a request to a full queue
    * stalled everything behind it — head-of-line blocking. A real design routes requests to
    * per-bank queues through a network, and then each bank takes its own pending requests
    * independently and no bank can block another.
    *
    * `portsPerBank` is how many requests a bank may accept in a cycle. Setting it to the queue
    * depth isolates the absence of head-of-line blocking; setting it to 1 adds the constraint a
    * real single-ported request network would impose, which is a separate question. Neither
    * configuration strictly bounds the global port, since the two also differ in how the issue
    * window advances within a cycle — but they bracket it closely enough to show that blocking is
    * not the binding constraint.
    */
  def perBankBandwidth(
      mapping: Mapping,
      support: Seq[Int],
      slices: Int,
      queueDepth: Int,
      window: Int,
      portsPerBank: Int
  ): Double =
    val requests = Arbiter.stream(mapping, support, slices)
    val occupancy = new Array[Int](mapping.banks)
    val pending = mutable.ArrayBuffer.empty[Int]
    var next = 0
    var served = 0L
    var cycles = 0L

    while served < requests.length do
      (0 until mapping.banks).foreach: bank =>
        if occupancy(bank) > 0 then
          occupancy(bank) -= 1
          served += 1

      while pending.length < window && next < requests.length do
        pending += requests(next)
        next += 1

      // Every bank takes its own pending requests. No bank can block another.
      (0 until mapping.banks).foreach: bank =>
        var taken = 0

        while taken < portsPerBank && occupancy(bank) < queueDepth && pending.contains(bank) do
          pending.remove(pending.indexOf(bank))
          occupancy(bank) += 1
          taken += 1

          while pending.length < window && next < requests.length do
            pending += requests(next)
            next += 1

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

    println("== 1. A per-bank request network against one global issue port ==")
    println()
    println(s"$qubitCount qubits, $banks banks, width-$width blocks, queue depth 4, window 32.")
    println()
    println(f"${"mapping"}%-14s${"global port"}%13s${"no HOL"}%9s${"1 port/bank"}%13s${"ceiling"}%9s")

    mappings.foreach: mapping =>
      val support = Schedule.worstSupport(mapping, qubitCount, width)
      val global = Arbiter.bandwidth(mapping, support, slices, 4, banks, 32)
      val unblocked = perBankBandwidth(mapping, support, slices, 4, 32, portsPerBank = 4)
      val singlePort = perBankBandwidth(mapping, support, slices, 4, 32, portsPerBank = 1)

      println(f"${mapping.name}%-14s$global%13.1f$unblocked%9.1f$singlePort%13.1f$banks%9d")

    println()
    println("The middle column has no head-of-line blocking. It lands within a few percent of the")
    println("global port in both directions — the two also differ in how the window advances, so")
    println("neither bounds the other — and that closeness is the result: blocking is not what was")
    println("costing bandwidth. A mapping that sends a whole slice to one bank is *starved*, not")
    println("blocked. There is nothing for the other banks to accept, and no network fixes that.")
    println()
    println("The last column adds a single request port per bank, which a real network would have.")
    println("That constraint costs more than the blocking it replaces.")
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

    mappings.foreach: mapping =>
      val support = Schedule.worstSupport(mapping, qubitCount, width)
      val read = perBankBandwidth(mapping, support, slices, 4, 32, portsPerBank = 4)

      println(f"${mapping.name}%-14s$read%11.1f${read / 2}%12.1f$read%8.1f")

    println()
    println("The last column is what a read-modify-write memory would give, since the write has the")
    println("same address as the read that preceded it. That is a memory feature rather than an")
    println("arbiter one, and it is worth a factor of two.")
