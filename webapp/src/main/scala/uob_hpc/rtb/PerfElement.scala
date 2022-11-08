package uob_hpc.rtb

import com.raquo.airstream.web.AjaxEventStream
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

        val seriesSig = page.map(_.state).map { state =>
          allSeries
            .filter(s => state.jobs.contains(s.jobName))
            .map { s =>
              s.results.groupBy(e => e.key.name -> e.key.version).collect {
                case (k @ (n, v), xs) if state.versions.contains(k) =>
                  (s.jobName, n, v) -> xs.sortBy(_.key.date)
              }
            }
            .reduceOption(_ ++ _)
            .getOrElse(Map.empty)
        }

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

        val controls = DatasetElements.leftControlContainer(p(s"Dataset: $series"), 600) {
          table(
            cls             := "table is-narrow",
            backgroundColor := "transparent",
            display.block,
            alignSelf.center,
            thead(
              tr(
                th("Compilers", colSpan := compilers.size),
                th("Jobs (see [Job Info] for source)"),
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
          div(
            display.flex,
            alignItems.center,
            justifyContent.flexEnd,
            width := 100.pct,
            children <-- page.combineWith(seriesSig).map {
              case (Page.Perf(_, _, None), _) => Seq(span(" -- "))
              case (page @ Page.Perf(_, _, Some(state)), xs) =>
                DatasetElements.focusPanelHeader(
                  xs((state.job, state.name, state.version))(state.index).key,
                  Some(state.job),
                  state.group,
                  g => WebApp.navigateTo(page.copy(focus = Some(state.copy(group = g))))
                )
            }
          )
        ) {
          div(
            overflowY.scroll,
            height := 100.pct,
            child <-- page
              .map(_.focus)
              .combineWith(seriesSig)
              .map {
                case (None, _) => span("Select a data point in the chart for details.", margin := 16.px)
                case (Some(state), xs) =>
                  val element = xs((state.job, state.name, state.version))(state.index)
                  state.group match {
                    case FocusGroup.Compiler =>
                      DatasetElements.focusCompilerPanel(dataset, element.key)
                    case FocusGroup.Output =>
                      div(
                        child <-- AjaxEventStream
                          .get(s"./dataset/${series}/${state.job}/${element.key.formatted}/log.txt")
                          .map(_.responseText)
                          .map { xs =>
                            pre(
                              cls := "line-numbers",
                              whiteSpace.preWrap,
                              margin        := 0.px,
                              paddingBottom := 64.px,
                              code(cls := "language-bash", xs),
                              onMountCallback(c => typings.prismjs.mod.highlightAllUnder(c.thisNode.ref))
                            )
                          }
                      )
                  }
              }
          )
        }

        div(
          display.flex,
          flexGrow := 1,
          flexDirection.column,
          DatasetElements.chartContainer { case (dim, owner) =>
            dim.combineWith(page.map(_.state), seriesSig).map { case (w, h, state, xs) =>
              DateSeriesChartElement(
                yLabel = "Compilation time (seconds)",
                dayWidth = state.scale.getOrElse(2d),
                maxWidth = w,
                maxHeight = h - 7,
                yStep = 1,
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
                      else s"$job ($vers)" :: Nil
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
                page.map(_.focus.flatMap(x => xs.get((x.job, x.name, x.version)).flatMap(_.lift(x.index)))),
                { case (k @ (job, name, version), x) =>
                  val p     = page.observe(owner).now()
                  val index = xs(k).indexOf(x)
                  WebApp.router.replaceState(
                    p.copy(focus =
                      Some(
                        p.focus
                          .map(_.copy(job = job, name = name, version = version, index = index))
                          .getOrElse(PerfFocus(job, name, version, index, FocusGroup.Compiler))
                      )
                    )
                  )
                }
              )
            }
          },
          div(
            overflowY.hidden,
            display.flex,
            flexDirection.row,
            maxHeight := 350.px,
            minHeight := 350.px,
            controls,
            focused
          )
        )

    }

    div(
      overflowY.hidden,
      child <-- X,
      flexGrow := 1,
      display.flex,
      justifyContent.center,
      alignContent.center
    )
  }

}
