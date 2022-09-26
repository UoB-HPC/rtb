package uob_hpc.rtb

import com.raquo.airstream.core.Observable
import com.raquo.airstream.state.{Val, Var}
import com.raquo.laminar.api.L.*

import scala.scalajs.js
import scala.collection.immutable.SortedSet

object UI {

  def mkCheckBox[A](x: A, xs: Var[Set[A]], f: A => String, modifiers: Modifier[Input]*) = label(
    cls := "mr-1",
    input(
      modifiers,
      typ("checkbox"),
      controlled(
        checked <-- xs.signal.map(_.contains(x)),
        onClick.mapToChecked --> xs.updater[Boolean] { case (xs, v) => if (v) xs + x else xs - x }
      )
    ),
    f(x)
  )

  def mkCheckBox[A, S](
      name: String,
      state: Signal[S],
      get: S => SortedSet[A],
      set: (S, SortedSet[A]) => S,
      bind: S => Unit,
      x: A
  ) = label(
    cls := "mr-1",
    input(
      typ("checkbox"),
      checked <-- state.map(get(_).contains(x)),
      onMountBind { c =>
        onInput.mapToChecked --> { checked =>
          val s = state.observe(c.owner).now()
          bind(set(s, if (checked) get(s) + x else get(s) - x))
        }
      }
    ),
    name
  )

  def mkSelect[A](faIcon: String, in: Signal[A], out: Observer[A], xs: Seq[A], show: A => String, f: String => A) =
    div(
      flexGrow := 1,
      cls      := "control has-icons-left",
      div(
        cls := "select is-fullwidth",
        select(
          xs.map(x => option(show(x))),
          controlled(
            value <-- in.map(show),
            onChange.mapToValue.map(f) --> out
          )
        )
      ),
      span(cls := "icon is-left has-text-black", i(cls := s"fas $faIcon"))
    )

}
