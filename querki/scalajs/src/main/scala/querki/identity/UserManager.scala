package querki.identity

import scala.util.Success

import org.scalajs.dom.html

import upickle.default._
import rx._
import scalatags.JsDom.all.{name => nm, _}
import autowire._

import org.querki.jquery._
import org.querki.gadgets._

import querki.comm._
import querki.globals._
import querki.data.UserInfo
import querki.display._
import querki.display.rx._
import QuerkiEmptyable._
import querki.pages.Page
import querki.session.UserFunctions

/**
 * This is a slightly nasty little hack, to try and preserve as much commonality
 * as possible between the Login Dialog and the (optional) Login part of the
 * SignInPage, while allowing the look-and-feel details to differ.
 */
class LoginLogic(
)(implicit
  val ecology: Ecology,
  ctx: Ctx.Owner
) extends EcologyMember {
  lazy val controllers = interface[querki.comm.ApiComm].controllers
  lazy val UserAccess = interface[UserAccess]

  val loginPromise = Promise[Unit]

  def finishLogin() = {
    loginPromise.complete(Success(()))
  }

  // The order of the logic here is a tad convoluted, because it's a bit recursive: we want to
  // allow Enter, in the passwordInput, which is inside the Dialog, to *close* that Dialog.
  // So everything needs to be pulled apart a bit.
  def doLogin(): Unit = {
    // We call this one as a raw AJAX call, instead of going through client, since it is a weird case:
    val fut: Future[String] =
      controllers.LoginController.clientlogin().callAjax(
        "name" -> handleInput.get.text.now,
        "password" -> passwordInput.get.text.now
      )
    fut.foreach { result =>
      if (result == "failed") {
        $(badLoginMsg.elem).show()
      } else {
        val userInfoOpt = read[Option[UserInfo]](result)
        UserAccess.setUser(userInfoOpt)
        finishLogin()
      }
    }
  }

  lazy val handleInput = GadgetRef[RxInput]

  lazy val passwordInput = GadgetRef[RxInput]
    .whenSet { g =>
      g.onEnter { text =>
        if (text.length() > 0) {
          doLogin()
        }
      }
    }
  lazy val badLoginMsg = GadgetRef.of[html.Div]
}

class UserManagerEcot(e: Ecology) extends ClientEcot(e) with UserAccess {

  def implements = Set(classOf[UserAccess])

  lazy val controllers = interface[querki.comm.ApiComm].controllers
  lazy val Client = interface[querki.client.Client]
  lazy val PageManager = interface[querki.display.PageManager]
  lazy val SkillLevel = interface[querki.identity.skilllevel.SkillLevel]
  lazy val StatusLine = interface[querki.display.StatusLine]

  var _user: Option[UserInfo] = None
  def user: Option[UserInfo] = _user

  def setUser(user: Option[UserInfo]) = {
    _user = user
    SkillLevel.updateSkillLevel()
  }

  def name = _user.map(_.mainIdentity.name).getOrElse("Not logged in")

  def isActualUser = user.map(_.actualUser).getOrElse(false)

  def login()(implicit ctx: Ctx.Owner): Future[Page] = {
    for {
      _ <- loginCore()
      page <- PageManager.reload()
    } yield page
  }

  def loginCore()(implicit ctx: Ctx.Owner): Future[Unit] = {
    val logic = new LoginLogic()

    def showSignup(): Unit = {
      loginDialog.done()
      SignUpPage.run(false).foreach { userInfo =>
        logic.finishLogin()
      }
    }

    def dismiss(): Unit = loginDialog.done()

    lazy val loginDialog = new Dialog(
      "Log in to Querki",
      div(
        p("""If you are already a member of Querki, enter your login info here:"""),
        logic.handleInput <= new RxText(
          placeholder := "Handle or email address",
          width := "80%",
          nm := "name",
          id := "name",
          tabindex := 1
        ),
        logic.passwordInput <= new RxInput(
          "password",
          placeholder := "Password",
          width := "80%",
          nm := "password",
          id := "password",
          tabindex := 2
        ),
        logic.badLoginMsg <=
          div(
            cls := "alert alert-danger alert-dismissable",
            style := "display: none",
            button(
              tpe := "button",
              cls := "close",
              data("dismiss") := "alert",
              aria.label := "Close",
              span(aria.hidden := "true", raw("&times;")),
              tabindex := 7
            ),
            b("That isn't a correct email and password."),
            " Please try again. "
          ),
        div(
          cls := "row",
          div(
            cls := "col-sm-3",
            new ButtonGadget(
              ButtonGadget.Primary,
              "Log in",
              disabled := Rx {
                val handleEmpty = logic.handleInput.rxEmpty
                val passwordEmpty = logic.passwordInput.rxEmpty
                handleEmpty() || passwordEmpty()
              },
              tabindex := 3
            )({ () => logic.doLogin() })
          ),
          div(
            cls := "col-sm-3 col-sm-offset-6",
            new ButtonGadget(
              ButtonGadget.Normal,
              "Sign up",
              id := "_signupButton",
              tabindex := 4
            )({ () => showSignup() })
          )
        ),
        p(a(
          href := controllers.LoginController.sendPasswordReset().url,
          "Click here if you have forgotten your password.",
          tabindex := 5
        )),
        if (user.isDefined && !user.get.actualUser)
          div(
            p("or:"),
            new ButtonGadget(
              ButtonGadget.Normal,
              "Continue as a Guest",
              tabindex := 6
            )({ () => dismiss() })
          )
      )
    )
    loginDialog.show()

    val doneFuture = logic.loginPromise.future
    doneFuture.onComplete {
      case _ => loginDialog.done()
    }
    doneFuture
  }

  def resendActivationButton =
    new ButtonGadget(ButtonGadget.Normal, "Resend my activation email")({ () =>
      Client[UserFunctions].resendActivationEmail().call().foreach { _ =>
        StatusLine.showBriefly("Activation email sent!")
      }
    })
}
