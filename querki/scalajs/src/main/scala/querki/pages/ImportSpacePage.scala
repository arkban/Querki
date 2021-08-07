package querki.pages

import java.util.regex.Pattern

import scala.util.{Failure, Success}

import scala.scalajs.js.timers._
import org.scalajs.dom
import dom.raw.File
import scalatags.JsDom.all._
import upickle._
import autowire._
import rx._

import org.querki.facades.fileupload._
import org.querki.jquery._
import org.querki.gadgets._

import querki.api.SpaceExistsException
import querki.comm._
import querki.data.SpaceInfo
import org.querki.gadgets.core.GadgetLookup
import querki.display.rx._
import querki.imexport.ImportSpaceFunctions
import querki.globals._
// Needed in order to get evt.target.files:
import querki.photos.FileTarget._
import querki.util.InputUtils

/**
 * @author jducoeur
 */
class ImportSpacePage(params: ParamMap)(implicit val ecology: Ecology) extends Page() {

  lazy val Client = interface[querki.client.Client]
  lazy val StatusLine = interface[querki.display.StatusLine]

  val spaceName = GadgetRef[RxInput]
  val buttonSection = GadgetRef.of[dom.html.Div]
  val spinnerSection = GadgetRef.of[dom.html.Div]
  val progressBar = GadgetRef.of[dom.html.Div]
  val progressMsg = GadgetRef.of[dom.html.Paragraph]

  var progressTimer: Option[SetIntervalHandle] = None

  val xmlInputElem =
    createInputElem(
      { file => Pattern.matches("text/xml", file.`type`) },
      { file => Client[ImportSpaceFunctions].importFromXML(spaceName.get.text.now.trim, file.size.toInt).call() }
    )

  val sqlInputElem =
    createInputElem(
      { file => true },
      { file => Client[ImportSpaceFunctions].importFromMySQL(spaceName.get.text.now.trim, file.size.toInt).call() }
    )

  def startProgressTimer(path: String) = {
    def finished() = {
      // We're done -- fire-and-forget call to the server to ack the completion:
      Client[ImportSpaceFunctions].acknowledgeComplete(path).call()
      clearInterval(progressTimer.get)
      progressTimer = None
    }

    // Once a second, ping the uploader and see how it's coming:
    progressTimer = Some(setInterval(1000) {
      Client[ImportSpaceFunctions].getImportProgress(path).call().foreach { progress =>
        progress.spaceInfo match {
          case Some(space) => {
            // Success -- we have a new Space
            finished()
            CreateSpacePage.navigateToSpace(space)
          }

          case None if (progress.failed) => {
            // Failure!
            finished()
            $(progressMsg.elem).text(s"Error -- ${progress.msg}")
          }

          case _ => {
            // Normal progress:
            $(progressMsg.elem).text(progress.msg)
            $(progressBar.elem).width(s"${progress.progress}%")
            $(progressBar.elem).text(s"${progress.progress}%")
          }
        }
      }
    })
  }

  def createInputElem(
    testFile: File => Boolean,
    fBeginUpload: File => Future[String]
  ) = GadgetRef.of[dom.html.Input].whenRendered { inpGadget =>
    $(inpGadget.elem).fileupload(FileUploadOptions
      .add({ (e: JQueryEventObject, data: FileUploadData) =>
        val file = data.files(0)
        if (testFile(file)) {
          fBeginUpload(file).onComplete {
            case Success(path) => {
              data.url = controllers.ClientController.upload(path).url
              val deferred = data.submit()
              $(buttonSection.elem).hide()
              $(spinnerSection.elem).show()
              startProgressTimer(path)
              // We no longer care about this; we're polling the state instead:
              // deferred.done { (data:String, textStatus:String, jqXHR:JQueryDeferred) => }
              deferred.fail { (jqXHR: JQueryXHR, textStatus: String, errorThrown: String) =>
                println(s"ImportSpacePage got error $errorThrown")
                StatusLine.showUntilChange(s"Error during uploading")
              }
            }
            case Failure(SpaceExistsException(name)) => {
              StatusLine.showBriefly(s"You already have a Space named $name")
            }
            case Failure(_) => StatusLine.showUntilChange(s"Error during uploading")
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
        p(spaceName <= new RxInput(
          Some(InputUtils.spaceNameFilter _),
          "text",
          cls := "form-control",
          placeholder := "Name of the new Space"
        )),
        buttonSection <= div(
          p("Choose the file to import. This may be compressed using gzip (a .gz file), or it can be uncompressed."),
          p(b("To import an XML file that was exported from Querki"), ", click here:"),
          // TODO: make this into a pretty custom button:
          xmlInputElem <= input(
            /*cls:="_photoInputElem", */ tpe := "file",
            accept := "text/xml",
            disabled := Rx { spaceName.isEmpty || spaceName.get.text().length() == 0 }
          ),
          br(),
          p(b("To import a MySQL dump file"), ", click here:"),
          // TODO: make this into a pretty custom button:
          sqlInputElem <= input(
            /*cls:="_photoInputElem", */ tpe := "file",
            disabled := Rx { spaceName.isEmpty || spaceName.get.text().length() == 0 }
          )
        ),
        spinnerSection <= div(
          display := "none",
          div(
            cls := "progress",
            progressBar <= div(
              cls := "progress-bar progress-bar-striped active",
              role := "progressbar",
              aria.valuenow := "0",
              aria.valuemin := "0",
              aria.valuemax := "100",
              style := "width: 0%"
            )
          ),
          progressMsg <= p("Uploading...")
        )
      )

    Future.successful(PageContents("Import a Space", guts))
  }
}
