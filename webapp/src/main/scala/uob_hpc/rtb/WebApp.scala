package uob_hpc.rtb

import com.raquo.airstream.state.{Val, Var}
import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import org.scalajs.dom
import uob_hpc.rtb.WebApp.ViewState.PathType
import urldsl.vocabulary.UrlMatching

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.collection.immutable.{ArraySeq, SortedSet}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.{Failure, Success, Try}
import Pickler.*

import scala.reflect.ClassTag

object WebApp {

  object TimeGroup {
    given ReadWriter[TimeGroup]                = macroRW
    def apply(name: String): Option[TimeGroup] = TimeGroup.values.find(_.name == name)
  }
  enum TimeGroup(val name: String) {
    case Real   extends TimeGroup("real")
    case System extends TimeGroup("sys")
    case User   extends TimeGroup("user") //  System, User
  }

  case class ViewState(
      versions: SortedSet[(String, String)] = SortedSet.empty,
      jobs: SortedSet[String] = SortedSet.empty,
      scale: Option[Double] = None,
      group: Option[TimeGroup] = None
  ) {
    def asPath: PathType = (
      Option.when(versions.nonEmpty)(versions.map((name, ver) => s"$name-$ver").mkString(SeqDelim)),
      Option.when(jobs.nonEmpty)(jobs.mkString(SeqDelim)),
      scale.map(x => f"$x%.2f"),
      group.map(_.name)
    )
  }
  object ViewState {
    given ReadWriter[ViewState] = macroRW

    type PathType = (Option[String], Option[String], Option[String], Option[String])
    def apply(path: PathType): ViewState = {
      val (versions, jobs, scale, group) = path
      new ViewState(
        versions
          .to(SortedSet)
          .flatMap(_.split(s"\\$SeqDelim").collect { case s"$name-$version" => name -> version }.to(SortedSet)),
        jobs.to(SortedSet).flatMap(_.split(s"\\$SeqDelim").map(_.strip).to(SortedSet)),
        scale.flatMap(_.toDoubleOption),
        group.flatMap(TimeGroup(_))
      )
    }
  }

  object FocusGroup {
    given ReadWriter[FocusGroup]                = macroRW
    def apply(name: String): Option[FocusGroup] = FocusGroup.values.find(_.name == name)
  }
  enum FocusGroup(val name: String) {
    case Compiler extends FocusGroup("compiler")
    case Output   extends FocusGroup("output")
  }

  case class PerfFocus(job: String, name: String, version: String, index: Int, group: FocusGroup) {
    def asPath = s"$job^$name^$version^$index^${group.name}"
  }
  object PerfFocus {
    given ReadWriter[PerfFocus] = macroRW
    def apply(path: String): Option[PerfFocus] = {
      println(s"Dec:${path}")
      path match {
        case s"$job^$name^$version^$index^$group" =>
          for {
            g <- FocusGroup(group)
            i <- index.toIntOption
          } yield PerfFocus(job, name, version, i, g)
        case _ => None
      }
    }
  }

  case class SizeFocus(index: Int, group: FocusGroup) {
    def asPath = s"$index^${group.name}"
  }
  object SizeFocus {
    given ReadWriter[SizeFocus] = macroRW
    def apply(path: String): Option[SizeFocus] = path match {
      case s"${index}^$group" =>
        for {
          g <- FocusGroup(group)
          i <- index.toIntOption
        } yield SizeFocus(i, g)
      case _ => None
    }
  }

  sealed abstract class Page(val title: String)
  object Page {
    case class Index(section: Option[String] = None)                            extends Page("Index")
    case class Perf(series: String, state: ViewState, focus: Option[PerfFocus]) extends Page(s"[$series] Perf")
    case class Size(series: String, state: ViewState, focus: Option[SizeFocus]) extends Page(s"[$series] Size")
    case class JobInfo(series: String, state: ViewState, job: Option[String])   extends Page(s"[$series] Job")
    case class RunnerInfo(series: String, state: ViewState)                     extends Page(s"[$series] Runner")
    given ReadWriter[Page]       = macroRW
    given ReadWriter[Index]      = macroRW
    given ReadWriter[Perf]       = macroRW
    given ReadWriter[Size]       = macroRW
    given ReadWriter[JobInfo]    = macroRW
    given ReadWriter[RunnerInfo] = macroRW
  }

  private val basePath = s"${dom.window.location.pathname}#"

  private val SeqDelim = "^"
  private val ViewStateParams =
    param[String]("versions").? & param[String]("jobs").? & (param[String]("scale").? & param[String]("group").?)

  val router = new Router[Page](
    routes = List(
      Route[Page.Index, Either[Unit, String]](
        p => p.section.toRight(()),
        e => Page.Index(e.toOption),
        (root / endOfSegments) || (root / segment[String] / endOfSegments),
        basePath
      ),
      Route.withQuery[Page.Perf, Either[String, (String, String)], PathType](
        page => UrlMatching(page.focus.map(f => page.series -> f.asPath).toRight(page.series), page.state.asPath),
        {
          case UrlMatching(Left(series), query)           => Page.Perf(series, ViewState(query), None)
          case UrlMatching(Right((series, focus)), query) => Page.Perf(series, ViewState(query), PerfFocus(focus))
        },
        ((root / "dataset" / segment[String] / "perf" / endOfSegments) ||
          (root / "dataset" / segment[String] / "perf" / segment[String] / endOfSegments)) ? ViewStateParams,
        basePath
      ),
      Route.withQuery[Page.Size, Either[String, (String, String)], PathType](
        page => UrlMatching(page.focus.map(f => page.series -> f.asPath).toRight(page.series), page.state.asPath),
        {
          case UrlMatching(Left(series), query)           => Page.Size(series, ViewState(query), None)
          case UrlMatching(Right((series, focus)), query) => Page.Size(series, ViewState(query), SizeFocus(focus))
        },
        ((root / "dataset" / segment[String] / "size" / endOfSegments) ||
          (root / "dataset" / segment[String] / "size" / segment[String] / endOfSegments)) ? ViewStateParams,
        basePath
      ),
      Route.withQuery[Page.JobInfo, Either[String, (String, String)], PathType](
        page =>
          page.job match {
            case None    => UrlMatching(Left(page.series), page.state.asPath)
            case Some(x) => UrlMatching(Right((page.series, x)), page.state.asPath)
          },
        {
          case UrlMatching(Left(series), query)         => Page.JobInfo(series, ViewState(query), None)
          case UrlMatching(Right((series, job)), query) => Page.JobInfo(series, ViewState(query), Some(job))
        },
        (
          (root / "dataset" / segment[String] / "job" / endOfSegments) ||
            (root / "dataset" / segment[String] / "job" / segment[String] / endOfSegments)
        ) ? ViewStateParams,
        basePath
      ),
      Route.withQuery[Page.RunnerInfo, String, PathType](
        page => UrlMatching(page.series, page.state.asPath),
        url => Page.RunnerInfo(url.path, ViewState(url.params)),
        (root / "dataset" / segment[String] / "runner" / endOfSegments) ? ViewStateParams,
        basePath
      )
    ),
    getPageTitle = p => s"RTB - ${p.title}",
    serializePage = Pickler.web.write(_),
    deserializePage = Pickler.web.read[Page](_)
  )(
    $popStateEvent = windowEvents.onPopState,
    owner = unsafeWindowOwner
  )

  def navigateTo(page: Page, replace: Boolean = false): Binder[HtmlElement] = Binder { el =>
    val isLinkElement = el.ref.isInstanceOf[dom.html.Anchor]
    if (isLinkElement)
      el.amend(href(router.relativeUrlForPage(page)))
    (onClick
      .filter(ev => !(isLinkElement && (ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey)))
      .preventDefault
      --> (_ => if (replace) router.replaceState(page) else router.pushState(page))).bind(el)
  }

  @JSImport("bulma/css/bulma.min.css", JSImport.Namespace)
  @js.native
  object Bulma extends js.Object

  @JSImport("@fortawesome/fontawesome-free/css/all.css", JSImport.Namespace)
  @js.native
  object FontAwesomeCSS extends js.Object

  private def mkNavBar(dataset: Signal[Deferred[Dataset]]) = {
    val navState = router.$currentPage.map {
      case p @ Page.Perf(series, state, _)    => Some((p, series, state))
      case p @ Page.Size(series, state, _)    => Some((p, series, state))
      case p @ Page.RunnerInfo(series, state) => Some((p, series, state))
      case p @ Page.JobInfo(series, state, _) => Some((p, series, state))
      case _                                  => None
    }
    nav(
      zIndex := 1,
      display.flex,
      flexDirection.row,
//      marginBottom := "px",
      role       := "navigation",
      aria.label := "main navigation",
      boxShadow  := "0px 2px 2px rgba(0,0,0,0.2)",
      div(cls := "navbar-brand", a(cls := "navbar-item", href := "", fontSize := 1.5.em, "RTB")),
      span(
        cls := "navbar-item",
        display.flex,
        alignItems.center,
        child <-- dataset
          .map {
            case Deferred.Success(data) => data.series.keys.to(ArraySeq).sorted
            case _                      => ArraySeq.empty[String]
          }
          .map { xs =>
            UI.mkSelect(
              "fa-database",
              navState.map(_.map(_._2)),
              Observer {
                case None    => WebApp.router.replaceState(Page.Index())
                case Some(s) => WebApp.router.replaceState(Page.Perf(s, ViewState(), None))
              },
              None +: xs.map(Some(_)),
              {
                case None    => "Select Dataset..."
                case Some(x) => x
              },
              {
                case "Select Dataset..." => None
                case x                   => Some(x)
              }
            )
          }
      ),
      child <-- navState.map {
        case None => emptyNode
        case Some((p, series, state)) =>
          inline def mkSubPage[A <: Page: ClassTag](inline to: => A, inline name: String, inline icon: String) =
            button(
              cls := (p match {
                case _: A => "button is-info is-selected"
                case _    => "button"
              }),
              span(cls := "icon is-small", i(cls := s"fas $icon")),
              span(name),
              navigateTo(to)
            )
          div(
            cls := "navbar-item",
            alignItems.center,
            display.flex,
            margin := "0 auto",
            div(
              cls := "buttons has-addons",
              mkSubPage(Page.Perf(series, state, None), "Compile Perf.", "fa-stopwatch"),
              mkSubPage(Page.Size(series, state, None), "Compiler Size", "fa-file-archive"),
              mkSubPage(Page.JobInfo(series, state, None), "Job Info", "fa-code"),
              mkSubPage(Page.RunnerInfo(series, state), "Runner Info", "fa-server")
            )
          )
      }
    )
  }

  @JSImport("./public/index.md", JSImport.Namespace)
  @js.native
  private object IndexMD extends js.Object { def default: String = js.native }

  @main def main(): Unit = {

    // XXX keep a reference to these
    val _ = (Bulma, FontAwesomeCSS)

    val dataset = Var[Deferred[Dataset]](Deferred.Pending)

    fetchJson[Dataset]("./dataset/dataset.json").onComplete(x =>
      dataset.set(x match {
        case Failure(e) => Deferred.Error(e)
        case Success(x) => Deferred.Success(x)
      })
    )

    val globalStylesheet = dom.document.createElement("style")
    globalStylesheet.textContent = """
      |
      |.datapoint{
      |  transform-origin: 50% 50%;
      |}
      |.datapoint:hover {
      |   transform: scale(1.2);
      |}
      |
      |.datapoint .focused {
      |   transform: scale(1.5);
      |}
	  |
	  |/* Prevent Bulma classes from breaking PrismJS's styles */
	  |pre code [class~=token] {
      |  font: inherit;
      |  background: inherit;
	  |  align-items: inherit;
      |  background-color: inherit;
      |  border-radius: inherit;
      |  display: inherit;
      |  font-size: inherit;
      |  height: inherit;
      |  justify-content: inherit;
      |  margin-right: inherit;
      |  min-width: inherit;
      |  padding: inherit;
      |  text-align: inherit;
      |  vertical-align: inherit;
      |}
      |
      |""".stripMargin
    dom.document.head.append(globalStylesheet)

    val pageSplitter = SplitRender[Page, HtmlElement](router.$currentPage)
      .collectSignal[Page.Index](p =>
        MarkdownElement(IndexMD.default, p.map(_.section), router.relativeUrlForPage(Page.Index()))
      )
      .collectSignal[Page.Perf](p => PerfElement(dataset.signal, p))
      .collectSignal[Page.Size](p => SizeElement(dataset.signal, p))
      .collectSignal[Page.RunnerInfo](_ => RunnerInfoElement())
      .collectSignal[Page.JobInfo](JobInfoElement(_, dataset.signal))

    render(
      dom.document.querySelector("body"),
      div(
        height    := 100.vh,
        width     := 100.vw,
        minWidth  := 900.px,
        minHeight := 500.px,
        display.flex,
        flexDirection.column,
        alignItems.stretch,
        mkNavBar(dataset.signal),
        child <-- pageSplitter.$view
      )
    )

  }
}
