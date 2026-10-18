package brokkr.model

import scala.util.Random
import scotty.quantum.density.{Channel, ComplexMatrix, DensityMatrix}
import scotty.quantum.math.Complex

/** The density-matrix case, measured against the state-vector one.
  *
  * The assessment has pointed here twice from opposite directions. The arithmetic-intensity
  * argument said a Kraus channel is a dense superoperator product with good reuse and no fusion
  * trickery; the refill model said the engine only wins where the state fits on chip. A density
  * matrix of ten or eleven qubits is both — and this is the measurement rather than the argument.
  *
  * ==Why it is a different problem==
  * A state vector of `n` qubits is `2^n` amplitudes and a gate touches each one once: the work is
  * linear in the data, which is why the whole of this project has been a memory-bandwidth story. A
  * density matrix is `4^n` entries and a channel is `rho -> sum K rho K†` — two matrix products per
  * Kraus operator, so the work is `O(8^n)` against `O(4^n)` of data. The arithmetic per byte grows
  * with the system instead of being fixed at 0.44.
  *
  * That one change removes most of what the preceding models were about. Matrix multiplication has
  * regular dense access, so there is no bit-permutation stride and no quantum-specific banking
  * problem; the operators are the Kraus set, so there is no fusion pass and no host-side
  * construction cost; and at these sizes nothing spills, so there is no refill.
  */
object DensityCost:
  /** Complex multiply as six flops, complex add as two. */
  def channelFlops(dimension: Long, kraus: Int): Double =
    val n = dimension.toDouble

    // Two matrix products per Kraus operator: K rho, then (K rho) K†.
    kraus * 2 * (6 * n * n * n + 2 * n * n * (n - 1))

  /** Bytes moved: the state in and out, plus the Kraus operators once. */
  def channelBytes(dimension: Long, kraus: Int, bytesPerEntry: Int = 16): Double =
    val n = dimension.toDouble

    (2 + kraus) * n * n * bytesPerEntry

  def intensity(qubits: Int, kraus: Int): Double =
    val dimension = 1L << qubits

    channelFlops(dimension, kraus) / channelBytes(dimension, kraus)

  def footprint(qubits: Int, bytesPerEntry: Int = 16): Double =
    math.pow(4, qubits.toDouble) * bytesPerEntry

  def time[A](warmups: Int, runs: Int)(body: => A): Double =
    (0 until warmups).foreach(_ => body)
    (0 until runs).map: _ =>
      val started = System.nanoTime()
      body
      (System.nanoTime() - started) / 1e9
    .min

  def randomDensity(qubits: Int, random: Random): DensityMatrix =
    val dimension = 1 << qubits
    val entries = Vector.tabulate(dimension * dimension)(_ => Complex(random.nextDouble() - 0.5, 0d))
    val raw = ComplexMatrix(dimension, dimension, entries)

    // rho = A A† / tr, which is Hermitian and positive semidefinite by construction.
    DensityMatrix(Vector.fill(qubits)(2), ComplexMatrix.outer(raw, raw)).normalized

  def main(args: Array[String]): Unit =
    val kraus = 4

    println("== Arithmetic intensity, against the state-vector case ==")
    println()
    println(s"A depolarizing channel has $kraus Kraus operators. State-vector figures are from")
    println("FusionCost earlier in this project: 0.44 flop/byte unfused, 15.9 at a fusion width of 6.")
    println()
    println(f"${"qubits"}%8s${"dimension"}%11s${"rho"}%12s${"flop/byte"}%12s${"vs unfused"}%12s${"vs k=6"}%10s")

    Seq(4, 6, 8, 10, 11, 12).foreach: qubits =>
      val flopByte = intensity(qubits, kraus)
      val bytes = footprint(qubits)
      val size =
        if bytes >= 1048576d then f"${bytes / 1048576d}%.0f MiB"
        else f"${bytes / 1024d}%.0f KiB"

      println(
        f"$qubits%8d${1 << qubits}%11d$size%12s$flopByte%12.0f${flopByte / 0.44}%11.0fx${flopByte / 15.9}%9.0fx"
      )

    println()
    println("== Measured, on Scotty's density layer ==")
    println()
    println(f"${"qubits"}%8s${"dimension"}%11s${"seconds"}%11s${"Gflop/s"}%10s${"flop/byte"}%12s")

    val random = Random(20261018L)

    Seq(3, 4, 5, 6, 7).foreach: qubits =>
      val rho = randomDensity(qubits, random)
      val channel = Channel.depolarizing(0.1).tensor(Channel.identity(Vector.fill(qubits - 1)(2)))
      val elapsed = time(2, 3)(channel(rho))
      val flops = channelFlops(1L << qubits, kraus)

      println(
        f"$qubits%8d${1 << qubits}%11d$elapsed%11.4f${flops / elapsed / 1e9}%10.2f" +
          f"${intensity(qubits, kraus)}%12.0f"
      )

    println()
    println("== What stops being a problem ==")
    println()
    println("  banking      a matrix product has regular dense access. No bit-permutation stride,")
    println("               so no quantum-specific bank conflicts — ordinary GEMM blocking.")
    println("  fusion       the operators are the Kraus set. Nothing to fuse, nothing to classify.")
    println("  host cost    no operator construction, so no 116 us per block and no k=5 cap.")
    println("  refill       at ten qubits rho is 16 MiB and fits on chip; nothing spills.")
    println("  the forms    there is one datapath, a MAC array, and it is busy.")
    println()
    println("The state-vector engine spent this project fighting a kernel with 0.44 flop/byte. This")
    println("one starts three orders of magnitude the other side of the roofline and stays there.")
