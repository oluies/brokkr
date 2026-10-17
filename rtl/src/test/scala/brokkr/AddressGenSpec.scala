package brokkr

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Checks `AddressGen` against every vector Scotty produced.
  *
  * This is the difftest arrangement in miniature: the golden model is the Scala 3 simulator, the
  * device under test is Chisel, and they meet through a file because the two Scala versions cannot
  * call each other. The vectors are exhaustive rather than sampled — at six qubits and supports up
  * to three wide there are only 592 of them, so there is no reason to settle for less.
  */
class AddressGenSpec extends AnyFlatSpec with ChiselScalatestTester {
  case class Vector(slice: Int, qubits: Seq[Int], base: Int)

  lazy val vectors: Seq[Vector] = {
    val stream = getClass.getResourceAsStream("/address-vectors.txt")
    require(stream != null, "address-vectors.txt is missing; regenerate it with vectors.runMain")

    val lines = Source.fromInputStream(stream).getLines().filterNot(_.startsWith("#")).toSeq

    lines.map { line =>
      val Array(slice, qubits, base) = line.trim.split("\\s+")
      Vector(slice.toInt, qubits.split(",").map(_.toInt).toSeq, base.toInt)
    }
  }

  val QubitCount = 6
  val MaxWidth = 3

  "AddressGen" should "load its golden vectors" in {
    assert(vectors.length == 592, s"expected 592 vectors, found ${vectors.length}")
  }

  it should "match Scotty's baseOf on every vector" in {
    test(new AddressGen(QubitCount, MaxWidth)) { dut =>
      vectors.foreach { v =>
        dut.io.slice.poke(v.slice.U)
        dut.io.blockWidth.poke(v.qubits.length.U)

        (0 until MaxWidth).foreach { i =>
          dut.io.qubits(i).poke(v.qubits.lift(i).getOrElse(0).U)
        }

        withClue(s"slice ${v.slice}, qubits ${v.qubits.mkString(",")}: ") {
          dut.io.base.expect(v.base.U)
        }
      }
    }
  }

  it should "leave the supported bits clear" in {
    // The defining property, checked independently of the vectors: a base address never has a bit
    // set at a supported qubit, because that is the bit the block's own offsets will fill in.
    test(new AddressGen(QubitCount, MaxWidth)) { dut =>
      vectors.foreach { v =>
        dut.io.slice.poke(v.slice.U)
        dut.io.blockWidth.poke(v.qubits.length.U)
        (0 until MaxWidth).foreach(i => dut.io.qubits(i).poke(v.qubits.lift(i).getOrElse(0).U))

        val base = dut.io.base.peek().litValue.toInt

        v.qubits.foreach { q =>
          withClue(s"base $base has qubit $q set: ") {
            assert(((base >> q) & 1) == 0)
          }
        }
      }
    }
  }
}
