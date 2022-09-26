package uob_hpc.rtb

import com.raquo.laminar.api.L.*

object MarkdownElement {

  PrismJS.use()

  def apply(markdown: String, section: Signal[Option[String]], baseUrl: String) = div(
    cls := "section",
    div(
      cls := "container",
      div(
        cls := "content",
        onMountCallback { ctx =>
          ctx.thisNode.ref.innerHTML = markdown
          ctx.thisNode.ref.getElementsByTagName("a").foreach { e =>
            e.getAttribute("href") match {
              case s"#$id" => e.setAttribute("href", s"$baseUrl/$id")
              case _       => ()
            }
          }
          section.changes
            .collect { case Some(section) =>
              section.toLowerCase.replace(' ', '-')
            }
            .foreach { s =>
              if (!s.isBlank) {
                Option(ctx.thisNode.ref.querySelector(s"#$s")).foreach(_.scrollIntoView())
              }
            }(ctx.owner)
          ()
        }
      ), 
      onMountCallback(c => typings.prismjs.mod.highlightAllUnder(c.thisNode.ref))
    )
  )

}
