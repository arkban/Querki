package querki.client

import scala.concurrent.Future

import scala.scalajs.js
import org.querki.jquery.JQueryDeferred

import upickle._
import autowire._

import querki.api.{CommonFunctions, CommonFunctionsEmpty}
import querki.comm.URL
import querki.globals._

trait TestClientRouter {
  
  def commStub:querki.test.ApiCommStub
  
  trait AutowireHandler extends autowire.Server[String, upickle.Reader, upickle.Writer] {
    def read[Result: upickle.Reader](p: String) = upickle.read[Result](p)
    def write[Result: upickle.Writer](r: Result) = upickle.write(r)
    
    def handle(request:Core.Request[String]):Future[String]
  }

  var handlers = Map.empty[Seq[String], AutowireHandler]
  
  /**
   * Tests should call this to register the handlers that they expect to require.
   */
  def registerApiHandler[T](methods:String*)(handler:AutowireHandler)(implicit tag:scala.reflect.ClassTag[T]) = {
    val packageAndTrait = tag.runtimeClass.getName().split("\\.")
    val splitLocal = packageAndTrait.flatMap(_.split("\\$")).toSeq

    methods.foreach(method => handlers += ((splitLocal :+ method) -> handler))
  }  

  def rawEntryPoint0(name:String)() = {
    lit(
      url = s"/test/$name"
    )
  }
  def entryPoint0(name:String)(userName:String, spaceId:String) = {
    lit(
      url = s"/test/$userName/$spaceId/$name"
    )
  }
  def entryPoint1(name:String)(userName:String, spaceId:String, p1:String) = {
    lit(
      url = s"/test/$userName/$spaceId/$name/$p1"
    )
  }
  def ajaxCommonEntryPoint(name:String, handler:(String => Future[String]))(p1:String) = {
    println("Calling ajaxCommonEntryPoint")
    lit(
      url = s"/test/$name/$p1",
      
      // This is returning a JQueryDeferred, essentially.
      // TODO: make this pluggable!
      ajax = { () =>
        lit(
          done = { (cb:js.Function3[String, String, JQueryDeferred, Any]) =>
            // Note that we actually call the callback, and thus fulfill the Future in PlayAjax, synchronously.
            // This is an inaccuracy that could lead to us missing some bugs, but does make the testing more
            // deterministic.
            // TODO: enhance the framework to fire the callbacks asynchronously. We'll have to make sure the
            // Javascript framework doesn't exit prematurely, though, and the test will have to wait for results.
            val fut = handler(p1)
            fut.onFailure {
              case ex:Exception => println(s"Got async error $ex"); throw ex
            }
            fut.map { result => cb(result, "", lit().asInstanceOf[JQueryDeferred]) }
          },
          fail = { (cb:js.Function3[JQueryDeferred, String, String, Any]) =>
            // We don't do anything here -- we're not failing for now.
            // TODO: we should do some fail tests!
          }
        ) 
      },
	      
	  method = "PUT",
	  `type` = "PUT",
	  absoluteURL = new URL("http://www.querki.net/dummy/")  
    )
  }
  def ajaxEntryPoint1(name:String, handler:(String => Future[String]))(userName:String, spaceId:String, p1:String) = {
    lit(
      url = s"/test/$userName/$spaceId/$name/$p1",
      
      // This is returning a JQueryDeferred, essentially.
      // TODO: make this pluggable!
      ajax = { () =>
        lit(
          done = { (cb:js.Function3[String, String, JQueryDeferred, Any]) =>
            // Note that we actually call the callback, and thus fulfill the Future in PlayAjax, synchronously.
            // This is an inaccuracy that could lead to us missing some bugs, but does make the testing more
            // deterministic.
            // TODO: enhance the framework to fire the callbacks asynchronously. We'll have to make sure the
            // Javascript framework doesn't exit prematurely, though, and the test will have to wait for results.
            val fut = handler(p1)
            fut.onFailure {
              case ex:Exception => println(s"Got async error $ex"); throw ex
            }
            fut.map { result => cb(result, "", lit().asInstanceOf[JQueryDeferred]) }
          },
          fail = { (cb:js.Function3[JQueryDeferred, String, String, Any]) =>
            // We don't do anything here -- we're not failing for now.
            // TODO: we should do some fail tests!
          }
        ) 
      },
	      
	  method = "PUT",
	  `type` = "PUT",
	  absoluteURL = new URL("http://www.querki.net/dummy/")  
    )
  }
}

trait StandardTestEntryPoints extends TestClientRouter {
  
  def setupStandardEntryPoints() = {
    def controllers = commStub.controllers
    
    // Entry points referenced in the MenuBar, so need to be present in essentially every Page:
    controllers.AdminController.showSpaceStatus = { rawEntryPoint0("showSpaceStatus") _ }
    controllers.AdminController.sendSystemMessage = { rawEntryPoint0("sendSystemMessage") _ }
    
    controllers.TOSController.showTOS = { rawEntryPoint0("showTOS") _ }
    
    controllers.ClientController.apiRequest = { ajaxEntryPoint1("apiRequest", { pickledRequest =>
      val request = read[Core.Request[String]](pickledRequest)
      handlers.get(request.path) match {
        case Some(handler) => handler.handle(request)
        case None => throw new Exception(s"Couldn't find handler for trait ${request.path}")
      }
    }) _ }
    def commonFunc():Function[String, js.Dynamic] = { println("In commonFunc") 
      ajaxCommonEntryPoint("commonApiRequest", { pickledRequest =>
        val request = read[Core.Request[String]](pickledRequest)
        handlers.get(request.path) match {
          case Some(handler) => handler.handle(request)
          case None => throw new Exception(s"Couldn't find handler for trait ${request.path}")
        }
      }) _ 
    }
    controllers.ClientController.commonApiRequest = { commonFunc _ }
    
    println(s"Initially, commonApiRequest = ${controllers.ClientController.commonApiRequest}")
    
    registerApiHandler[CommonFunctions]("getStandardThings")(new CommonFunctionsEmpty with AutowireHandler {
      def handle(request:Core.Request[String]):Future[String] = route[CommonFunctions](this)(request) 
    })
  }

}
