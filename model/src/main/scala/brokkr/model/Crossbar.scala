package brokkr.model

import brokkr.model.BankConflicts.*

/** What network is needed to route amplitudes from banks to lanes?
  *
  * This is the other half of the shuffle question, and it has a different answer from the operator
  * permutation. Within a slice, lane `l` holds the amplitude at `base + offset(l)`, so its bank is
  *
  * {{{
  * bank(l) = L(base) XOR L(offset(l))
  * }}}
  *
  * `offset` is linear in `l` — bit `j` of `l` selects `e_q(j)` — so `L(offset(·))` is a linear map
  * from lane index to bank index whose matrix is literally `L`'s columns at the support, and the
  * whole lane-to-bank map is that linear part plus a constant `L(base)` that depends on the slice.
  *
  * So the bank-to-lane crossbar never has to route an arbitrary permutation. It routes an
  * '''affine''' one, always, by construction. That is a strictly smaller class than the operator
  * permutations the permute path has to handle, where [[brokkr.model.BankConflicts]]'s companion
  * study found 58.8% arbitrary — and it is a strictly larger class than the bit permutations an
  * Omega network routes natively, unless the mapping's columns happen to be unit vectors.
  *
  * This classifies them to check that reasoning rather than assert it.
  */
object Crossbar:
  enum Shape:
    case BitPermutation, Affine, NotAPermutation

  /** Lane `l` is served by this bank, for one slice. */
  def laneToBank(mapping: Mapping, support: Seq[Int], slice: Int): Array[Int] =
    var base = slice
    var j = 0

    while j < support.length do
      val mask = (1 << support(j)) - 1

      base = (base & mask) | ((base & ~mask) << 1)
      j += 1

    Array.tabulate(1 << support.length): local =>
      var offset = 0

      support.indices.foreach(bit => if ((local >> bit) & 1) == 1 then offset |= 1 << support(bit))
      mapping.bank(base + offset)

  def classify(mapping: Mapping, support: Seq[Int], slice: Int): Shape =
    val lanes = laneToBank(mapping, support, slice)
    val width = support.length

    if lanes.distinct.length != lanes.length then Shape.NotAPermutation
    else
      // Affine means lane -> constant XOR (linear in lane). Recover both and check every lane.
      val constant = lanes(0)
      val columns = (0 until width).map(bit => lanes(1 << bit) ^ constant)
      val affine = lanes.indices.forall: lane =>
        val image = (0 until width).foldLeft(constant): (acc, bit) =>
          if ((lane >> bit) & 1) == 1 then acc ^ columns(bit) else acc

        image == lanes(lane)

      if !affine then Shape.NotAPermutation
      else if columns.forall(c => Integer.bitCount(c) == 1) && constant == 0 then Shape.BitPermutation
      else Shape.Affine

  def main(args: Array[String]): Unit =
    val qubitCount = 14
    val width = 4
    // As many banks as lanes, so a conflict-free gather is exactly a permutation of lanes onto
    // banks and the question "what network routes it" is well posed.
    val bankBits = width
    val mappings = Seq(
      lowBits(qubitCount, bankBits),
      xorFold(qubitCount, bankBits),
      distinct(qubitCount, bankBits),
      extendedHamming(qubitCount, bankBits)
    )

    println(s"$qubitCount qubits, width-$width blocks, ${1 << bankBits} banks — one bank per lane.")
    println("Every support, every slice. A gather that conflicts is not a permutation of lanes at")
    println("all, and is counted separately.")
    println()
    println(f"${"mapping"}%-14s${"bit perm"}%10s${"affine"}%10s${"conflicting"}%13s")

    mappings.foreach: mapping =>
      val tally = scala.collection.mutable.Map[Shape, Int]().withDefaultValue(0)

      (0 until qubitCount).toList.combinations(width).foreach: support =>
        (0 until 16).foreach(slice => tally(classify(mapping, support, slice)) += 1)

      val total = tally.values.sum.toDouble

      println(
        f"${mapping.name}%-14s${100 * tally(Shape.BitPermutation) / total}%9.1f%%" +
          f"${100 * tally(Shape.Affine) / total}%9.1f%%${100 * tally(Shape.NotAPermutation) / total}%12.1f%%"
      )

    println()
    println("Nothing is ever arbitrary: a gather is affine or it does not fit in one cycle. So the")
    println("bank-to-lane crossbar is a strictly easier problem than the operator permutation the")
    println("permute path routes, where 58.8% of blocks are arbitrary. A Benes network handles both")
    println("and costs 1.4% of a datapath, so the structure is a reason for confidence rather than")
    println("an optimisation to chase.")
