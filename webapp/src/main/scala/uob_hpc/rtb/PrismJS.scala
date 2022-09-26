package uob_hpc.rtb
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
object PrismJS {

  @JSImport("prismjs/themes/prism-tomorrow.css")
  @js.native
  final val CSS: js.Any = js.native

  @JSImport("prismjs/plugins/line-numbers/prism-line-numbers.css")
  @js.native
  final val LineNumbersCSS: js.Any = js.native

  @JSImport("prismjs/plugins/line-numbers/prism-line-numbers.js")
  @js.native
  final val LineNumbersJS: js.Any = js.native

  @JSImport("prismjs/components/prism-bash.js")
  @js.native
  final val BashJS: js.Any = js.native

  inline def use(): Any = { val _ = (CSS, LineNumbersCSS, LineNumbersJS, BashJS) }

}
