package querki.pages

import java.util.regex.Pattern

import scala.scalajs.js.timers._
import org.scalajs.dom
import scalatags.JsDom.all._
import upickle._
import autowire._
import rx._

import org.querki.facades.fileupload._
import org.querki.jquery._

import querki.comm._
import querki.data.SpaceInfo
import querki.display.rx._
import querki.imexport.ImportSpaceFunctions
import querki.globals._
// Needed in order to get evt.target.files:
import querki.photos.FileTarget._
import querki.util.InputUtils

/**
 * @author jducoeur
 */
class ImportSpacePage(params:ParamMap)(implicit e:Ecology) extends Page(e) with EcologyMember {
  
  lazy val Client = interface[querki.client.Client]
  lazy val StatusLine = interface[querki.display.StatusLine]
  
  val spaceName = GadgetRef[RxInput]
  val buttonSection = GadgetRef.of[dom.html.Div]
  val spinnerSection = GadgetRef.of[dom.html.Div]
  val progressBar = GadgetRef.of[dom.html.Div]
  val progressMsg = GadgetRef.of[dom.html.Paragraph]
  
  var progressTimer:Option[SetIntervalHandle] = None
  
  val fileInputElem = GadgetRef.of[dom.html.Input].
    whenRendered { inpGadget =>
      $(inpGadget.elem).fileupload(FileUploadOptions
        .add({ (e:JQueryEventObject, data:FileUploadData) =>
          val file = data.files(0)
          if (Pattern.matches("text/xml", file.`type`)) {
            Client[ImportSpaceFunctions].importFromXML(spaceName.get.text(), file.size).call() foreach { path =>
              data.url = controllers.ClientController.upload(path).url
              val deferred = data.submit()
              buttonSection.jq.hide()
              spinnerSection.jq.show()
              progressTimer = Some(setInterval(1000) {
                Client[ImportSpaceFunctions].getImportProgress(path).call() foreach { progress =>
                  progressMsg.jq.text(progress.msg)
                  progressBar.jq.width(s"${progress.progress}%")
                  progressBar.jq.text(s"${progress.progress}%")
                }
              })
              deferred.done { (data:String, textStatus:String, jqXHR:JQueryDeferred) => 
                clearInterval(progressTimer.get)
                progressTimer = None
                val space = read[SpaceInfo](data)
                CreateSpacePage.navigateToSpace(space)
              }
              deferred.fail { (jqXHR:JQueryXHR, textStatus:String, errorThrown:String) =>
                clearInterval(progressTimer.get)
                progressTimer = None
                // TODO: this needs better error support:
                println(s"ImportSpacePage got error $errorThrown")
              }
            }
          } else {
            StatusLine.showBriefly(s"That is a ${file.`type`}. You can only upload images.")
          }          
        })
        .multipart(false)
        .maxFileSize(5000000) // 5 MB
      )
    }
  
  def pageContent = {
    val guts =
      div(
        h1("Import a Space from a File"),
        p(spaceName <= new RxInput(Some(InputUtils.spaceNameFilter _), "text", cls:="form-control", placeholder:="Name of the new Space")),
        buttonSection <= div(
          p("To import an XML file that was exported from Querki, click here:"),
          // TODO: make this into a pretty custom button:
          fileInputElem <= input(/*cls:="_photoInputElem", */tpe:="file", accept:="text/xml",
            disabled := Rx { spaceName.isEmpty || spaceName.get.text().length() == 0 })
        ),
        spinnerSection <= div(display := "none",
          div(cls:="progress",
            progressBar <= div(cls:="progress-bar progress-bar-striped active", role:="progressbar", 
              aria.valuenow:="0", aria.valuemin:="0", aria.valuemax:="100", style:="width: 0%"
            )
          ),
          progressMsg <= p("Uploading...")
        )
      )
     
    Future.successful(PageContents("Import a Space", guts))
  }
}