package brokkr.vectors

import scala.util.Random

/** Routing for a Benes network, which is what the permute path's shuffle fabric has to be.
  *
  * [[PermutationStudy]] shows that most monomial blocks contain permutations that are not bit
  * permutations, so an Omega network — which routes bit permutations natively and is otherwise
  * blocking — cannot serve them. A Benes network is rearrangeably non-blocking: it realises any
  * permutation of `2^k` elements in `2k-1` stages of `2^(k-1)` switches, against a crossbar's
  * `4^k` and an Omega network's inability.
  *
  * The price is that the switch settings have to be computed, not derived from the address. That
  * computation belongs on the CPU, once per block, which is exactly where the fusion pass already
  * runs — so it costs nothing at the datapath.
  *
  * Control bits come out in the order the recursive hardware consumes them: the input switches,
  * then the upper sub-network, then the lower, then the output switches.
  */
object BenesRouter:
  /** How many control bits a network of this size needs: `(2k-1) * 2^(k-1)`. */
  def controlBits(size: Int): Int =
    if size <= 1 then 0 else if size == 2 then 1 else size + 2 * controlBits(size / 2)

  /** Switch settings realising `permutation`, where input `i` is delivered to output
    * `permutation(i)`.
    */
  def route(permutation: Array[Int]): Vector[Boolean] =
    val n = permutation.length

    if n <= 1 then Vector.empty
    else if n == 2 then Vector(permutation(0) == 1)
    else
      val half = n / 2
      val inverse = Array.ofDim[Int](n)

      permutation.zipWithIndex.foreach((destination, source) => inverse(destination) = source)

      val inputSubnet = Array.fill(n)(-1)
      val outputSubnet = Array.fill(n)(-1)

      // The looping algorithm. Each input switch must send its two inputs to different
      // sub-networks, and so must each output switch receive from different ones; following that
      // constraint from an unassigned port traces a cycle, and the cycles are even, so the
      // alternation always closes.
      (0 until n).foreach: start =>
        if inputSubnet(start) == -1 then
          var port = start
          var subnet = 0
          var running = true

          while running do
            inputSubnet(port) = subnet

            val destination = permutation(port)
            val sibling = destination ^ 1

            outputSubnet(destination) = subnet
            outputSubnet(sibling) = 1 - subnet

            val partner = inverse(sibling)

            inputSubnet(partner) = 1 - subnet

            val next = partner ^ 1

            if inputSubnet(next) != -1 then running = false else port = next

      val upper = Array.fill(half)(0)
      val lower = Array.fill(half)(0)

      (0 until n).foreach: port =>
        val target = permutation(port) / 2

        if inputSubnet(port) == 0 then upper(port / 2) = target else lower(port / 2) = target

      val inputControl = Vector.tabulate(half)(switch => inputSubnet(2 * switch) != 0)
      val outputControl = Vector.tabulate(half)(switch => outputSubnet(2 * switch) != 0)

      inputControl ++ route(upper) ++ route(lower) ++ outputControl

  /** The network itself, as a model: what the RTL has to reproduce. */
  def apply[A](control: Vector[Boolean], in: Vector[A]): Vector[A] =
    val n = in.length

    if n <= 1 then in
    else if n == 2 then if control(0) then Vector(in(1), in(0)) else in
    else
      val half = n / 2
      val subnetBits = controlBits(half)
      val inputControl = control.take(half)
      val rest = control.drop(half)
      val upperOut = apply(rest.take(subnetBits), Vector.tabulate(half) { switch =>
        if inputControl(switch) then in(2 * switch + 1) else in(2 * switch)
      })
      val lowerOut = apply(rest.slice(subnetBits, 2 * subnetBits), Vector.tabulate(half) { switch =>
        if inputControl(switch) then in(2 * switch) else in(2 * switch + 1)
      })
      val outputControl = rest.drop(2 * subnetBits)

      Vector.tabulate(n): port =>
        val switch = port / 2
        val fromUpper = (port % 2 == 0) != outputControl(switch)

        if fromUpper then upperOut(switch) else lowerOut(switch)

  /** Self-check: route random permutations and confirm the network delivers them. */
  def main(args: Array[String]): Unit =
    val random = new Random(20261018L)

    Seq(2, 4, 8, 16, 32).foreach: size =>
      var checked = 0

      (0 until 500).foreach: _ =>
        val permutation = random.shuffle((0 until size).toVector).toArray
        val control = route(permutation)

        require(
          control.length == controlBits(size),
          s"size $size: produced ${control.length} control bits, expected ${controlBits(size)}"
        )

        val delivered = apply(control, (0 until size).toVector)
        val expected = Vector.tabulate(size)(output => permutation.indexOf(output))

        require(delivered == expected, s"size $size: $permutation routed to $delivered")
        checked += 1

      val stages = 2 * (31 - Integer.numberOfLeadingZeros(size)) - 1

      println(f"size $size%3d: $checked%4d random permutations routed, " +
        f"${controlBits(size)}%4d control bits, $stages stages of ${size / 2} switches")

    println()
    println("A Benes network routes every permutation. An Omega network routes bit permutations,")
    println("which PermutationStudy puts at 7.7% of monomial blocks.")
