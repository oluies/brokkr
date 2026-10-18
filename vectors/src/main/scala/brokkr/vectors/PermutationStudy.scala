package brokkr.vectors

import scala.util.Random
import scotty.quantum.Circuit
import scotty.quantum.gate.Gate
import scotty.quantum.gate.StandardGate.*
import scotty.simulator.fusion.{BlockForm, GateFusion, MonomialOperator}

/** What permutations do monomial blocks actually contain?
  *
  * The answer decides what the permute path's shuffle network has to be, and the choices differ by
  * an order of magnitude in area:
  *
  *   - a '''bit permutation''' of the index is what an Omega network does natively, in
  *     `k/2 * 2^k` switches;
  *   - an arbitrary permutation of `2^k` elements needs a Benes network, `(2k-1) * 2^(k-1)`
  *     switches, or a crossbar at `4^k`.
  *
  * An Omega network is blocking: it realises only a fraction of the `2^k!` permutations. So
  * assuming one without checking would be assuming the answer. This classifies the permutations
  * that fusion actually produces, over randomised circuits.
  */
object PermutationStudy:
  /** Where a permutation sits in the hierarchy the hardware cares about. */
  enum Shape:
    case Identity, BitPermutation, Affine, Arbitrary

  def classify(rows: Array[Int], width: Int): Shape =
    val dimension = 1 << width

    if (0 until dimension).forall(i => rows(i) == i) then Shape.Identity
    else
      // Affine over GF(2): sigma(x) = Ax XOR b. Recover b from sigma(0) and A from the basis
      // vectors, then check every point.
      val b = rows(0)
      val columns = (0 until width).map(j => rows(1 << j) ^ b)
      val affine = (0 until dimension).forall: x =>
        val image = (0 until width).foldLeft(b): (acc, j) =>
          if ((x >> j) & 1) == 1 then acc ^ columns(j) else acc

        image == rows(x)

      if !affine then Shape.Arbitrary
      else if b == 0 && columns.forall(c => Integer.bitCount(c) == 1) then Shape.BitPermutation
      else Shape.Affine

  def main(args: Array[String]): Unit =
    val random = new Random(20261018L)
    val qubitCount = 6
    val trials = 400
    val tally = scala.collection.mutable.Map[Shape, Int]().withDefaultValue(0)
    var blocks = 0

    def pick(n: Int): Seq[Int] = random.shuffle((0 until qubitCount).toList).take(n)

    // Only gates with a monomial form, so every fused block below is one: permutations and the
    // phases that ride along with them.
    def randomGate(): Gate =
      val one = pick(1).head
      val two = pick(2)
      val ascending = two.sorted
      val three = pick(3)

      random.nextInt(8) match
        case 0 => X(one)
        case 1 => Y(one)
        case 2 => CNOT(two.head, two.last)
        case 3 => SWAP(ascending.head, ascending.last)
        case 4 => ISWAP(ascending.head, ascending.last)
        case 5 => CCNOT(three.head, three(1), three.last)
        case 6 => CSWAP(three.head, three(1).min(three.last), three(1).max(three.last))
        case _ => Z(one)

    (0 until trials).foreach: _ =>
      val circuit = Circuit(Circuit.generateRegister(qubitCount), (0 until 10).map(_ => randomGate())*)

      GateFusion.fuse(circuit, maxWidth = 4).blocks.foreach: block =>
        if block.form == BlockForm.Monomial then
          val monomial = block.operator.asInstanceOf[MonomialOperator]

          blocks += 1
          tally(classify(monomial.rows, block.width)) += 1

    println(s"$blocks monomial blocks from $trials randomised circuits on $qubitCount qubits")
    println()

    Shape.values.foreach: shape =>
      val count = tally(shape)
      val share = if blocks == 0 then 0.0 else 100.0 * count / blocks

      println(f"  $shape%-16s $count%6d  $share%5.1f%%")

    println()

    val beyondBits = tally(Shape.Affine) + tally(Shape.Arbitrary)

    if tally(Shape.Arbitrary) > 0 then
      println("Arbitrary permutations occur, so an Omega network cannot route every block.")
      println("A Benes network can, at (2k-1) * 2^(k-1) switches against a crossbar's 4^k.")
    else if beyondBits > 0 then
      println("Every permutation is affine over GF(2), but not every one is a bit permutation,")
      println("so an Omega network alone is not enough. Affine maps are XOR-linear, which is")
      println("cheaper to route than the general case.")
    else println("Every permutation is a bare bit permutation, which is exactly an Omega network.")
