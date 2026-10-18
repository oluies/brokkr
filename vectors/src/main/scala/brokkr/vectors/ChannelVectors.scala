package brokkr.vectors

import java.io.PrintWriter
import scala.util.Random
import scotty.quantum.density.{Channel, ComplexMatrix, DensityMatrix}
import scotty.quantum.math.Complex

/** Reference vectors for `ChannelEngine`: several channels, several states, each with its answer.
  *
  * The engine was first checked against one channel on one state, which is thin for the thing the
  * whole assessment rests on — the state-vector path it is being compared against is checked
  * against randomised circuits across the entire gate set. This closes that gap: every channel
  * Scotty offers, each applied to several random states.
  *
  * ==Erasure==
  * Erasure is the reason this generator carries two dimensions per case. It replaces the input with
  * a flag state on a third basis element, so it maps a qubit to a qutrit and its Kraus operators are
  * `3 × 2`. For a while the engine assumed `rho`, `K` and the result were all one square size, and
  * erasure was recorded here as a known gap on a channel `pctc` actually uses. It is no longer one:
  * a case now states `inputDim`, `outputDim` and the array width it should be run with.
  *
  * Lifting erasure over more qubits makes it rectangular without being awkward — `4 → 6` on two
  * qubits, `8 → 12` on three — and neither of those needs any padding at a two- or four-wide array.
  * The bare one-qubit case does: three is not a whole number of tiles at any width above one, so its
  * output is padded to four with zeros. That is exact rather than approximate, because a zero row of
  * `K` contributes nothing to either product, and the padding is written into the vectors so the
  * hardware comparison is against the padded answer rather than a reshaped one.
  */
object ChannelVectors:
  /** Two qubits exercises the engine with one tile; three forces it to loop over tiles, which is a
    * different control path and the one that has to work for the sizes this project argues for.
    */
  val QubitCounts: Seq[Int] = Seq(2, 3)
  val StatesPerChannel: Int = 3

  /** Erasure over one, two and three qubits: padded `2 → 4`, then `4 → 6` and `8 → 12` exactly. */
  val ErasureQubitCounts: Seq[Int] = Seq(1, 2, 3)
  val ErasureStates: Int = 2

  /** Array widths the engine is built for, widest first. */
  val TileCandidates: Seq[Int] = Seq(4, 2)

  /** The widest array that divides both dimensions, so no padding is needed; otherwise the
    * narrowest, which minimises what has to be padded away.
    */
  def tileFor(inputDim: Int, outputDim: Int): Int =
    TileCandidates
      .find(t => inputDim % t == 0 && outputDim % t == 0)
      .getOrElse(TileCandidates.last)

  def padUp(dimension: Int, tile: Int): Int = ((dimension + tile - 1) / tile) * tile

  /** Square channels on one qubit, lifted to a register of the given size. */
  def channels(qubits: Int): Seq[(String, Channel)] =
    val padding = Channel.identity(Vector.fill(qubits - 1)(2))

    Seq(
      "depolarizing" -> Channel.depolarizing(0.3),
      "amplitudeDamping" -> Channel.amplitudeDamping(0.4),
      "dephasing" -> Channel.dephasing(0.25)
    ).map((name, channel) => name -> channel.tensor(padding))

  /** Erasure on the first qubit of a register, leaving the rest alone. */
  def erasure(qubits: Int): Channel =
    val channel = Channel.erasure(0.35)

    if qubits == 1 then channel
    else channel.tensor(Channel.identity(Vector.fill(qubits - 1)(2)))

  /** A valid state: Hermitian, positive semidefinite, unit trace. */
  def randomState(qubits: Int, random: Random): DensityMatrix =
    val dimension = 1 << qubits
    val seed = ComplexMatrix(
      dimension,
      dimension,
      Vector.tabulate(dimension * dimension)(_ =>
        Complex(random.nextDouble() - 0.5, random.nextDouble() - 0.5)
      )
    )

    DensityMatrix(Vector.fill(qubits)(2), ComplexMatrix.outer(seed, seed)).normalized

  def main(args: Array[String]): Unit =
    val path = args.headOption.getOrElse("rtl/src/test/resources/channel-vectors.txt")
    val random = Random(20261018L)
    val out = new PrintWriter(path)

    /** Emits `rows × cols`, zero-filling anything past the matrix's own extent. */
    def emit(label: String, caseIndex: Int, operator: Int, matrix: ComplexMatrix, rows: Int, cols: Int): Unit =
      require(matrix.rows <= rows && matrix.cols <= cols, s"$label does not fit in ${rows}x$cols")

      (0 until rows).foreach: row =>
        val entries = (0 until cols).flatMap: col =>
          if row < matrix.rows && col < matrix.cols then
            Seq(matrix.realAt(row, col), matrix.imagAt(row, col))
          else Seq(0.0, 0.0)

        out.println(s"$label $caseIndex $operator $row ${entries.mkString(",")}")

    var caseIndex = 0

    /** One case: the channel, the state, and the padded shape the hardware will be built for. */
    def emitCase(name: String, channel: Channel, rho: DensityMatrix): Unit =
      val tile = tileFor(padUp(channel.inputDim, 2), padUp(channel.outputDim, 2))
      val inputDim = padUp(channel.inputDim, tile)
      val outputDim = padUp(channel.outputDim, tile)
      val result = channel(rho)

      require(rho.isHermitian(), s"$name: rho should be Hermitian")
      require(math.abs(rho.realTrace - 1.0) < 1e-9, s"$name: rho should have unit trace")
      require(channel.isTracePreserving(), s"$name: the channel should be trace preserving")

      out.println(
        s"case $caseIndex $name ${channel.kraus.length} $inputDim $outputDim $tile " +
          s"${channel.inputDim} ${channel.outputDim}"
      )
      emit("rho", caseIndex, 0, rho.matrix, inputDim, inputDim)
      channel.kraus.zipWithIndex.foreach((k, i) => emit("k", caseIndex, i, k, outputDim, inputDim))
      emit("result", caseIndex, 0, result.matrix, outputDim, outputDim)

      caseIndex += 1

    out.println("# generated by brokkr.vectors.ChannelVectors from Scotty's Channel.apply")
    out.println("# case <index> <name> <kraus> <inputDim> <outputDim> <tile> <trueIn> <trueOut>")
    out.println(
      s"cases ${QubitCounts.length * channels(2).length * StatesPerChannel +
          ErasureQubitCounts.length * ErasureStates}"
    )

    QubitCounts.foreach: qubits =>
      val dimension = 1 << qubits

      channels(qubits).foreach: (name, channel) =>
        require(
          channel.inputDim == dimension && channel.outputDim == dimension,
          s"$name on $qubits qubits is not square"
        )

        (0 until StatesPerChannel).foreach: _ =>
          emitCase(name, channel, randomState(qubits, random))

    ErasureQubitCounts.foreach: qubits =>
      val channel = erasure(qubits)

      require(channel.outputDim != channel.inputDim, "erasure should change the dimension")

      (0 until ErasureStates).foreach: _ =>
        emitCase("erasure", channel, randomState(qubits, random))

    out.close()

    println(s"wrote $caseIndex cases, of which ${ErasureQubitCounts.length * ErasureStates} rectangular")
