package uob_hpc.rtb

import com.raquo.airstream.ownership.{DynamicSubscription, Subscription}
import com.raquo.laminar.modifiers.Binder
import com.raquo.laminar.nodes.ReactiveElement
import org.scalajs.dom
import org.scalajs.dom.{HTMLElement, ResizeObserver, SVGElement}

import scala.scalajs.js.|

class ResizeObserverBinder[El <: ReactiveElement[dom.HTMLElement]](
    onNext: () => Unit
) extends Binder[El] {

  private var element: El                                 = _
  private var maybeResizeObserver: Option[ResizeObserver] = Option.empty

  override def bind(element: El): DynamicSubscription = {

    if (maybeResizeObserver.isDefined) {
      dom.console.error("resizeObserver can not be re-used")
    }

    this.element = element
    maybeResizeObserver = Some(
      ResizeObserver(
        callback = (entries, _) =>
          if (entries.nonEmpty && entries.head.target == element.ref.asInstanceOf[HTMLElement | SVGElement]) {
            onNext()
          }
      )
    )
    ReactiveElement.bindSubscription(element) { ctx =>
      maybeResizeObserver.foreach(_.observe(element.ref))
      Subscription(
        ctx.owner,
        cleanup = () => maybeResizeObserver.foreach(_.disconnect())
      )
    }

  }
}
