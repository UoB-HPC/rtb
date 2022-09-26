package uob_hpc.rtb
import com.raquo.laminar.api.L.*
import uob_hpc.rtb.WebApp.Page
import scala.collection.immutable.TreeMap
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global

object JobInfoElement {

  PrismJS.use()

  def apply(page: Signal[Page.JobInfo], dataset: Signal[Deferred[Dataset]]) = div(
    display.flex,
    flexDirection.row,
    flexGrow := "1",
    children <-- page.combineWith(dataset).map {
      case (_, Deferred.Pending)  => Seq(span("Loading"))
      case (_, Deferred.Error(e)) => Seq(span(e.toString))
      case (info, Deferred.Success(dataset)) =>
        val jobInfo  = Var[Deferred[Option[String]]](Deferred.Pending)
        val allJobs  = dataset.series(info.series).map(s => s.jobName -> s.jobFile).to(TreeMap)
        val selected = info.job.orElse(allJobs.headOption.map(_._1))
        selected match {
          case None => jobInfo.set(Deferred.Success(None))
          case Some(name) =>
            fetchRaw(s"./dataset/${info.series}/${allJobs(name)}").onComplete(x =>
              jobInfo.set(x match {
                case Failure(e) => Deferred.Error(e)
                case Success(x) => Deferred.Success(Some(x))
              })
            )
        }
        Seq(
          div(
            cls    := "menu",
            margin := 8.px,
            p(cls := "menu-label", "Jobs"),
            ul(
              cls := "menu-list",
              dataset.series(info.series).sortBy(_.jobName).map { series =>
                li(
                  a(
                    series.jobName,
                    WebApp.navigateTo(info.copy(job = Some(series.jobName)), replace = true),
                    selected.filter(_ == series.jobName).map(_ => cls := "is-active")
                  )
                )
              }
            )
          ),
          div(
            flexGrow := 1,
            position.relative,
            justifyContent.center,
            alignItems.center,
            display.flex,
            child <-- jobInfo.signal
              .map {
                case Deferred.Error(e)      => s"# Error loading ${selected.getOrElse("")}:\n${e.stackTraceAsString}"
                case Deferred.Pending       => s"# Loading ${selected.getOrElse("")}"
                case Deferred.Success(None) => "# No data"
                case Deferred.Success(Some(fileContent)) => fileContent
              }
              .map(text =>
                pre(
                  0.inset,
                  position.absolute,
                  cls := "line-numbers",
                  whiteSpace.preWrap,
                  margin := 0.px,
                  code(cls := "language-bash", text),
                  onMountCallback(c => typings.prismjs.mod.highlightAllUnder(c.thisNode.ref))
                )
              )
          )
        )
    }
  )
}
