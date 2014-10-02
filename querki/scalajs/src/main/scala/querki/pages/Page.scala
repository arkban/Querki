package querki.pages

import org.scalajs.dom
import scalatags.JsDom.all._

import querki.globals._

import querki.comm._
import querki.display.{Gadget, WrapperDiv}

trait Page extends Gadget[dom.HTMLDivElement] with EcologyMember {
  
  lazy val DataAccess = interface[querki.data.DataAccess]
  
  /**
   * The title of this page. Concrete subclasses must fill this in.
   */
  def title:String
  
  /**
   * The contents of this page. Concrete subclasses must fill this in.
   */
  def pageContent:Modifier
  
  val contentDiv = new WrapperDiv
  
  def replaceContents(newContent:dom.Element) = {
    contentDiv.replaceContents(newContent)
  }
  
  def doRender() = {
    div(cls:="guts container-fluid",
      div(cls:="row-fluid",
        contentDiv(cls:="querki-content span12",
          // The link to the Space:
          DataAccess.space match {
            case Some(space) =>
              div(cls:="_smallSubtitle _spaceLink _noPrint",
                a(href:=controllers.Application.thing(DataAccess.userName, DataAccess.spaceId, DataAccess.spaceId).url,
                  space.displayName)
              )
            case None => div(cls:="_smallSubtitle _spaceLink _noPrint", raw("&nbsp;"))
          },
          pageContent
        )
      )
    )
  }
}