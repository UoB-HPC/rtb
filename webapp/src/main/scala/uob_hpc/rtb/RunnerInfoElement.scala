package uob_hpc.rtb
import com.raquo.laminar.api.L.*
import uob_hpc.rtb.WebApp.Page
import scala.collection.immutable.TreeMap
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global

object RunnerInfoElement {

  PrismJS.use()

  def apply() = {
    val runnerInfo = Var[Deferred[String]](Deferred.Pending)
    fetchRaw("./dataset/runnerInfo.txt").onComplete(x =>
      runnerInfo.set(x match {
        case Failure(e) => Deferred.Error(e)
        case Success(x) => Deferred.Success(x)
      })
    )
    div(
      display.flex,
      flexGrow := 1,
      overflowY.scroll,
      child <-- runnerInfo.signal.map {
        case Deferred.Pending  => span("Loading")
        case Deferred.Error(e) => span(e.toString)
        case Deferred.Success(x) =>
          pre(
            cls := "line-numbers",
            whiteSpace.preWrap,
            margin := 0.px,
            code(cls := "language-html", x),
            onMountCallback(c => typings.prismjs.mod.highlightAllUnder(c.thisNode.ref))
          )
      }
    )
  }
}
