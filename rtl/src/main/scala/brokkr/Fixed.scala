package brokkr

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
  * `UnitCoefficientSpec` is the regression that keeps it that way.
  */
object Fixed {
  def fractionBits(dataWidth: Int): Int = dataWidth - 2

  /** A coefficient of exactly one, which is the whole reason for the second integer bit. */
  def one(dataWidth: Int): BigInt = BigInt(1) << fractionBits(dataWidth)

  def largestSigned(dataWidth: Int): BigInt = (BigInt(1) << (dataWidth - 1)) - 1
}
