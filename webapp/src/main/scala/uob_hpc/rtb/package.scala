package uob_hpc.rtb

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.scalajs.dom.fetch
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

inline def linearScale(
    inline x: Double,
    inline xMin: Double,
    inline xMax: Double,
    inline min: Double,
    inline max: Double
): Double =
  ((max - min) * (x - xMin) / (xMax - xMin)) + min

extension [A: Numeric](inline d: A) {
  inline def px  = s"${d}px"
  inline def em  = s"${d}em"
  inline def vh  = s"${d}vh"
  inline def vw  = s"${d}vw"
  inline def pct = s"$d%"
  inline def inset = Seq(
    top    := 0.px,
    left   := 0.px,
    bottom := 0.px,
    right  := 0.px
  )

}

extension [A <: AnyVal](inline xs: Seq[(A, A)]) {
  inline def svgPoints: String = xs.map((a, b) => s"$a,$b").mkString(" ")
}

extension (inline t: Throwable) {
  inline def stackTraceAsString = {
    val sw = java.io.StringWriter()
    t.printStackTrace(java.io.PrintWriter(sw))
    sw.toString
  }
}

enum Deferred[+A] {
  case Pending
  case Success(a: A)
  case Error(e: Throwable)
}

case class Colour(hex: Int) {
  inline def r: Int            = (hex & 0xff0000) >> 16
  inline def g: Int            = (hex & 0xff00) >> 8
  inline def b: Int            = hex & 0xff
  inline def hexString: String = String.format("#%02X%02X%02X", r, g, b)
  inline def mix(inline that: Colour): Colour = Colour(
    (r + that.r) / 2,
    (g + that.g) / 2,
    (b + that.b) / 2
  )
  inline def rgb: js.Array[Int] = js.Array(r, g, b)
}
object Colour {
  inline def apply(r: Int, g: Int, b: Int): Colour = {
    var rgb: Int = r
    rgb = (rgb << 8) + g
    rgb = (rgb << 8) + b
    Colour(rgb)
  }
}
final val Colours = Seq(
  Colour(0x3366cc),
  Colour(0xdc3912),
  Colour(0xff9900),
  Colour(0x109618),
  Colour(0x990099),
  Colour(0x0099c6),
  Colour(0xdd4477),
  Colour(0x66aa00),
  Colour(0xb82e2e),
  Colour(0x316395),
  Colour(0x994499),
  Colour(0x22aa99),
  Colour(0xaaaa11),
  Colour(0x6633cc),
  Colour(0xe67300),
  Colour(0x8b0707),
  Colour(0x651067),
  Colour(0x329262),
  Colour(0x5574a6),
  Colour(0x3b3eac),
  Colour(0xb77322),
  Colour(0x16d620),
  Colour(0xb91383),
  Colour(0xf4359e),
  Colour(0x9c5935),
  Colour(0xa9c413),
  Colour(0x2a778d),
  Colour(0x668d1c),
  Colour(0xbea413),
  Colour(0x0c5922),
  Colour(0x743411)
)
inline def mkColour(inline n: Int) = Colours((n % (Colours.size - 1)).abs)

given [A: upickle.default.ReadWriter]: upickle.default.ReadWriter[Var[A]] =
  upickle.default.readwriter[A].bimap[Var[A]](_.now(), Var(_))

//def fetchJson[A](url: String): Future[A] = fetch(url).toFuture.flatMap(_.json().toFuture).map(_.asInstanceOf[A])

inline def fetchRaw(inline url: String): Future[String]                = fetch(url).toFuture.flatMap(_.text().toFuture)
inline def fetchJson[A: Pickler.Reader](inline url: String): Future[A] = fetchRaw(url).map(Pickler.web.read[A](_))
