package brokkr.model

/** Can the amplitude store serve a block's `2^k` strided addresses without bank conflicts?
  *
  * This is the last unanswered question in the design assessment, and it turns out to have an exact
  * answer rather than a statistical one.
  *
  * ==The structure==
  * Applying a fused block means visiting every slice of the state. Within a slice the `2^k`
  * addresses differ only in the bits at the supported qubit positions, so the set of addresses is
  * `base + V` where `V` is the linear span over GF(2) of the basis vectors `e_q`, `q` in the
  * support. Addition is XOR, because `base` is zero at exactly those positions.
  *
  * A bank mapping worth building is linear: `bank(address) = L · address` over GF(2), which in
  * hardware is a tree of XOR gates and nothing else. Linearity has a consequence that collapses the
  * whole problem: `bank(base + v) = bank(base) XOR bank(v)`, so the *multiset* of banks a slice
  * touches is a translate of `L(V)` and its shape does not depend on the slice at all. Every slice
  * conflicts identically.
  *
  * `L(V)` is a subgroup of size `2^rank`, where `rank` is the GF(2) rank of `L`'s columns at the
  * supported positions, and each bank in it is hit `2^(k-rank)` times. So:
  *
  * {{{
  * cycles per slice = 2^(k - rank(columns of L at the support))
  * }}}
  *
  * with `rank` at most `min(k, bankBits)`. The floor is `2^k / banks` — you cannot read more than
  * one word per bank per cycle — and it is reached exactly when the columns at the support are
  * linearly independent.
  *
  * ==So the design question is a coding-theory question==
  * A mapping is conflict-free for every support of size `k` if and only if every `k` columns of `L`
  * are linearly independent — that is, `L` is the parity-check matrix of a code with minimum
  * distance at least `k + 1`. Taking the low address bits, the obvious choice, is the worst
  * possible one: its columns at positions beyond `bankBits` are *zero*, so any block touching a
  * high qubit degenerates completely.
  */
object BankConflicts:
  /** A linear bank mapping: column `j` is the bank-bit pattern contributed by address bit `j`. */
  final case class Mapping(name: String, bankBits: Int, columns: Array[Int]):
    def banks: Int = 1 << bankBits

    def bank(address: Int): Int =
      var acc = 0
      var j = 0

      while j < columns.length do
        if ((address >> j) & 1) == 1 then acc ^= columns(j)
        j += 1

      acc

  /** GF(2) rank of the columns at `support`. */
  def rank(mapping: Mapping, support: Seq[Int]): Int =
    var pivots = List.empty[Int]

    support.foreach: position =>
      var value = mapping.columns(position)

      pivots.foreach(p => if (value ^ p) < value then value ^= p)

      if value != 0 then pivots = (value :: pivots).sortBy(-_)

    pivots.length

  /** Cycles to gather one slice, assuming one access per bank per cycle. */
  def cycles(mapping: Mapping, support: Seq[Int]): Int = 1 << (support.length - rank(mapping, support))

  /** The floor: you cannot read `2^k` words from `banks` banks faster than this. */
  def floor(width: Int, bankBits: Int): Int = 1 << math.max(0, width - bankBits)

  /** Counted directly, to check the algebra above rather than trust it. */
  def cyclesByCounting(mapping: Mapping, support: Seq[Int], slice: Int, qubitCount: Int): Int =
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

    counts.max

  // ---- the mappings worth comparing -------------------------------------------------------

  /** `bank = address & (banks - 1)`. The obvious choice, and the worst one. */
  def lowBits(addressBits: Int, bankBits: Int): Mapping =
    Mapping(
      "low bits",
      bankBits,
      Array.tabulate(addressBits)(j => if j < bankBits then 1 << j else 0)
    )

  /** XOR of the address's `bankBits`-wide chunks. Cheap, and much better than low bits. */
  def xorFold(addressBits: Int, bankBits: Int): Mapping =
    Mapping("xor fold", bankBits, Array.tabulate(addressBits)(j => 1 << (j % bankBits)))

  /** Distinct non-zero columns: every two are independent, so conflict-free up to k = 2 — but only
    * while they last. There are `2^b - 1` non-zero patterns, so beyond that many address bits the
    * columns must repeat and a pair of repeats collides.
    */
  def distinct(addressBits: Int, bankBits: Int): Mapping =
    val patterns = (1 << bankBits) - 1

    Mapping("distinct", bankBits, Array.tabulate(addressBits)(j => (j % patterns) + 1))

  /** Columns `(1, x)` with `x` distinct: every three are independent, so conflict-free up to k = 3.
    *
    * The parity-check matrix of an extended Hamming code. A leading one in every column makes any
    * odd number of them sum to something non-zero, and the distinct tails handle the even case.
    */
  def extendedHamming(addressBits: Int, bankBits: Int): Mapping =
    val tailBits = bankBits - 1
    val tails = 1 << tailBits

    Mapping(
      "ext. Hamming",
      bankBits,
      Array.tabulate(addressBits)(j => (1 << tailBits) | (j % tails))
    )

  /** Columns `(1, x, x^3)` over GF(2^m): a BCH parity check with designed distance 5, so every four
    * are independent and it is conflict-free up to k = 4.
    */
  def bch(addressBits: Int, fieldBits: Int): Mapping =
    val field = GF2(fieldBits)

    Mapping(
      s"BCH d=5",
      1 + 2 * fieldBits,
      Array.tabulate(addressBits): j =>
        val x = field.element(j)

        (1 << (2 * fieldBits)) | (x << fieldBits) | field.cube(x)
    )

  /** Just enough GF(2^m) to build the BCH columns. */
  final class GF2(val bits: Int):
    private val size = 1 << bits
    private val primitive = bits match
      case 3 => 0xb // x^3 + x + 1
      case 4 => 0x13 // x^4 + x + 1
      case 5 => 0x25 // x^5 + x^2 + 1
      case 6 => 0x43 // x^6 + x + 1
      case _ => throw IllegalArgumentException(s"no primitive polynomial wired in for GF(2^$bits)")

    def multiply(a: Int, b: Int): Int =
      var result = 0
      var x = a
      var y = b

      while y != 0 do
        if (y & 1) == 1 then result ^= x
        x <<= 1
        if (x & size) != 0 then x ^= primitive
        y >>= 1

      result

    def cube(a: Int): Int = multiply(a, multiply(a, a))

    /** The `n`-th element in a fixed order; any injection into the field will do. */
    def element(n: Int): Int = n % size
