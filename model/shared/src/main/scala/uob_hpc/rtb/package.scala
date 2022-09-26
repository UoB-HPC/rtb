package uob_hpc.rtb

import scala.collection.immutable.ArraySeq
import java.time.LocalDate
import Pickler.*

import java.time.format.DateTimeFormatter
import scala.reflect.ClassTag

def timed[R](block: => R): (R, Double) = {
  val t0     = System.nanoTime()
  val result = block
  val t1     = System.nanoTime()
  result -> ((t1 - t0).toDouble / 1e9)
}

def timed[R](name: String)(block: => R): R = {
  val (r, elapsed) = timed(block)
  println(s"[$name] ${elapsed}s")
  r
}

object Time { given ReadWriter[Time] = macroRW }
case class Time(realS: Double, userS: Double, systemS: Double)

object Key { given ReadWriter[Key] = macroRW }
case class Key(
    name: String,
    version: String,
    date: LocalDate,
    extra: Option[String]
) {
  lazy val formatted: String =
    f"$name-$version-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}${extra.fold("")("-" + _)}"
}

object Entry { given [A: ReadWriter]: ReadWriter[Entry[A]] = macroRW }
case class Entry[A](
    key: A,
    times: ArraySeq[Time]
) {

  inline def mean(inline f: Time => Double): Double = {
    val N = times.size
    times.map(f).sum / N
  }
  inline def stdDev(inline f: Time => Double): Double = {
    val m = mean(f)
    math.sqrt(times.map(t => math.pow(t.realS - m, 2)).sum / (times.size - 1))
  }

  lazy val meanRealS: Double   = mean(_.realS)
  lazy val stdDevRealS: Double = stdDev(_.realS)

  lazy val meanSystemS: Double   = mean(_.systemS)
  lazy val stdDevSystemS: Double = stdDev(_.systemS)

  lazy val meanUserS: Double   = mean(_.userS)
  lazy val stdDevUserS: Double = stdDev(_.userS)

}

object Series { given [A: ReadWriter: ClassTag]: ReadWriter[Series[A]] = macroRW }
case class Series[A](
    jobName: String,
    jobFile: String,
    jobFileSHA256: String,
    results: ArraySeq[Entry[A]],
    failures: ArraySeq[A]
)

object Dataset { given ReadWriter[Dataset] = macroRW }
case class Dataset(
    providers: ArraySeq[(Key, Long)],
    series: Map[String, ArraySeq[Series[Int]]]
) {
  inline def swizzledSeries: Map[String, ArraySeq[Series[Key]]] = {
    val keys = providers.map(_._1)
    series.map { case (k, xs) =>
      k -> xs.map(s =>
        Series[Key](
          s.jobName,
          s.jobFile,
          s.jobFileSHA256,
          s.results.map(e => Entry[Key](keys(e.key), e.times)),
          s.failures.map(keys(_))
        )
      )
    }
  }
}
