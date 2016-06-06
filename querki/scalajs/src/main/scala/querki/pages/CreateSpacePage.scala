package querki.pages

import scala.concurrent.Future

import org.scalajs.dom
import scalatags.JsDom.all._
import autowire._
import rx._

import org.querki.jquery._

import querki.data.SpaceInfo
import querki.display.ButtonGadget
import querki.display.rx._
import querki.globals._
import querki.session.UserFunctions
import querki.util.InputUtils

/**
 * @author jducoeur
 */
class CreateSpacePage(params:ParamMap)(implicit e:Ecology) extends Page(e, "createSpace") with EcologyMember {
  lazy val Client = interface[querki.client.Client]
  
  val spaceName = GadgetRef[RxInput]
    .whenSet { g => 
      g.onEnter { text =>
        if (text.length() > 0) {
          createSpace()
        }
      }
    }
  
  def createSpace():Unit = {
    val newName = spaceName.get.text().trim
    Client[UserFunctions].createSpace(newName).call() foreach { space =>
      CreateSpacePage.navigateToSpace(space)
    }    
  }
    
  // Weird -- I think it's a page that we can create without going to the server!
  def pageContent = {
    val guts =
      div(
        h1(pageTitle),
        form(
          div(cls:="form-group col-md-12",
            div(cls:="input-group",
              spaceName <= new RxInput(
                  Some(InputUtils.spaceNameFilter _), "text", 
                  id:="_newSpaceName", cls:="form-control", maxlength:=254, placeholder:=msg("namePlaceholder"), tabindex:=200),
              span(cls:="input-group-btn",
                new ButtonGadget(ButtonGadget.Normal, msg("createButton"), tabindex:=210, id:="_createSpaceButton", 
                    disabled := Rx { spaceName.isEmpty || spaceName.get.text().length() == 0 })
                ({ () => createSpace()  })
              )
            )
          )
        ),
        hr(),
        h3("Or..."),
        p(
          new ButtonGadget(ButtonGadget.Normal, msg("importButton"), id:="_importButton", tabindex:=300)({ () =>
            Pages.importSpaceFactory.showPage()
          })
        )
      )
      
    Future.successful(PageContents(guts))
  }
}

object CreateSpacePage {
  def navigateToSpace(space:SpaceInfo)(implicit ecology:Ecology) = {
    val PageManager = ecology.api[querki.display.PageManager]
    
    val spaceName = space.linkName.getOrElse(space.oid.underlying)
    val url = s"/u/${space.ownerHandle}/$spaceName/#!$spaceName"
    PageManager.navigateTo(url)    
  }
}