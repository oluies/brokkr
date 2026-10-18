package brokkr

import chisel3._
import chisel3.util._

/** A Benes network: `2k-1` stages of `2^(k-1)` switches, routing any permutation of `2^k` lanes.
  *
  * The permute path needs this rather than the Omega network the rest of the design reaches for,
  * and the reason is measured rather than assumed. `brokkr.vectors.PermutationStudy` fuses
  * randomised circuits and classifies the permutations that come out: 7.7% are bit permutations,
  * which is what an Omega network routes natively; 33.5% are affine over GF(2); and 58.8% are
  * arbitrary. Toffoli and Fredkin gates generate permutations that are not affine, so this is what
  * a real gate set produces, not a corner case. An Omega network is blocking and would be unable to
  * route most blocks.
  *
  * A Benes network is rearrangeably non-blocking: it routes every permutation, in
  * `(2k-1) * 2^(k-1)` switches against a crossbar's `4^k`. The price is that the switch settings
  * must be computed rather than derived from the address, by the looping algorithm in
  * `brokkr.vectors.BenesRouter`. That computation belongs on the CPU, once per block, where the
  * fusion pass already runs — so it costs the datapath nothing.
  *
  * The recursion here consumes control bits in the order the router emits them: input switches,
  * then the upper sub-network, then the lower, then the output switches.
  */
object Benes {
  /** Control bits a network of this size needs. */
  def controlBits(size: Int): Int =
    if (size <= 1) 0 else if (size == 2) 1 else size + 2 * controlBits(size / 2)

  def apply[T <: Data](in: Seq[T], control: Seq[Bool]): Seq[T] = {
    val size = in.length

    require(isPow2(size), s"a Benes network needs a power-of-two lane count, not $size")
    require(
      control.length == controlBits(size),
      s"a $size-lane network needs ${controlBits(size)} control bits, not ${control.length}"
    )

    if (size <= 1) in
    else if (size == 2) Seq(Mux(control(0), in(1), in(0)), Mux(control(0), in(0), in(1)))
    else {
      val half = size / 2
      val inputControl = control.take(half)
      val rest = control.drop(half)
      val subnetBits = controlBits(half)

      // Each input switch sends one of its two lanes to each sub-network. Which one is the whole
      // content of the routing problem.
      val upper = apply(
        Seq.tabulate(half)(s => Mux(inputControl(s), in(2 * s + 1), in(2 * s))),
        rest.take(subnetBits)
      )
      val lower = apply(
        Seq.tabulate(half)(s => Mux(inputControl(s), in(2 * s), in(2 * s + 1))),
        rest.slice(subnetBits, 2 * subnetBits)
      )
      val outputControl = rest.drop(2 * subnetBits)

      Seq.tabulate(size) { port =>
        val switch = port / 2

        if (port % 2 == 0) Mux(outputControl(switch), lower(switch), upper(switch))
        else Mux(outputControl(switch), upper(switch), lower(switch))
      }
    }
  }
}
