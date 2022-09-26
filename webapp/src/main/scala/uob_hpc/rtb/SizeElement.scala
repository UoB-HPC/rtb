package uob_hpc.rtb

import com.raquo.laminar.api.L.*
import uob_hpc.rtb.WebApp.{FocusGroup, Page, SizeFocus, ViewState}
import scala.collection.immutable.SortedSet

object SizeElement {

  def apply(dataset: StrictSignal[Deferred[Dataset]], page: Signal[Page.Size]) = {

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
                th("Options")
              )
            ),
            tbody(
              tr(
                compilers.map(_._1).map(td(_)),
                td(
                  scale,
                  rowSpan := 2
                )
              ),
              tr(compilers.map(_._2).map(xs => td(div(maxHeight := 150.px, overflowY.scroll, xs))))
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

        dataset.series(series)
        page.map(_.focus).map(x => x)

        div(
          display.flex,
          flexGrow := 1,
          flexDirection.column,
          DatasetElements.chartContainer { (dim, owner) =>
            dim.combineWith(page.map(_.state)).map { case (w, h, state) =>
              DateSeriesChartElement(
                yLabel = "Compressed size (MiB)",
                dayWidth = state.scale.getOrElse(2d),
                maxWidth = w,
                maxHeight = h - 7,
                yStep = 100,
                series = dataset.providers
                  .filter { case (k, _) =>
                    state.versions.contains(k.name -> k.version)
                  }
                  .groupBy(x => x._1.name -> x._1.version)
                  .map((k, xs) => k -> xs.sortBy(_._1.date)),
                groupLabelFn = { case (compiler, version) => s"($compiler-$version)" },
                groupLabelsFn = (xs, limit) =>
                  xs.groupMap(_._1) { case (c, v) => (c, v) }
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
                dateFn = _._1.date,
                yFn = _._2.toDouble / 1024 / 1024,
                yErrorFn = _ => 0d,
                scrollX,
                page.map(_.focus.flatMap(x => dataset.providers.lift(x.index))),
                { case (_, k) =>
                  val p = page.observe(owner).now()
                  WebApp.router.replaceState(
                    p.copy(focus = Some(SizeFocus(dataset.providers.indexOf(k), FocusGroup.Compiler)))
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
      display.flex,
      flexGrow := 1,
      justifyContent.center,
      alignContent.center
    )
  }

}
