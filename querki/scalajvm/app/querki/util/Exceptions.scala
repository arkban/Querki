package querki.util

import scala.util._

// TODO: Feh -- this is all hackery to get at Messages in the Play 2.4 world. Probably need to do this
// a better way, more compatible with Play's new dependency-injected approach. For the moment, we're
// using a bunch of global implicits to get there.
import play.api.i18n.Messages
import play.api.i18n.Lang.defaultLang
import Messages.Implicits._

import play.api.mvc.RequestHeader

import controllers.PlayRequestContext
import querki.ecology.PlayEcology
import querki.globals._
import querki.values.RequestContext

/**
 * Represents an error that is intended to be displayed to end users. Intentionally forces you to
 * internationalize the message properly. All exceptions that are to be shown to users should use this!
 */
case class PublicException(
  msgName: String,
  params: Any*
) extends Exception {

  private def doDisplay(implicit e: Ecology): String =
    PlayEcology.maybeApplication match {
      case Some(app) => {
        implicit val a = app
        Messages(msgName, params: _*)
      }
      // There's no Application, which implies that we're probably running under unit tests:
      case _ => s"$msgName"
    }

  def display(
    implicit
    req: RequestHeader,
    e: Ecology
  ): String = doDisplay

  def display(rc: Option[RequestContext])(implicit e: Ecology): String = {
    rc match {
      case Some(prc: PlayRequestContext) => display(prc.request, e)
      case _                             => doDisplay
    }
  }
  override def getMessage = s"$msgName(${params.mkString(", ")})"
}

object PublicException {
  def raw(msg: String) = PublicException("General.public", msg)
}
object UnexpectedPublicException extends PublicException("General")

/**
 * Represents an internal error that should *not* be shown to end users.
 */
case class InternalException(message: String) extends Exception(message)

/**
 * This captures the common pattern we want to encourage, using Try and PublicException. Note
 * that while this can return a value, it is particularly useful in Actors, where the real action
 * is side-effectful (usually sending messages) and it returns Unit.
 *
 * Basically, this takes three blocks. The first does the actual calculation. The second does something
 * with the result if it succeeds. The third does something with the resulting PublicException if it
 * fails.
 *
 * TBD: this is almost *too* concise: it is arguably hard to distinguish the success and failure cases,
 * especially since you don't actually have to declare any of the types. We may actually want some
 * syntactic glue to clarify.
 */
object Tryer {

  def apply[T, R](func: => T)(succ: T => R)(fail: PublicException => R): R = {
    val t = Try { func }
    t match {
      case Failure(ex: PublicException) => fail(ex)
      case Failure(error)               => { QLog.error("Internal error", error); fail(UnexpectedPublicException) }
      case Success(v)                   => succ(v)
    }
  }
}

// Trying to get a version of this that works with an ordinary Try. Still a project in-progress.
//object RichTry {
//  class TryHandler[T, R](t:Try[T], succ:Option[T => R] = None, fail:Option[PublicException => R] = None) {
//    def onSucc(f:T => R) = new TryHandler(t, Some(f), fail)
//    def onFail(f:PublicException => R) = new TryHandler(t, succ, Some(f))
//
//    def result:R = {
//      t match {
//        case Failure(ex:PublicException) if (fail.isDefined) => fail.get(ex)
//        case Failure(error) if (fail.isDefined) => { QLog.error("Internal error", error); fail.get(UnexpectedPublicException) }
//        case Success(v) if (succ.isDefined) => succ.get(v)
//        case _ => throw new Exception("Incompletely defined TryTrans")
//      }
//    }
//  }
//
//  implicit class TryWrapper[T, R](t:Try[T]) {
//    def onSucc(f:T => R) = new TryHandler[T, R](t, Some(f), None)
//    def onFail(f:PublicException => R) = new TryHandler[T, R](t, None, Some(f))
//  }
//}

class TryTrans[T, R](
  func: => T,
  succ: Option[T => R] = None,
  fail: Option[PublicException => R] = None
) {
  type TRet = R
  def onSucc(f: T => R) = new TryTrans(func, Some(f), fail)
  def onFail(f: PublicException => R) = new TryTrans(func, succ, Some(f))

  def result: R = {
    // NOTE: I tried to do this with scala.util.Try, and found it a miserable failure in the case of
    // functions that return Unit -- I couldn't force evaluation to happen at the right time. So we're
    // doing it manually instead:
    try {
      succ.get(func)
    } catch {
      case ex: PublicException => fail.get(ex)
      case ex: Exception => {
        QLog.error("Internal error", ex)
        fail.get(UnexpectedPublicException)
      }
    }
  }
}

object TryTrans {
  def apply[T, R](func: => T) = new TryTrans[T, R](func)
}
