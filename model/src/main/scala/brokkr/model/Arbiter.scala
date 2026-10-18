package brokkr.model

import brokkr.model.BankConflicts.*
import scala.collection.mutable

/** A bank arbiter with the constraints the earlier schedule model left out.
  *
  * [[Schedule]] allowed any pending access to be served in any order, which is an arbiter with
  * unlimited reordering and unlimited queues. Real hardware has neither. This model adds the three
  * constraints that actually bite:
  *
  *   - '''finite per-bank queues''', so a burst at one bank fills it;
  *   - '''finite issue width''', since the address generator emits a fixed number of addresses per
  *     cycle;
  *   - '''in-order issue''', or issue with a small lookahead window, rather than free reordering.
  *
  * The third is the one that matters. With in-order issue a blocked request stalls everything
  * behind it even though other banks sit idle, which is head-of-line blocking, and it is exactly
  * what the optimistic model assumed away.
  */
object Arbiter:
  /** Every (slice, lane) access in order, as the bank each one wants. */
  def stream(mapping: Mapping, support: Seq[Int], slices: Int): Array[Int] =
    val width = 1 << support.length
    val out = new Array[Int](slices * width)

    (0 until slices).foreach: slice =>
      val addresses = BankConflicts.addresses(support, slice)

      (0 until width).foreach(local => out(slice * width + local) = mapping.bank(addresses(local)))

    out

  /** Bandwidth in accesses per cycle.
    *
    * `lookahead` is how far past a blocked request the issue logic may scan, and nothing else. It
    * is deliberately not the window capacity: tying the two together caps throughput at `lookahead`
    * accesses per cycle, which produces a table that looks like a statement about bank mappings and
    * is really a statement about the cap. The window here is sized to exercise the issue width and
    * is topped up as it drains; only the scan distance is bounded.
    *
    * @param lookahead blocked requests the issue logic may scan past. 1 is strictly in-order —
    *                  issue the whole issuable prefix, stop at the first blocked request.
    */
  def bandwidth(
      mapping: Mapping,
      support: Seq[Int],
      slices: Int,
      queueDepth: Int,
      issueWidth: Int,
      lookahead: Int
  ): Double =
    require(lookahead >= 1, s"a lookahead of $lookahead would never fill the window")
    require(queueDepth >= 1, s"a queue depth of $queueDepth would never accept a request")
    require(issueWidth >= 1, s"an issue width of $issueWidth would never issue one")

    val requests = stream(mapping, support, slices)
    val occupancy = new Array[Int](mapping.banks)
    val window = mutable.ArrayBuffer.empty[Int]
    // Enough buffer that the issue width is reachable even when the whole scan distance is
    // occupied by blocked requests.
    val capacity = issueWidth + lookahead
    var next = 0
    var served = 0L
    var cycles = 0L

    def refill(): Unit =
      while window.length < capacity && next < requests.length do
        window += requests(next)
        next += 1

    while served < requests.length do
      // Banks serve first, so a queue freed this cycle can be refilled in the same one.
      (0 until mapping.banks).foreach: bank =>
        if occupancy(bank) > 0 then
          occupancy(bank) -= 1
          served += 1

      refill()

      // Blocked requests stay at positions 0 until scanned, so bounding `scanned` by `lookahead`
      // bounds exactly how many of them may be passed over. Successful issues top the window back
      // up, which is what lets the issue width be reached without widening the scan.
      var issued = 0
      var scanned = 0

      while issued < issueWidth && scanned < math.min(window.length, lookahead) do
        if occupancy(window(scanned)) < queueDepth then
          occupancy(window(scanned)) += 1
          window.remove(scanned)
          issued += 1
          refill()
        else scanned += 1

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

    println(s"$qubitCount qubits, $banks banks, width-$width blocks, all $slices slices.")
    println(s"Issue width $banks, per-bank queue depth 4. Bandwidth in accesses per cycle.")
    println("Each mapping on the support with the worst per-slice conflict count — which is not")
    println("quite the support with the worst bandwidth, since among supports tied on conflicts the")
    println("achieved rate still varies with bank coverage. These are bad supports, not the worst.")
    println()
    println(f"${"mapping"}%-14s${"in order"}%10s${"look 8"}%9s${"look 32"}%9s${"look 256"}%10s${"ceiling"}%9s")

    mappings.foreach: mapping =>
      val support = Schedule.worstSupport(mapping, qubitCount, width)
      val results = Seq(1, 8, 32, 256).map(bandwidth(mapping, support, slices, 4, banks, _))

      println(
        f"${mapping.name}%-14s" + results.map(b => f"$b%9.1f").mkString + f"$banks%9d"
      )

    println()
    println("== What a deeper per-bank queue buys, at a lookahead of 32 ==")
    println()
    println(f"${"mapping"}%-14s" + Seq(1, 2, 4, 8, 16).map(d => f"q=$d%-8s").mkString)

    mappings.foreach: mapping =>
      val support = Schedule.worstSupport(mapping, qubitCount, width)
      val results = Seq(1, 2, 4, 8, 16).map(bandwidth(mapping, support, slices, _, banks, 32))

      println(f"${mapping.name}%-14s" + results.map(b => f"$b%-8.1f").mkString)
