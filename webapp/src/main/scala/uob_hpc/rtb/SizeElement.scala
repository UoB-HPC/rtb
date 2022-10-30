package uob_hpc.rtb

import com.raquo.laminar.api.L.*
import uob_hpc.rtb.WebApp.{FocusGroup, Page, SizeFocus, ViewState}
import scala.collection.immutable.SortedSet
import java.time.format.DateTimeFormatter

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

        val controls = DatasetElements.leftControlContainer(p(s"Dataset: $series"), 400) {
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
          div(
            display.flex,
            alignItems.center,
            justifyContent.flexEnd,
            width := 100.pct,
            children <-- page.map {
              case Page.Size(_, _, None) => Seq(span(" -- "))
              case page @ Page.Size(_, _, Some(state)) =>
                val entry @ (key, _) = dataset.providers(state.index)
                DatasetElements.focusPanelHeader(
                  key,
                  state.group,
                  g => WebApp.navigateTo(page.copy(focus = Some(state.copy(group = g))))
                )
            }
          )
        ) {
          div(
            overflowY.scroll,
            height := 100.pct,
            child <-- page.map(_.focus).map {
              case None => span("Select a data point in the chart for details.", margin := 16.px)
              case Some(state) =>
                state.group match {
                  case FocusGroup.Compiler =>
                    DatasetElements.focusCompilerPanel(dataset, dataset.providers(state.index)._1)
                  case FocusGroup.Output =>
                    val (key, size) = dataset.providers(state.index)
                    val file =
                      s"${key.name}-${key.version}.${key.date.format(DateTimeFormatter.ISO_DATE)}Z.${key.extra.getOrElse("")}"
                    table(
                      cls             := "table",
                      backgroundColor := "transparent",
                      thead(),
                      tbody(
                        tr(
                          td("Download"),
                          td(
                            a(
                              s"$file.tar.xz",
                              href := s"https://github.com/uob-hpc/compiler-snapshots/releases/download/$file/$file.tar.xz"
                            )
                          )
                        ),
                        tr(
                          td("Size"),
                          td(f"${size.toDouble / 1024 / 1024}%.2f MB (${size.toDouble / 1000 / 1000}%.2f MiB)")
                        )
                      )
                    )
                }
            }
          )
        }

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
                      else s"$job ($vers)" :: Nil
                    }
                    .toList,
                dateFn = _._1.date,
                yFn = _._2.toDouble / 1024 / 1024,
                yErrorFn = _ => 0d,
                scrollX,
                page.map(_.focus.flatMap(x => dataset.providers.lift(x.index))),
                { case (_, k) =>
                  val p     = page.observe(owner).now()
                  val index = dataset.providers.indexOf(k)
                  WebApp.router.replaceState(
                    p.copy(focus =
                      Some(p.focus.map(_.copy(index = index)).getOrElse(SizeFocus(index, FocusGroup.Compiler)))
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
      display.flex,
      flexGrow := 1,
      justifyContent.center,
      alignContent.center
    )
  }

}
