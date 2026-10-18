package brokkr.model

import scotty.quantum.Circuit
import scotty.quantum.gate.StandardGate.{CNOT, CZ, H, RZ, X}
import scotty.simulator.fusion.{BlockForm, FusedBlock, FusedCircuit, FusedOp, GateFusion, NativeOp}

/** What it costs when the state does not fit on chip.
  *
  * Past about twenty qubits the amplitude store cannot hold the state, and the engine works in
  * tiles: load a tile, apply what can be applied to it, store it back. A block can be applied to a
  * tile in isolation only if every qubit it touches is '''local''' — inside the address bits the
  * tile spans. A block touching a qubit above that line needs amplitudes from several tiles at
  * once, which means re-tiling the whole state first, and that costs a full pass over off-chip
  * memory in each direction.
  *
  * So the figure of merit is '''passes''', not cycles. Off-chip bandwidth is the binding constraint
  * at this size, and a pass is `2^n` amplitudes read and written.
  *
  * ==Where the form lattice comes back==
  * Not every block needs locality. A '''diagonal''' block scales each amplitude independently, so
  * it streams: it can be applied to a tile whatever its support, including a support spanning every
  * qubit in the register. Grover's all-qubit controlled `Z` — the gate that forced a native escape
  * in the fusion pass and looked like the worst case there — is free here.
  *
  * A '''monomial''' block permutes amplitudes between lanes and a '''dense''' one mixes them, so
  * both need their support local. That is the same three-way split that decided the datapaths,
  * reappearing as a scheduling constraint.
  */
object Refill:
  /** A block as this model sees it: what it touches, and whether it needs that to be local. */
  final case class Step(support: Set[Int], streams: Boolean)

  def steps(fused: FusedCircuit): Seq[Step] =
    fused.ops.map:
      case block: FusedBlock =>
        Step(block.qubits.toSet, block.form == BlockForm.Diagonal)
      case native: NativeOp =>
        // A native op is one the pass declined to fuse. It is diagonal exactly when every gate in
        // it is, which for the gates that reach this path means a controlled phase of some kind.
        Step(native.qubits.toSet, isDiagonal(native))

  private def isDiagonal(native: NativeOp): Boolean =
    FusedBlock.lower(native.gate, native.width) match
      case Seq(block: FusedBlock) => block.form == BlockForm.Diagonal
      case _                      => false

  /** Passes over off-chip memory, scheduling greedily with a lookahead.
    *
    * When a block's support will not fit the local set, the tiling has to change. The replacement
    * must contain that block's support; the remaining slots are filled with whichever qubits the
    * next `lookahead` blocks want most, which is the cheapest policy that is not simply arbitrary.
    */
  def passes(steps: Seq[Step], qubitCount: Int, localQubits: Int, lookahead: Int): Int =
    require(localQubits >= 1 && localQubits <= qubitCount, "locals have to fit in the register")

    var local = (0 until localQubits).toSet
    var remaps = 0

    steps.zipWithIndex.foreach: (step, index) =>
      if !step.streams && !step.support.subsetOf(local) then
        if step.support.size > localQubits then
          // Nothing can make this local. It would need a different decomposition, not a re-tiling.
          remaps += 1
        else
          val wanted = steps
            .slice(index, index + lookahead)
            .filterNot(_.streams)
            .flatMap(_.support)
            .groupMapReduce(identity)(_ => 1)(_ + _)
          val filler = (0 until qubitCount)
            .filterNot(step.support.contains)
            .sortBy(q => -wanted.getOrElse(q, 0))
            .take(localQubits - step.support.size)

          local = step.support ++ filler
          remaps += 1

    remaps

  def main(args: Array[String]): Unit =
    val qubitCount = 24
    val workloads = Seq(
      "brickwork" -> brickwork(qubitCount, layers = 4),
      "Grover" -> grover(qubitCount, iterations = 2),
      "diagonal-heavy" -> diagonalHeavy(qubitCount, layers = 4)
    )

    println(s"$qubitCount qubits. A pass is 2^$qubitCount amplitudes read and written off chip.")
    println("Local qubits are how many of them the on-chip store spans; 20 is about 16 MiB at")
    println("fp64 complex, so these are the sizes a real part would have.")
    println()

    workloads.foreach: (name, circuit) =>
      val fused = GateFusion.fuse(circuit, maxWidth = 5)
      val plan = steps(fused)
      val streaming = plan.count(_.streams)

      println(s"$name: ${fused.ops.length} blocks, $streaming of them diagonal and so streaming")
      println(f"  ${"local"}%7s${"passes"}%9s${"lookahead 1"}%14s${"off-chip x state"}%19s")

      Seq(16, 18, 20, 22).foreach: locals =>
        val withLookahead = passes(plan, qubitCount, locals, lookahead = 64)
        val greedy = passes(plan, qubitCount, locals, lookahead = 1)

        println(f"  $locals%7d$withLookahead%9d$greedy%14d${2 * withLookahead}%18dx")

      println()

    println("A diagonal block never forces a pass, whatever it touches. That is the same property")
    println("that made it the cheap datapath, and it is worth more here: at this size a pass costs")
    println("two full traversals of off-chip memory, and the engine's whole advantage is on-chip.")

  def brickwork(qubitCount: Int, layers: Int): Circuit =
    val ops = (0 until layers).flatMap: layer =>
      (0 until qubitCount).map(H(_)) ++
        (0 until qubitCount).map(q => RZ(0.1 + 0.01 * q, q)) ++
        (layer % 2 until qubitCount - 1 by 2).map(q => CNOT(q, q + 1))

    Circuit(Circuit.generateRegister(qubitCount), ops*)

  /** Grover without the all-qubit oracle fused: the controlled Z stays native and diagonal. */
  def grover(qubitCount: Int, iterations: Int): Circuit =
    val qubits = (0 until qubitCount).toIndexedSeq
    val ops = qubits.map(H(_)) ++ (0 until iterations).flatMap: _ =>
      scotty.algorithm.Grover.oracle(qubits, 7) ++ scotty.algorithm.Grover.diffusion(qubits)

    Circuit(Circuit.generateRegister(qubitCount), ops*)

  /** Phases and permutations only, to show what a circuit that suits this engine looks like. */
  def diagonalHeavy(qubitCount: Int, layers: Int): Circuit =
    val ops = (0 until layers).flatMap: layer =>
      (0 until qubitCount).map(q => RZ(0.2 + 0.01 * q, q)) ++
        (layer % 2 until qubitCount - 1 by 2).map(q => CZ(q, q + 1)) ++
        Seq(X(layer % qubitCount))

    Circuit(Circuit.generateRegister(qubitCount), ops*)
