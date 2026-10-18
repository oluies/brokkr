package brokkr.model

import brokkr.vectors.BenesRouter
import scotty.quantum.Circuit
import scotty.simulator.fusion.{BlockForm, FusedBlock, GateFusion, MonomialOperator}

/** Can the host prepare descriptors as fast as the engine consumes them?
  *
  * Every block the engine applies has to be built first: gates fused into one operator, its form
  * classified, a Benes routing computed if it is monomial, strides and bank descriptors worked out.
  * That work runs on the CPU, and if it is slower than the engine it does not matter how good the
  * datapath is — the engine starves.
  *
  * The asymmetry is not obviously in the engine's favour. Applying a block touches `2^n` amplitudes
  * once, so its cost grows with the '''state'''; building a dense one costs a matrix product per
  * gate fused, `O(8^k)`, which grows with the '''block''' and not with the state at all. A larger
  * register makes the engine's share bigger and the host's share no bigger, but at a fixed register
  * the comparison can go either way.
  *
  * These numbers are measured rather than modelled: the fusion pass and the Benes router are timed
  * as they are, on this machine, against an engine assumed to run at 1 GHz and 32 amplitudes per
  * cycle — the banking model's figure.
  */
object DescriptorCost:
  val EngineHz: Double = 1e9
  val AmplitudesPerCycle: Double = 32.0

  /** Seconds for the engine to apply one block to an `n`-qubit state: every amplitude, read and
    * written once.
    */
  def engineSeconds(qubitCount: Int): Double =
    2 * math.pow(2, qubitCount.toDouble) / AmplitudesPerCycle / EngineHz

  def time[A](warmups: Int, runs: Int)(body: => A): Double =
    (0 until warmups).foreach(_ => body)

    val elapsed = (0 until runs).map: _ =>
      val started = System.nanoTime()

      body
      (System.nanoTime() - started) / 1e9

    elapsed.min

  /** Distinct operators, by content. A repeated operator need only be built once. */
  def distinctOperators(blocks: Seq[FusedBlock]): Int =
    blocks.map(block => (block.qubits, block.operator.toDense.matrix)).distinct.length

  def main(args: Array[String]): Unit =
    val qubitCount = 20
    val widths = Seq(2, 3, 4, 5, 6)

    println(s"Host cost of preparing one block, against an engine at ${(EngineHz / 1e9).toInt} GHz")
    println(s"and ${AmplitudesPerCycle.toInt} amplitudes per cycle applying it to a $qubitCount-qubit state.")
    println()
    println(f"${"width"}%6s${"blocks"}%8s${"fuse/block"}%13s${"engine/block"}%14s${"ratio"}%9s${"verdict"}%12s")

    val circuit = Refill.brickwork(qubitCount, layers = 4)
    val engine = engineSeconds(qubitCount)

    widths.foreach: width =>
      val fused = GateFusion.fuse(circuit, width)
      val total = time(2, 5)(GateFusion.fuse(circuit, width))
      val perBlock = total / fused.ops.length
      val ratio = perBlock / engine

      println(
        f"$width%6d${fused.ops.length}%8d${perBlock * 1e6}%11.1f us${engine * 1e6}%12.1f us" +
          f"$ratio%8.2fx${if ratio > 1 then "host-bound" else "engine-bound"}%12s"
      )

    println()
    println("== Benes routing, the other per-block host cost ==")
    println()
    println("Measured on its own, over many calls, because the per-block figure in a circuit is")
    println("dominated by everything around it. The algorithm is O(2^k * k) — a few hundred steps")
    println("at k=6 — so this is a measurement of an unoptimised Scala implementation rather than")
    println("of the routing itself.")
    println()
    println(f"  ${"lanes"}%7s${"per route"}%13s${"vs engine"}%12s")

    Seq(3, 4, 5, 6).foreach: width =>
      val lanes = 1 << width
      val permutation = scala.util.Random(20261018L).shuffle((0 until lanes).toVector).toArray
      val iterations = 2000
      val elapsed = time(2, 5):
        var i = 0
        while i < iterations do
          BenesRouter.route(permutation)
          i += 1

      val perRoute = elapsed / iterations

      println(f"  $lanes%7d${perRoute * 1e6}%11.2f us${perRoute / engine}%11.3fx")

    println()

    println("== How much of it repeats ==")
    println()
    println("A repeated operator need only be built once, and real circuits repeat heavily: layers")
    println("of the same structure, iterations of the same oracle. Caching turns the host cost from")
    println("a per-block one into a per-distinct-block one.")
    println()
    println(f"${"workload"}%-18s${"blocks"}%8s${"distinct"}%10s${"reuse"}%8s")

    Seq(
      "brickwork" -> Refill.brickwork(qubitCount, layers = 4),
      "Grover" -> Refill.grover(qubitCount, iterations = 2),
      "diagonal-heavy" -> Refill.diagonalHeavy(qubitCount, layers = 4)
    ).foreach: (name, workload) =>
      val blocks = GateFusion.fuse(workload, 5).blocks
      val distinct = distinctOperators(blocks)

      println(f"$name%-18s${blocks.length}%8d$distinct%10d${blocks.length.toDouble / distinct}%7.1fx")
