package brokkr

import chisel3._
import chisel3.util.log2Up

/** The fixed-point format the datapaths share, in one place because it is an invariant rather than
  * a per-kernel choice.
  *
  * Q2.`fractionBits`: a stored integer `x` means `x / 2^fractionBits`, so the range is `[-2, 2)`.
  *
  * Two integer bits rather than one, which costs a bit of precision and buys correctness. Amplitudes
  * live in the unit disc and would fit in Q1.f, but coefficients do not — they sit *on* the unit
  * circle constantly. Every `X`, `CNOT`, `SWAP` and Toffoli is a permutation matrix whose non-zero
  * entries are exactly 1, and `Z` and `CZ` contribute exact −1s. Q1.f stops at `1 − 2^-f`, so a
  * coefficient of 1 wraps to −1 and silently flips the sign of the most common coefficient there is.
  *
  * `UnitCoefficientSpec` is the regression that keeps it that way, and [[QFormat]] is the type that
  * would have made it unwriteable.
  */
object Fixed {
  def fractionBits(dataWidth: Int): Int = dataWidth - 2

  /** A coefficient of exactly one, which is the whole reason for the second integer bit. */
  def one(dataWidth: Int): BigInt = BigInt(1) << fractionBits(dataWidth)

  def largestSigned(dataWidth: Int): BigInt = (BigInt(1) << (dataWidth - 1)) - 1

  /** The shared working format at a given width: Q2.`dataWidth - 2`. */
  def working(dataWidth: Int): QFormat = QFormat(integerBits = 2, fractionBits = fractionBits(dataWidth))
}

/** A Q-format with the binary point in the type, rather than tracked by hand.
  *
  * The SpinalHDL control in section 11 of the assessment found one real difference between the two
  * HDLs, and it was not area — it was that `SFix` carries its own exponent, so the compiler refuses
  * an assignment that would move the point, while Chisel has no fixed-point type at all and leaves
  * every shift to the author. The conclusion recorded there was that the thing worth taking from
  * SpinalHDL is not SpinalHDL but a Q-format type. This is it.
  *
  * Three properties are what matter, each corresponding to a way the hand-written version could go
  * wrong:
  *
  *   - '''a format knows what it can represent'''. [[requireRepresentable]] rejects Q1.23 holding
  *     1.0 at elaboration. That is exactly the bug that flipped the sign of every permutation
  *     coefficient and took an exact-model check to find;
  *   - '''products and sums compute their own format'''. The 59-bit accumulator in [[ComplexGemm]]
  *     used to be asserted as `2 * dataWidth + log2Ceil(maxDepth) + 1`; it now falls out of
  *     `(working * working - working * working).accumulating(maxDepth)`, which is the arithmetic
  *     being performed rather than a number that has to be checked against it;
  *   - '''narrowing is explicit'''. [[Q.to]] is the only way to change format, so a dropped or
  *     doubled shift is a type error rather than a wrong answer.
  *
  * `integerBits` includes the sign, so Q2.22 is 24 bits with the range `[-2, 2)`.
  */
case class QFormat(integerBits: Int, fractionBits: Int) {
  require(integerBits >= 1, "one of the integer bits is the sign")
  require(fractionBits >= 0, "a negative fraction width is not a format")

  val width: Int = integerBits + fractionBits

  private def scale: BigDecimal = BigDecimal(BigInt(1) << fractionBits)

  /** The largest and smallest values this format holds, as exact rationals. */
  def max: BigDecimal = BigDecimal((BigInt(1) << (width - 1)) - 1) / scale

  def min: BigDecimal = BigDecimal(-(BigInt(1) << (width - 1))) / scale

  def representable(value: BigDecimal): Boolean = {
    val raw = value * scale

    raw.isWhole && raw >= BigDecimal(-(BigInt(1) << (width - 1))) &&
    raw <= BigDecimal((BigInt(1) << (width - 1)) - 1)
  }

  /** The stored integer for an exactly representable value; refuses anything else.
    *
    * This is the guard the Q1.23 bug needed: `Fixed.working(24)` admits 1.0, `QFormat(1, 23)` does
    * not, and the difference is an elaboration error rather than a sign flip in simulation.
    */
  def requireRepresentable(value: BigDecimal): BigInt = {
    require(
      representable(value),
      s"$value is not exact in Q$integerBits.$fractionBits, whose range is [$min, $max]"
    )

    (value * scale).toBigInt
  }

  def literal(value: BigDecimal): Q = Q(this, requireRepresentable(value).S(width.W))

  /** Take an existing `SInt` to be in this format. The one unchecked operation here, so it is the
    * one place a format error can still be introduced — by design, since ports and memories are
    * plain `SInt` and something has to attach meaning to them.
    */
  def apply(bits: SInt): Q = Q(this, bits)

  /** A product of two formats: widths add, so the integer parts add. Q2.22 × Q2.22 is Q4.44. */
  def *(that: QFormat): QFormat = QFormat(integerBits + that.integerBits, fractionBits + that.fractionBits)

  /** A sum or difference at the same point, which can carry one bit. */
  def +(that: QFormat): QFormat = {
    require(fractionBits == that.fractionBits, s"cannot add Q$integerBits.$fractionBits to $that")

    QFormat(math.max(integerBits, that.integerBits) + 1, fractionBits)
  }

  def -(that: QFormat): QFormat = this + that

  /** Room for `terms` values of this format summed without overflow. */
  def accumulating(terms: Int): QFormat = {
    require(terms >= 1, "an accumulation has at least one term")

    QFormat(integerBits + log2Up(terms), fractionBits)
  }

  override def toString: String = s"Q$integerBits.$fractionBits"
}

/** A hardware value and the format it is in. */
case class Q(format: QFormat, bits: SInt) {
  def *(that: Q): Q = Q(format * that.format, bits * that.bits)

  def +(that: Q): Q = Q(format + that.format, bits +& that.bits)

  def -(that: Q): Q = Q(format - that.format, bits -& that.bits)

  /** Accumulate into a format with headroom, keeping its width: the wide accumulator pattern.
    *
    * `target` has to hold the sum already, which is what [[QFormat.accumulating]] computes, so no
    * width grows here and the result rounds once on the way out rather than once per term.
    */
  def accumulate(that: Q, target: QFormat): Q = {
    require(format == target, s"accumulating into $target from $format")
    require(
      that.format.fractionBits == target.fractionBits,
      s"cannot accumulate ${that.format} into $target: the points differ"
    )
    require(
      that.format.integerBits <= target.integerBits,
      s"${that.format} does not fit in $target; widen the accumulator"
    )

    Q(target, (bits + that.bits)(target.width - 1, 0).asSInt)
  }

  /** Sign-extend into a format with the same point and more integer bits. Always exact. */
  def widen(target: QFormat): Q = {
    require(
      target.fractionBits == format.fractionBits && target.integerBits >= format.integerBits,
      s"$target is not a widening of $format"
    )

    Q(target, bits.pad(target.width))
  }

  /** Change format explicitly, truncating the fraction and wrapping the integer part.
    *
    * Truncation rather than rounding because that is one gate cheaper and the error is already far
    * below what the format costs; wrapping rather than saturating because the widths above are
    * chosen so overflow cannot happen, and a saturating adder would be paying for a case that does
    * not arise. Both choices match what the kernels did by hand, which is why this change is
    * bit-identical rather than merely equivalent.
    */
  def to(target: QFormat): Q = {
    val shifted =
      if (target.fractionBits <= format.fractionBits) bits >> (format.fractionBits - target.fractionBits)
      else (bits << (target.fractionBits - format.fractionBits)).asSInt

    Q(target, shifted(target.width - 1, 0).asSInt)
  }
}
