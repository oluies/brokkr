package brokkr.model

import brokkr.model.BankConflicts.*

/** How many banks does conflict-free gathering need, and what does falling short cost?
  *
  * {{{
  * ./mill model.runMain brokkr.model.BankReport
  * }}}
  */
object BankReport:
  val QubitCount: Int = 20

  def main(args: Array[String]): Unit =
    checkAlgebraAgainstCounting(lowBits(12, 4))

    println(s"State of $QubitCount qubits. Every support of each width, exhaustively.")
    println("Cycles are per slice at one access per bank per cycle; the floor is 2^k / banks.")
    println()
    println("== What 32 banks buys ==")
    println()
    report(bankBits = 5)

    println("== Banks needed for conflict-free gathering at every support ==")
    println()
    // Each family is searched over its own parameter; the entry is the bank bits that result.
    val families = Seq[(String, Seq[Mapping])](
      "low bits" -> (1 to 14).map(lowBits(QubitCount, _)),
      "xor fold" -> (1 to 14).map(xorFold(QubitCount, _)),
      "distinct" -> (1 to 14).map(distinct(QubitCount, _)),
      "ext. Hamming" -> (1 to 14).map(extendedHamming(QubitCount, _)),
      "BCH d=5" -> (3 to 6).map(bch(QubitCount, _))
    )

    println(f"${"width k"}%9s" + families.map(f => f"${f._1}%14s").mkString)

    (1 to 6).foreach: width =>
      val needed = families.map: (_, candidates) =>
        candidates
          .find(isConflictFree(_, width))
          .map(_.bankBits.toString)
          .getOrElse("none")

      println(f"$width%9d" + needed.map(n => f"$n%14s").mkString)

    println()
    println("Entries are bank bits, so 5 means 32 banks, and the criterion is that *every* support")
    println("of that width gathers in a single cycle — not that the average one does.")

  def report(bankBits: Int): Unit =
    val mappings = Seq(
      lowBits(QubitCount, bankBits),
      xorFold(QubitCount, bankBits),
      distinct(QubitCount, bankBits),
      extendedHamming(QubitCount, bankBits)
    )

    println(f"${"mapping"}%-14s${"k"}%3s${"floor"}%7s${"worst"}%7s${"mean"}%8s${"vs floor"}%10s${"optimal"}%9s")

    mappings.foreach: mapping =>
      (1 to 6).foreach: width =>
        val best = floor(width, mapping.bankBits)
        var worst = 0
        var total = 0L
        var count = 0L
        var optimal = 0L

        (0 until QubitCount).toList.combinations(width).foreach: support =>
          val c = cycles(mapping, support)

          worst = math.max(worst, c)
          total += c
          count += 1
          if c == best then optimal += 1

        val mean = total.toDouble / count

        println(
          f"${mapping.name}%-14s$width%3d$best%7d$worst%7d$mean%8.2f" +
            f"${mean / best}%9.2fx${100.0 * optimal / count}%8.1f%%"
        )

      println()

  /** Can every support of this width be gathered in a single cycle?
    *
    * Reaching the floor is not the right test — the floor itself falls as banks are removed, so
    * two banks trivially "achieve" it. One cycle is the question worth asking, and it needs both
    * `bankBits >= width` and every `width` columns of the mapping independent.
    */
  def isConflictFree(mapping: Mapping, width: Int): Boolean =
    (0 until QubitCount).toList.combinations(width).forall(cycles(mapping, _) == 1)

  /** The report rests on cycles being `2^(k - rank)` and slice-independent, so check that against
    * counting banks directly rather than trusting the algebra.
    */
  def checkAlgebraAgainstCounting(mapping: Mapping): Unit =
    val random = scala.util.Random(20261018L)

    (0 until 200).foreach: _ =>
      val width = 1 + random.nextInt(4)
      val support = random.shuffle((0 until 12).toList).take(width).sorted
      val predicted = cycles(mapping, support)

      (0 until 8).foreach: slice =>
        require(
          cyclesByCounting(mapping, support, slice, 12) == predicted,
          s"support $support slice $slice disagrees with 2^(k - rank)"
        )

    println("Checked: counting banks directly agrees with 2^(k - rank), for every slice.")
    println()
