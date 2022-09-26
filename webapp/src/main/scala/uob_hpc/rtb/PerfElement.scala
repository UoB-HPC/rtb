package uob_hpc.rtb

import com.raquo.laminar.api.L.*
import uob_hpc.rtb.WebApp.{FocusGroup, Page, PerfFocus, ViewState}
import scala.collection.immutable.SortedSet

object PerfElement {

  def apply(dataset: StrictSignal[Deferred[Dataset]], page: Signal[Page.Perf]) = {

    val scrollX = Var((0d, 0d))

    val X = page.map(_.series).combineWith(dataset).map {
      case (_, Deferred.Error(e)) => DatasetElements.error(e)
      case (_, Deferred.Pending)  => DatasetElements.loading
      case (series, Deferred.Success(dataset)) =>
        val allSeries = dataset.swizzledSeries(series)

        val compilers = DatasetElements.compilerSelect(
          allSeries,
          page,
          _.state.versions,
          (s, x) => s.copy(state = s.state.copy(versions = x)),
          WebApp.router.replaceState(_)
        )

        val jobs = DatasetElements.jobSelect(
          allSeries,
          page,
          _.state.jobs,
          (s, x) => s.copy(state = s.state.copy(jobs = x)),
          WebApp.router.replaceState(_)
        )
        val scale = DatasetElements.scaleSelect(
          page,
          _.state.scale,
          (s, x) => s.copy(state = s.state.copy(scale = x)),
          WebApp.router.replaceState(_)
        )

        val controls = DatasetElements.leftControlContainer(p(s"Dataset: $series")) {
          table(
            cls             := "table is-narrow",
            backgroundColor := "transparent",
            display.block,
            alignSelf.center,
            thead(
              tr(
                th("Compilers", colSpan := compilers.size),
                th("Jobs"),
                th("Options")
              )
            ),
            tbody(
              tr(
                compilers.map(_._1).map(td(_)),
                td(jobs, rowSpan := 2),
                td(
                  scale,
                  DatasetElements.timeGroupSelect(
                    page,
                    _.state.group,
                    (s, x) => s.copy(state = s.state.copy(group = x)),
                    WebApp.router.replaceState(_)
                  ),
                  rowSpan := 2
                )
              ),
              tr(
                compilers.map(_._2).map { xs =>
                  td(div(maxHeight := 150.px, overflowY.scroll, xs))
                }
              )
            )
          )
        }

        val focused = DatasetElements.rightControlContainer(
          p(
            s"Datapoint:",
            child.text <-- page.map(_.focus).map {
              case None        => " -- "
              case Some(state) => s"#${state.index}"
            }
          )
        ) {
          div(
            child.text <-- page.map(_.focus).map {
              case None => "Select a datapoint in the chart for details."
              case Some(state) =>
                s"${state.toString()}"
            }
          )
        }

        div(
          display.flex,
          flexGrow := 1,
          flexDirection.column,
          DatasetElements.chartContainer { case (dim, owner) =>
            dim.combineWith(page.map(_.state)).map { case (w, h, state) =>
              val xs = allSeries
                .filter(s => state.jobs.contains(s.jobName))
                .map { s =>
                  s.results.groupBy(e => e.key.name -> e.key.version).collect {
                    case (k @ (n, v), xs) if state.versions.contains(k) =>
                      (s.jobName, n, v) -> xs.sortBy(_.key.date)
                  }
                }
                .reduceOption(_ ++ _)
                .getOrElse(Map.empty)

              DateSeriesChartElement(
                yLabel = "Compilation time (seconds)",
                dayWidth = state.scale.getOrElse(2d),
                maxWidth = w,
                maxHeight = h - 7,
                yStep = 2,
                series = xs,
                groupLabelFn = { case (job, compiler, version) => s"$job ($compiler-$version)" },
                groupLabelsFn = (xs, limit) =>
                  xs.groupMap(_._1) { case (_, c, v) => (c, v) }
                    .flatMap { case (job, xs) =>
                      val vers = xs
                        .groupMap(_._1)(_._2)
                        .map { case (c, vers) =>
                          s"$c-{${vers.mkString(",")}}"
                        }
                        .mkString(",")
                      if ((job.length + vers.length) > limit) job :: vers :: Nil
                      else s"$job (${vers})" :: Nil
                    }
                    .toList,
                dateFn = _.key.date,
                yFn = e =>
                  state.group match {
                    case Some(WebApp.TimeGroup.Real) | None => e.meanRealS
                    case Some(WebApp.TimeGroup.System)      => e.meanSystemS
                    case Some(WebApp.TimeGroup.User)        => e.meanUserS
                  },
                yErrorFn = e =>
                  state.group match {
                    case Some(WebApp.TimeGroup.Real) | None => e.stdDevRealS
                    case Some(WebApp.TimeGroup.System)      => e.stdDevSystemS
                    case Some(WebApp.TimeGroup.User)        => e.stdDevUserS
                  },
                scrollX,
                page.map(_.focus.flatMap(x => allSeries.find(s => s.jobName == x._1).flatMap(_.results.lift(x._2)))),
                { case ((job, _, _), x) =>
                  val p = page.observe(owner).now()
                  WebApp.router.replaceState(
                    p.copy(focus = Some(PerfFocus(job, 0, FocusGroup.Compiler)))
                  )
                }
              )
            }
          },
          div(
            display.flex,
            flexDirection.row,
            controls,
            focused
          )
        )

    }

    div(
      child <-- X,
      flexGrow := 1,
      display.flex,
      justifyContent.center,
      alignContent.center
    )
  }

}
