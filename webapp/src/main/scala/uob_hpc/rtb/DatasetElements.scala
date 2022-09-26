package uob_hpc.rtb

import scala.collection.immutable.ArraySeq
import com.raquo.laminar.api.L.*
import scala.collection.immutable.SortedSet
import com.raquo.laminar.nodes.ReactiveHtmlElement
object DatasetElements {

  def leftControlContainer(header: ReactiveHtmlElement[_])(body: ReactiveHtmlElement[_]) =
    article(
      flexGrow := "1",
      margin   := "8px 8px 8px 16px",

      cls      := "message",
      div(cls := "message-header", header),
      div(cls := "message-body", body)
    )

  def rightControlContainer(header: ReactiveHtmlElement[_])(body: ReactiveHtmlElement[_]) =
    article(
      flexGrow := "1",
      margin   := "8px 16px 8px 8px",

      cls      := "message",
      div(cls := "message-header", header),
      div(cls := "message-body", body)
    )

  def chartContainer(mkChart: (Signal[(Int, Int)], Owner) => Source[Child]) = {
    val clientSize = Var((0, 0))
    div(
      marginTop := 8.px,
      flexGrow  := 1,
      width     := 100.vw,
      minWidth  := 800.px,
      minHeight := 400.px,
      position.relative,
      alignSelf.center,
      inContext(c => ResizeObserverBinder(() => clientSize.set(c.ref.clientWidth -> c.ref.clientHeight))),
      onMountInsert(c => child <-- (mkChart(clientSize.signal, c.owner)))
    )
  }

  def error(e: Throwable) = div(
    display.flex,
    alignContent.center,
    justifyContent.center,
    flexDirection.column,
    e.stackTraceAsString.linesIterator.map(span(_)).toSeq
  )

  def loading = div(
    display.flex,
    alignContent.center,
    justifyContent.center,
    flexDirection.column,
    button(cls := "button is-loading is-white is-large"),
    br(),
    span("Loading dataset...")
  )

  def timeGroupSelect[S](
      page: Signal[S],
      get: S => Option[WebApp.TimeGroup],
      set: (S, Option[WebApp.TimeGroup]) => S,
      bind: S => Unit
  ) = div(
    "Measurement:",
    br(),
    cls := "control",
    WebApp.TimeGroup.values.map { v =>
      label(
        cls := "radio",
        input(
          typ  := "radio",
          name := "time-group",
          checked <-- page.map(p => get(p).getOrElse(WebApp.TimeGroup.values.head) == v),
          onMountBind { c =>
            onInput.mapToChecked --> { checked =>
              if (checked) bind(set(page.observe(c.owner).now(), Some(v)))
            }
          }
        ),
        v.toString
      )
    }.toSeq
  )

  def compilerSelect[S](
      xs: ArraySeq[Series[Key]],
      page: Signal[S],
      get: S => SortedSet[(String, String)],
      set: (S, SortedSet[(String, String)]) => S,
      bind: S => Unit
  ) = xs
    .flatMap(_.results)
    .map(_.key)
    .groupBy(_.name)
    .toSeq
    .map { case (name, xs) =>
      name -> xs
        .map(_.version)
        .distinct
        .sortBy(s => s.toIntOption -> s)
        .flatMap(version => UI.mkCheckBox(version, page, get, set, bind, name -> version) :: br() :: Nil)
    }

  def jobSelect[S](
      xs: ArraySeq[Series[Key]],
      page: Signal[S],
      get: S => SortedSet[String],
      set: (S, SortedSet[String]) => S,
      bind: S => Unit
  ) = xs
    .map(_.jobName)
    .distinct
    .sorted
    .map(job => UI.mkCheckBox(job, page, get, set, bind, job) :: br() :: Nil)

  def scaleSelect[S](
      page: Signal[S],
      get: S => Option[Double],
      set: (S, Option[Double]) => S,
      bind: S => Unit
  ) = {
    val defaultScale = 2.0
    val minScale     = 0.5
    val maxScale     = 5.0
    val scaleStep    = 0.1
    val mapped       = page.map(p => get(p).getOrElse(defaultScale)).map(_.toString)
    div(
      span(child <-- mapped.map(v => s"Scale (Px/Day): $v")),
      br(),
      input(
        typ      := "range",
        stepAttr := scaleStep.toString,
        maxAttr  := maxScale.toString,
        minAttr  := minScale.toString,
        value <-- mapped,
        onMountBind(c =>
          onInput.mapToValue
            .map(_.toDoubleOption.getOrElse(defaultScale).min(maxScale).max(minScale)) --> { s =>
            bind(set(page.observe(c.owner).now(), Some(s)))
          }
        )
      )
    )
  }

}
