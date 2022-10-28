package uob_hpc.rtb

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom.{ResizeObserver, WheelEvent, html}
import uob_hpc.rtb.WebApp.ViewState

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoUnit, IsoFields}
import scala.collection.immutable.ArraySeq
import com.raquo.laminar.nodes.ReactiveNode

object DateSeriesChartElement {

  private inline def reblock1D[A](
      inline xs: List[A],
      inline fmin: A => Double,
      inline fmax: A => Double
  ): List[(Double, List[A], Double)] =
    xs.sortBy(fmin).foldLeft(List.empty[(Double, List[A], Double)]) {
      case ((min, ys, max) :: xs, x) if fmin(x) <= max => (min, x :: ys, fmax(x)) :: xs
      case (ys, x)                                     => (fmin(x), x :: Nil, fmax(x)) :: ys
    }

  def apply[G, A](
      yLabel: String,
      dayWidth: Double,
      maxWidth: Double,
      maxHeight: Double,
      yStep: Int,
      series: Map[G, Seq[A]],
      groupLabelFn: G => String,
      groupLabelsFn: (List[G], Int) => List[String],
      dateFn: A => LocalDate,
      yFn: A => Double,
      yErrorFn: A => Double,
      scrollX: Var[(Double, Double)],
      focused: Signal[Option[A]],
      set: (G, A) => Unit
  ): ReactiveHtmlElement[html.Div] = {
    // val scrollX = Var((0d, 0d))

    val DateLabelOffset   = 90d
    val SeriesLabelHeight = 15d
    val CharOffset        = 8.5d

    val seriesXPadding = 3 * dayWidth

    val SeriesLabelOffset = series.keys.map(groupLabelFn(_).length).maxOption.getOrElse(0) * CharOffset

    val ViewportYMax = maxHeight - DateLabelOffset
    val ViewportXMax = maxWidth - SeriesLabelOffset

    val xs   = series.values.flatten.to(Seq).sortBy(dateFn)
    val minX = xs.view.map(dateFn).minOption
    val maxX = xs.view.map(dateFn).maxOption

    val ys   = series.values.flatten.map(yFn).to(ArraySeq)
    val maxY = ys.maxOption.getOrElse(0d)

    inline def dateToPx(inline that: LocalDate)  = minX.map(ChronoUnit.DAYS.between(_, that) * dayWidth).getOrElse(0d)
    inline def secondToPx(inline second: Double) = linearScale(second, 0d, maxY, ViewportYMax, 0d)

    inline def asX(inline x: A)     = dateToPx(dateFn(x))
    inline def asY(inline x: A)     = secondToPx(yFn(x))
    inline def makeOne(inline x: A) = asX(x) -> asY(x)

    val seriesLabels = xs
      .foldLeft(Vector.empty[A]) {
        case (Vector(), y)                                             => Vector(y)
        case (xs :+ x, y) if (asX(x) - asX(y)).abs > SeriesLabelHeight => (xs :+ x :+ y)
        case (xs, y)                                                   => xs
      }
      .map { k =>
        svg.text(
          svg.dominantBaseline := "central",
          svg.transform        := s"translate(${asX(k)}, -${DateLabelOffset - 4}) rotate(90)",
          s"${dateFn(k).format(DateTimeFormatter.ISO_LOCAL_DATE)}"
        )
      }

    val yRange       = 0 to maxY.ceil.toInt by yStep
    val YLabelOffset = yRange.lastOption.map(_.toString.length.toDouble * CharOffset).getOrElse(0d) + 40d
    val gridLabels =
      yRange.map(n => n -> secondToPx(n)).map { case (n, dy) =>
        svg.text(
          n.toString,
          svg.y                := dy.px,
          svg.x                := (YLabelOffset - CharOffset).px,
          svg.dominantBaseline := "central",
          svg.textAnchor       := "end"
        )

      }
    val gridLines =
      yRange.drop(1).map(n => n -> secondToPx(n)).map { case (n, dy) =>
        svg.line(
          svg.x1            := YLabelOffset.px,
          svg.y1            := dy.px,
          svg.x2            := ViewportXMax.px,
          svg.y2            := dy.px,
          svg.strokeOpacity := (if (n % 2 == 0) "0.5" else "0.2"),
          svg.stroke        := "black"
        )
      }
    val ViewportWidth = maxWidth - YLabelOffset - SeriesLabelOffset

    val (seriesLegend, seriesLegendDynamicMarker) = series
      .to(ArraySeq)
      .sortBy(x => groupLabelFn(x._1))
      .map { case (name, xs) =>
        val xPointOffsets = xs.map(e => e -> (seriesXPadding + asX(e)))
        val yOffsetAndConnector = scrollX.signal.map { case (xRawLeft, h) =>
          val xMin = xRawLeft
          val xMax = xMin + ViewportWidth
          if (xPointOffsets.last._2 < xMax) { // last point is left of viewport min
            val xOrigin = xPointOffsets.last._2
            val yOrigin = asY(xPointOffsets.last._1)
            if (xOrigin < xMin) None -> Seq.empty // beyond viewport
            else
              Some(yOrigin) -> Seq(
                (xOrigin - xMin, yOrigin),
                (ViewportWidth, yOrigin)
              )
          } else if (xPointOffsets.head._2 > xMax) { // first point is to the right of viewport max
            None -> Seq.empty
          } else { // label is within first and last point
            Some(xPointOffsets.findLast(_._2 <= xMax).map(x => asY(x._1)).getOrElse(0d)) -> Seq.empty
          }
        }

        svg.g( // the label
          child <-- yOffsetAndConnector.map(_._1).map {
            case None => emptyNode
            case Some(yy) =>
              val ArrowWidth = 15
              svg.g(
                svg.transform := s"translate($ViewportWidth, $yy)",
                svg.polygon(
                  svg.fill := mkColour(name.hashCode).hexString,
                  svg.points := Seq(
                    (0, 0),
                    (ArrowWidth, -SeriesLabelHeight / 2),
                    (SeriesLabelOffset, -SeriesLabelHeight / 2),
                    (SeriesLabelOffset, SeriesLabelHeight / 2),
                    (ArrowWidth, SeriesLabelHeight / 2)
                  ).svgPoints
                ),
                svg.text(
                  s"$name",
                  svg.fontSize         := (SeriesLabelHeight * 0.8).px,
                  svg.x                := ArrowWidth.px,
                  svg.dominantBaseline := "central",
                  svg.textAnchor       := "begin"
                )
              )
          }
        ) -> svg.g(
          svg.polyline( // line that connects the point with the label
            svg.points <-- yOffsetAndConnector.map(_._2.svgPoints),
            svg.fill          := "none",
            svg.strokeOpacity := "0.6",
            svg.strokeWidth   := 1.px,
            svg.stroke        := mkColour(name.hashCode).hexString
          )
        )
      }
      .unzip

    val u = scrollX.signal.map { case (xRawLeft, h) =>
      val (seriesLegend2) = series
        .to(ArraySeq)
        .sortBy(x => groupLabelFn(x._1))
        .flatMap { case (name, xs) =>
          val xPointOffsets = xs.map(e => e -> (seriesXPadding + asX(e)))
          val xMin          = xRawLeft
          val xMax          = xMin + ViewportWidth
          val yOffset =
            if (xPointOffsets.last._2 < xMax) { // last point is left of viewport min
              val xOrigin = xPointOffsets.last._2
              val yOrigin = asY(xPointOffsets.last._1)
              if (xOrigin < xMin) None // beyond viewport
              else Some(yOrigin)
            } else if (xPointOffsets.head._2 > xMax) { // first point is to the right of viewport max
              None
            } else { // label is within first and last point
              Some(xPointOffsets.findLast(_._2 <= xMax).map(x => asY(x._1)).getOrElse(0d))
            }
          yOffset.map(name -> _)
        }

      reblock1D[(G, Double)](
        seriesLegend2.toList,
        _._2 - (SeriesLabelHeight / 2),
        _._2 + (SeriesLabelHeight / 2)
      ).map {
        case (min, (k, _) :: Nil, max) =>
          (
            (min, max),
            groupLabelFn(k) :: Nil,
            mkColour(k.hashCode)
          )
        case (min, xs, max) =>
          val greyScale = (255.0 / xs.size.toDouble).ceil.toInt
          (
            (min, max),
            groupLabelsFn(xs.map(_._1), (SeriesLabelOffset / CharOffset).ceil.toInt),
            Colour(greyScale, greyScale, greyScale)
          )
      }.map { case ((min, max), labels, c) =>
        val ArrowWidth = 15
        val yCentre    = (min + max) / 2
        svg.g(
          svg.transform := s"translate($ViewportWidth, ${yCentre})",
          svg.polygon(
            svg.fillOpacity := "0.8",
            svg.fill        := c.hexString,
            svg.points := Seq(
              // ->
              (0, min - yCentre + SeriesLabelHeight / 2),
              (ArrowWidth, -SeriesLabelHeight / 2),
              (SeriesLabelOffset, -SeriesLabelHeight / 2),
              (SeriesLabelOffset, SeriesLabelHeight * (labels.size.toDouble - 0.5)),
              (ArrowWidth, SeriesLabelHeight * (labels.size.toDouble - 0.5)),
              (0, max - yCentre - SeriesLabelHeight / 2)
            ).svgPoints
          ),
          svg.text(
            svg.fontSize         := (SeriesLabelHeight * 0.8).px,
            svg.transform        := s"translate($ArrowWidth, 0)",
            svg.dominantBaseline := "central",
            svg.textAnchor       := "begin",
            svg.fill             := "white",
            labels.zipWithIndex.map { case (l, i) =>
              svg.tspan(svg.x := 0.px, svg.y := (i * SeriesLabelHeight).px, l)
            }
          )
        )
      }
    }

    val highlighted = new EventBus[Option[A]]

    val seriesPoints = series
      .flatMap { case (name, xs) =>
        xs.map { x =>
          val (xx, yy) = makeOne(x)
          svg.circle(
            // svg.cls := "datapoint",

            // inContext(c =>

            //   Seq(
            //      --> { e =>

            //       c.
            //       // println(e)
            //       // c.apply(svg.r := (8 + yErrorFn(x)).px)
            //     }
            //   )
            // ),
            onClick.preventDefault --> { _ => set(name, x) },
            svg.cx := xx.px,
            svg.cy := yy.px,
            svg.r <-- focused
              .map(_.contains(x))
              .map(h => (yErrorFn(x) + (if (h) 8 else 4)).px), // .preventDefault.mapTo(8 + yErrorFn(x)).map(_.px),
            // svg.r    := (4 + yErrorFn(x)).px,
            svg.fill := mkColour(name.hashCode).hexString
          )
        }
      }
      .to(ArraySeq)

    val seriesLines = series
      .map { case (name, xs) =>
        val ys = xs // .map( x => secondToPx(x.mean) ).sortBy(_.key.date)
        svg.polyline(
          svg.strokeWidth := 3.px,
          svg.stroke      := mkColour(name.hashCode).hexString,
          svg.fill        := "none",
          svg.points := (ys :+ ys.last)
            .sliding(2)
            .map {
              case Seq(x, xNext) =>
                val (xx, yy) = makeOne(x)
                s"$xx,$yy ${asX(xNext)},$yy"
              case Seq(x) =>
                val (xx, yy) = makeOne(x)
                s"$xx,$yy"
              case _ => ""
            }
            .mkString(" ")
        )
      }
      .to(ArraySeq)

    div( // data viewport
      // position.relative,

      position.absolute,
      width  := maxWidth.px,
      height := maxHeight.px,
      margin := "0 auto",
      0.inset,
      div(
        position.absolute,
        top    := 0.px,
        left   := YLabelOffset.px,
        bottom := 0.px,
        right  := SeriesLabelOffset.px,
        overflowX.scroll,
        overflowY.hidden,
        inContext { c =>
          inline def topAndHeight = (c.ref.scrollLeft, c.ref.clientWidth.toDouble)
          Seq(
            onMountCallback(e => e.thisNode.ref.scrollLeft = scrollX.now()._1),
            onScroll.mapTo(topAndHeight) --> scrollX,
            ResizeObserverBinder(() => scrollX.set(topAndHeight)),
            onWheel.preventDefault --> { e =>
              c.ref.scrollLeft += e.asInstanceOf[WheelEvent].deltaY
            }
          )
        },
        svg.svg(
          svg.width  := (maxX.map(dateToPx(_)).getOrElse(0d) + seriesXPadding * 2).px,
          svg.height := 100.pct,
          svg.g(seriesLines, svg.transform  := s"translate($seriesXPadding, 0)"),
          svg.g(seriesPoints, svg.transform := s"translate($seriesXPadding, 0)"),
          svg.g(seriesLabels, svg.transform := s"translate($seriesXPadding, $maxHeight)")
        )
      ),
      div( // data framnes and legends
        position.absolute,
        0.inset,
        pointerEvents.none,
        svg.svg(
          svg.height := maxHeight.px,
          svg.width  := maxWidth.px,
          svg.g(
            svg.text(
              yLabel,
              svg.transform        := s"rotate(-90) translate(${-ViewportYMax / 2}, ${YLabelOffset / 4})",
              svg.dominantBaseline := "central",
              svg.textAnchor       := "middle"
            ),
            //            svg.transform := s"translate($DateLabelOffset ,$TopBottomInset)",
            svg.polyline(
              svg.strokeWidth := 2.px,
              svg.points := Seq(
                (ViewportXMax - 1, 1),
                (YLabelOffset, 1),
                (YLabelOffset, ViewportYMax - 1),
                (ViewportXMax - 1, ViewportYMax - 1)
              ).svgPoints,
              svg.stroke := "black",
              svg.fill   := "none"
            ),
            svg.line(
              svg.strokeWidth   := 2.px,
              svg.stroke        := "black",
              svg.strokeOpacity := "0.3",
              svg.y1            := 0.px,
              svg.x1            := ViewportXMax.px,
              svg.y2            := ViewportYMax.px,
              svg.x2            := ViewportXMax.px
            ),
            gridLines,
            gridLabels
          ),
          svg.g(
            svg.transform := s"translate($YLabelOffset, 0)",
            //            seriesLegend,
            seriesLegendDynamicMarker,
            children <-- u
          )
        )
      )
    )

  }

}
