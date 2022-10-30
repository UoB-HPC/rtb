package uob_hpc.rtb

import scala.collection.immutable.ArraySeq
import com.raquo.laminar.api.L.*
import scala.collection.immutable.SortedSet
import com.raquo.laminar.nodes.ReactiveHtmlElement
import java.time.format.DateTimeFormatter
import uob_hpc.rtb.WebApp.FocusGroup
import com.raquo.airstream.web.AjaxEventStream
import java.time.ZoneOffset
object DatasetElements {

  def leftControlContainer(header: ReactiveHtmlElement[_], width: Int)(body: ReactiveHtmlElement[_]) =
    article(
      overflowY.hidden,
      flexGrow := "1",
      margin   := "8px 8px 8px 16px",
      cls      := "message",
      minWidth := width.px,
      maxWidth := width.px,
      div(cls := "message-header", height := 2.5.em, header),
      div(cls := "message-body", height   := 100.pct, padding := 8.px, body)
    )

  def rightControlContainer(header: ReactiveHtmlElement[_])(body: ReactiveHtmlElement[_]) =
    article(
      overflowY.hidden,
      flexGrow := "1",
      margin   := "8px 16px 8px 8px",
      cls      := "message",
      div(cls := "message-header", height := 2.5.em, header),
      div(cls := "message-body", height   := 100.pct, padding := 0.px, paddingBottom := 16.px, body)
    )

  def focusPanelHeader(key: Key, current: FocusGroup, navigate: FocusGroup => Binder[HtmlElement]) = Seq(
    span(
      fontFamily := "monospace",
      fontSize   := 0.9.em,
      s"${key.extra.map(s => s"[$s] ").getOrElse("")}${key.name}-${key.version} ${key.date.format(DateTimeFormatter.ISO_DATE)}"
    ),
    div(
      cls        := "buttons has-addons ",
      marginLeft := "auto",
      ul(
        button(
          cls                                  := "button is-small is-dark",
          cls.toggle("is-info", "is-selected") := current == FocusGroup.Compiler,
          span("Compiler"),
          navigate(FocusGroup.Compiler)
        ),
        button(
          cls                                  := "button is-small is-dark",
          cls.toggle("is-info", "is-selected") := current == FocusGroup.Output,
          span("Output"),
          navigate(FocusGroup.Output)
        )
      )
    )
  )

  def focusCompilerPanel(dataset: Dataset, key: Key) = {
    val grouped =
      dataset.providers.groupBy(x => x._1.name -> x._1.version).map((k, xs) => k -> xs.sortBy(_._1.date))

    val file =
      s"${key.name}-${key.version}.${key.date.format(DateTimeFormatter.ISO_DATE)}Z.${key.extra.getOrElse("")}"

    val repoBaseUrl = key.name match {
      case "gcc"  => Some(s"https://github.com/gcc-mirror/gcc")
      case "llvm" => Some(s"https://github.com/llvm/llvm-project")
      case _      => None
    }

    val build = AjaxEventStream
      .get(url = s"https://uob-hpc.github.io/compiler-snapshots/${key.name}/$file.json")
      .map(_.responseText)
      .map(Pickler.web.read[Build](_))

    val changes = build.map { build =>
      ul(
        build.changes.map { case (hash, date, message) =>
          li(
            fontSize.smaller,
            a(
              s"[$hash]",
              fontFamily := "monospace",
              repoBaseUrl.map(u => href := s"$u/commit/$hash"),
              target := "_blank"
            ),
            " ",
            span(
              date.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE),
              fontFamily := "monospace"
            ),
            " ",
            message
          )
        }
      )
    }

    val diff = grouped.get(key.name -> key.version).map(xs => xs -> xs.indexWhere(_._1 == key)) match {
      case Some((xs, n)) if n > 0 => // excludes 0 because 0 doesn't have a parent commit
        for {
          from    <- xs(n - 1)._1.extra
          to      <- xs(n)._1.extra
          diffUrl <- repoBaseUrl.map(u => s"$u/compare/$from..$to")
        } yield a(s"View $from..$to on GitHub", target := "_blank", href := diffUrl)
      case _ => None
    }

    table(
      cls             := "table",
      backgroundColor := "transparent",
      thead(),
      tbody(
        tr(
          td("Diff"),
          td(diff.getOrElse("N/A (initial data point)"))
        ),
        tr(
          td("Commits (", child.text <-- build.map(_.changes.size.toString), ")"),
          td(child <-- changes)
        )
      )
    )
  }

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
