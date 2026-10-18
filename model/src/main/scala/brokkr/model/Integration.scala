package brokkr.model

import brokkr.model.BankConflicts.*

/** The three things [[Arbiter]] left out: a per-bank request network, operator traffic competing
  * with amplitude traffic, and the writeback path.
  *
  * Two of them turn out to have arithmetic rather than simulation as their answer, which is worth
  * knowing before building a model for them.
  */
object Integration:
  /** Bandwidth against per-bank queue depth, with strictly in-order generation.
    *
    * This is a queue-depth sweep and nothing more, which is worth stating plainly because two
    * earlier versions of this model claimed to measure something else.
    *
    * The generator walks the stream in order and stops at the first request whose target bank is
    * full. That is head-of-line blocking, and giving each bank its own queue does not remove it —
    * a full bank still halts requests behind it that were bound elsewhere. At equal depth and issue
    * width this is algorithmically the same as [[Arbiter.bandwidth]] at `lookahead = 1`, and
    * produces the same numbers.
    *
    * So nothing here isolates a request network. What it does measure, cleanly, is how much deeper
    * queues are worth when the generator cannot bypass: a great deal, and the amount depends on how
    * far a mapping spreads a slice.
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

      // In-order generation: it stops at the first full target bank. Requests behind it are held
      // even when their own banks are idle, so this is head-of-line blocking and the per-bank
      // queues do not remove it. Only the depth is under study here.
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

    println("== 1. Queue depth, with in-order generation ==")
    println()
    println(s"$qubitCount qubits, $banks banks, width-$width blocks, issue width $banks.")
    println("Each bank has its own queue of the given depth. The generator is strictly in order and")
    println("stops at the first full target bank, so this measures depth alone — it is the same")
    println("algorithm as the arbiter at lookahead 1, and at equal depth it gives the same numbers.")
    println()
    println(f"${"mapping"}%-14s" + Seq(4, 16, 64, 256).map(d => f"${s"d=$d"}%9s").mkString + f"${"ceiling"}%9s")

    val perBank = mappings.map: mapping =>
      val support = Schedule.worstSupport(mapping, qubitCount, width)
      val depths = Seq(4, 16, 64, 256).map(perBankQueues(mapping, support, slices, _, banks))

      println(f"${mapping.name}%-14s" + depths.map(b => f"$b%9.1f").mkString + f"$banks%9d")

      mapping -> depths.last

    println()
    println(s"A slice sends all ${1 << width} of its accesses to however many banks the mapping")
    println("spreads it over, and an in-order generator can only reach the next slice once the")
    println("current one fits. So depth matters most to the mapping that concentrates: low bits")
    println("needs a queue the size of a whole slice before it overlaps slices at all, while a")
    println("mapping that spreads is most of the way there by depth 16.")
    println()
    println("Depth is one lever. Bypass is the other, and Arbiter sweeps it. What is *not* measured")
    println("anywhere here is a request network as such: at equal depth and issue discipline, giving")
    println("each bank its own queue changes nothing, because the generator still stalls on a full")
    println("target. Two earlier versions of this section claimed otherwise and were wrong.")
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

    perBank.foreach: (mapping, read) =>
      println(f"${mapping.name}%-14s$read%11.1f${read / 2}%12.1f$read%8.1f")

    println()
    println("The last column is what a read-modify-write memory would give, since the write has the")
    println("same address as the read that preceded it. That is a memory feature rather than an")
    println("arbiter one, and it is worth a factor of two.")
