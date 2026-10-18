package brokkr.model

import brokkr.model.BankConflicts.*
import scala.collection.mutable

/** What a memory controller actually achieves, as opposed to what the per-slice bound allows.
  *
  * [[BankConflicts]] bounds one slice: its `2^k` addresses land on banks with some multiplicity, and
  * at one access per bank per cycle that multiplicity is a floor on the slice's latency. That bound
  * is pessimistic about *throughput*, because slices are independent. A slice whose addresses pile
  * into one bank leaves every other bank idle, and the next slice can use them — if the controller
  * is allowed to have more than one slice in flight.
  *
  * So the question the bound cannot answer is not whether a mapping works, but how much buffering
  * it costs to make it work. This model answers that: issue slices through a reorder window of a
  * given depth, serve one access per bank per cycle, and measure achieved bandwidth.
  *
  * The controller modelled here is deliberately simple — any pending access may be served, so it is
  * an optimistic scheduler, and the depth it reports is a lower bound on what real hardware needs.
  * It still separates the mappings by an order of magnitude, which is the point.
  */
object Schedule:
  /** Accesses for one slice, as a count per bank. */
  def demand(mapping: Mapping, support: Seq[Int], slice: Int): Array[Int] =
    val counts = Array.fill(mapping.banks)(0)
    var base = slice
    var j = 0

    while j < support.length do
      val mask = (1 << support(j)) - 1

      base = (base & mask) | ((base & ~mask) << 1)
      j += 1

    (0 until (1 << support.length)).foreach: local =>
      var offset = 0

      support.indices.foreach(bit => if ((local >> bit) & 1) == 1 then offset |= 1 << support(bit))
      counts(mapping.bank(base + offset)) += 1

    counts

  /** Bandwidth in accesses per cycle, from running `slices` through a window `depth` slices deep. */
  def bandwidth(mapping: Mapping, support: Seq[Int], depth: Int, slices: Int): Double =
    val inFlight = mutable.ArrayBuffer.empty[Array[Int]]
    var admitted = 0
    var served = 0L
    var cycles = 0L
    val total = slices.toLong * (1 << support.length)

    while served < total do
      while inFlight.length < depth && admitted < slices do
        inFlight += demand(mapping, support, admitted)
        admitted += 1

      // Each bank serves one pending access, taken from the oldest slice that wants it, which is
      // what keeps a deep window from starving the head of the queue.
      (0 until mapping.banks).foreach: bank =>
        inFlight.find(_(bank) > 0).foreach: slice =>
          slice(bank) -= 1
          served += 1

      inFlight.filterInPlace(_.exists(_ > 0))
      cycles += 1

    total.toDouble / cycles

  /** The support this mapping handles worst, which is what sizes the buffer. */
  def worstSupport(mapping: Mapping, qubitCount: Int, width: Int): Seq[Int] =
    (0 until qubitCount).toList.combinations(width).maxBy(cycles(mapping, _))

  def main(args: Array[String]): Unit =
    // Few enough qubits that every slice can be run, rather than a prefix of them. Sampling a
    // prefix is wrong here and quietly so: a short run leaves the high address bits always zero, so
    // the banks those bits would reach never appear and the mapping looks worse than it is.
    val qubitCount = 14
    val bankBits = 5
    val width = 6
    val slices = 1 << (qubitCount - width)
    val depths = Seq(1, 2, 4, 8, 16, 32, 64)
    val mappings = Seq(
      lowBits(qubitCount, bankBits),
      xorFold(qubitCount, bankBits),
      distinct(qubitCount, bankBits),
      extendedHamming(qubitCount, bankBits)
    )

    println(s"$qubitCount qubits, ${1 << bankBits} banks, width-$width blocks, all $slices slices.")
    println("Bandwidth in accesses per cycle; the ceiling is the bank count.")
    println("Each mapping is run on the support it handles worst, which is what sizes the buffer.")
    println()
    println(f"${"mapping"}%-14s${"worst support"}%-22s" + depths.map(d => f"d=$d%-7s").mkString)

    mappings.foreach: mapping =>
      val support = worstSupport(mapping, qubitCount, width)
      val results = depths.map(bandwidth(mapping, support, _, slices))

      println(
        f"${mapping.name}%-14s${support.mkString(",")}%-22s" +
          results.map(b => f"$b%-7.1f").mkString
      )

    println()
    println("A depth that buys nothing means the banks are not all reachable. Per slice a block")
    println("touches 2^rank distinct banks; across slices the translates may still not cover the")
    println("whole bank space, and then bandwidth is capped by coverage rather than by buffering.")
    println()
    println(f"${"mapping"}%-14s${"banks/slice"}%13s${"reachable"}%11s${"of"}%5s${"ceiling"}%9s")

    mappings.foreach: mapping =>
      val support = worstSupport(mapping, qubitCount, width)
      val perSlice = demand(mapping, support, 0).count(_ > 0)
      val reachable = (0 until slices)
        .flatMap(slice => demand(mapping, support, slice).zipWithIndex.collect { case (n, b) if n > 0 => b })
        .distinct
        .length

      println(f"${mapping.name}%-14s$perSlice%13d$reachable%11d${mapping.banks}%5d$reachable%8d")

    println()
    println(f"${"mapping"}%-14s depth needed for 95%% of the reachable ceiling")

    mappings.foreach: mapping =>
      val support = worstSupport(mapping, qubitCount, width)
      val reachable = (0 until slices)
        .flatMap(slice => demand(mapping, support, slice).zipWithIndex.collect { case (n, b) if n > 0 => b })
        .distinct
        .length
      val needed = depths.find(bandwidth(mapping, support, _, slices) >= reachable * 0.95)

      println(f"${mapping.name}%-14s " + needed.map(_.toString).getOrElse("> 64"))
