package querki.client

import scala.concurrent.Future

import querki.globals._

import querki.api._
import querki.comm._

class ClientImpl(e:Ecology) extends ClientEcot(e) with Client {
  
  def implements = Set(classOf[Client])
  
  lazy val controllers = interface[querki.comm.ApiComm].controllers
  lazy val DataAccess = interface[querki.data.DataAccess]
  lazy val PageManager = interface[querki.display.PageManager]
  lazy val StatusLine = interface[querki.display.StatusLine]
  lazy val UserAccess = interface[querki.identity.UserAccess]
  
  def interceptFailures(caller: => Future[String]):Future[String] = {
    caller.transform(
      { response =>
        val wrapped = read[ResponseWrapper](response)
        UserAccess.setUser(wrapped.currentUser)
        wrapped.payload 
      },
      { ex =>
        ex match {
	      case ex @ PlayAjaxException(jqXHR, textStatus, errorThrown) => {
          if (jqXHR.status == 412) {
            // The server sent a PreconditionFailed, which is the signal that the Client
            // is out of date and the protocols may be inconsistent. So force a hard refresh.
            // IMPORTANT: I suspect this doesn't return anything; we're tearing down the world
            // and starting again.
            PageManager.fullReload()
          }
          
          try {
            // TODO: Argh! these dummy values are needed in order to hint the read system to pick up
            // on the subtypes, but this is a gigantic code smell. Is there any way around it?
            val dummy1 = upickle.Reader.macroR[EditException]
            val dummy2 = upickle.Reader.macroR[SecurityException]
            val dummy3 = upickle.Reader.macroR[AdminException]
            val dummy4 = upickle.Reader.macroR[ImportException]
            val aex = read[ApiException](jqXHR.responseText)
            throw aex
          } catch {
	          // The normal case -- the server sent an ApiException, which we will propagate up
	          // to the calling code:
	          case aex:querki.api.ApiException => throw aex
	          // The server sent a non-ApiException, which is unfortunate. Just display it:
	          case _:Throwable => {
              if (jqXHR.status >= 500)
                StatusLine.showUntilChange(s"There was an internal error (code ${jqXHR.status})! Sorry; it has been logged. If this persists, please tell us.")
              else
  	            StatusLine.showUntilChange(jqXHR.responseText)
		          throw ex	              
	          }
	        }
	      }
	      case _:Throwable => {
	        // Well, that's not good.
	        // TODO: should we have some mechanism to propagate this exception back to the server,
	        // and log it? Probably...
	        println(s"Client.interceptFailures somehow got non-PlayAjaxException $ex")
	        throw ex
	      }
        }
      }
    )
  }
  
  def makeCall(req:Request, ajax:PlayAjax):Future[String] = {
    val metadata = RequestMetadata(DataAccess.querkiVersion, PageManager.currentPageParams)
    interceptFailures(ajax.callAjax("pickledRequest" -> upickle.write(req), "pickledMetadata" -> upickle.write(metadata)))
  }
  
  override def doCall(req: Request): Future[String] = {
    try {
      if (DataAccess.space.isEmpty) {
        // We're not started, or not under the aegis of a Space, so there is no
        // owner/space pair to use. Hopefully this request understands that.
        // TODO: can we put something in the API definition to enforce this? That is,
        // so that we can say "this request requires a Space"? Probably -- figure it out...
        makeCall(req, controllers.ClientController.rawApiRequest())
      } else {
        makeCall(req, controllers.ClientController.apiRequest(
          DataAccess.userName, 
          DataAccess.spaceId.underlying))
      }
    } catch {
      // Note that we need to catch and report exceptions here; otherwise, they tend to get
      // lost inside Autowire:
      case (ex:Exception) => {
        println(s"Got exception in doCall: ${ex.getMessage()}")
        throw ex
      }
    }
  }

  def read[Result: upickle.Reader](p: String) = {
    try {
      upickle.read[Result](p)
    } catch {
      case ex:Exception => {
        println(s"Exception while trying to unpickle response $p: $ex")
        throw ex
      }
    }
  }
  def write[Result: upickle.Writer](r: Result) = upickle.write(r)
}
