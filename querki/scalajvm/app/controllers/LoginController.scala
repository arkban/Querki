package controllers

import javax.inject._

import scala.concurrent.duration._
import scala.util._

import akka.pattern._
import akka.util.Timeout

import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.validation.Constraints._
import play.api.mvc._

// Provide an implicit Messages object, which is needed to pass into some of the templates.
// TODO: this is a temporary hack -- we'll need to switch to the injected version of Messages
// before too long.
import play.api.i18n.Messages.Implicits._

import upickle.default._

import models._

import querki.api.ApiException
import querki.cluster.OIDAllocator._
import querki.data.UserInfo
import querki.db.ShardKind
import querki.ecology._
import querki.email.EmailAddress
import querki.globals._
import querki.identity._
import querki.spaces.messages._
import querki.time.DateTime
import querki.values.QLRequestContext

case class PasswordChangeInfo(
  password: String,
  newPassword: String,
  newPasswordAgain: String
)

class LoginController @Inject() (val appProv: Provider[play.api.Application]) extends ApplicationBase {

  lazy val ClientApi = interface[querki.api.ClientApi]
  lazy val Email = interface[querki.email.Email]
  lazy val Encryption = interface[querki.security.Encryption]
  lazy val NotifyInvitations = interface[querki.identity.NotifyInvitations]
  lazy val Person = interface[querki.identity.Person]
  lazy val QuerkiCluster = interface[querki.cluster.QuerkiCluster]
  lazy val UserSession = interface[querki.session.Session]

  case class UserForm(
    name: String,
    password: String
  )

  val userForm = Form(
    mapping(
      "name" -> nonEmptyText,
      "password" -> nonEmptyText
    )((name, password) => UserForm(name, password))((user: UserForm) => Some((user.name, "")))
  )

  case class InviteeForm(
    emails: List[String],
    collabs: List[String]
  )

  val inviteForm = Form(
    mapping(
      "invitees" -> list(email),
      "collaborators" -> list(nonEmptyText)
    )((emails, collaborators) => InviteeForm(emails, collaborators))((invitees: InviteeForm) =>
      Some((invitees.emails, invitees.collabs))
    )
  )

  // TODO: SignupInfo and its related form are actually written according to the Play
  // examples, now that I understand what this is all supposed to look like. The other
  // input forms should be rewritten similarly as we have time.
  val signupForm = Form(
    mapping(
      "email" -> email,
      // Note that we intentionally do *not* validate the minimum length here, because Play
      // foolishly display the error with the password in plaintext on the screen:
      "password" -> nonEmptyText,
      "handle" -> (text.verifying(pattern(
        """[a-zA-Z0-9]+""".r,
        error = "A handle can only contain letters and numbers"
      ))),
      "display" -> nonEmptyText
    )(SignupInfo.apply)(SignupInfo.unapply)
  )

  val passwordChangeForm = Form(
    mapping(
      "password" -> nonEmptyText,
      "newPassword" -> (nonEmptyText.verifying(minLength(8))),
      "newPasswordAgain" -> (nonEmptyText.verifying(minLength(8)))
    )(PasswordChangeInfo.apply)(PasswordChangeInfo.unapply)
  )

  val displayNameChangeForm = Form(
    mapping("newName" -> nonEmptyText)((name) => name)((name: String) => Some(name))
  )

  val resetPasswordForm = Form(
    mapping("name" -> nonEmptyText)((handle) => handle)((handle: String) => Some(handle))
  )

  def getCollaborators(
    ownerId: String,
    spaceId: String,
    q: String
  ) = withUser(true) { implicit rc =>
    // TODO: in principle, this isn't quite right -- the Identity in getCollaborators ought to be the localIdentity
    // for this Space. I've removed that for now, so that we don't need to have the Space. At some point, this entry
    // point ought to go through Autowire instead, to the UserSpaceSession, but that requires moving away from MarcoPolo
    // on the client:
    UserSession.getCollaborators(rc.requesterOrAnon, rc.requesterOrAnon.mainIdentity, q).map { collabs =>
      // TODO: introduce better JSONification for the AJAX code:
      // TODO: refactor this with getTags and getLinks; there is a common "return Manifest" function here:
      val JSONcollabs = "[" + collabs.acs.map(identity =>
        "{\"display\":\"" + identity.name + "\", \"id\":\"" + identity.id.toThingId + "\"}"
      ).mkString(",") + "]"
      Ok(JSONcollabs)
    }
  }

  def withSpaceInfo(
    cb: (SpaceInfo, PublicIdentity) => Future[Result]
  )(implicit
    rc: PlayRequestContext
  ): Future[Result] = {
    IdentityAccess.getIdentity(rc.ownerId).flatMap { ownerIdOpt =>
      val ownerId = ownerIdOpt.get
      askSpace(rc.ownerId, rc.spaceIdOpt.get)(GetSpaceInfo(rc.requesterOrAnon, _)) {
        case info: SpaceInfo => cb(info, ownerId)
      }
    }
  }

  val inviteTimeoutParam = "inviteTimeout"
  val inviteTimeout = Config.getDuration("querki.invitations.inviteTimeout", 5 minutes).toMillis

  def withinTimeout(request: RequestHeader): Boolean = {
    request.session.get(inviteTimeoutParam)
      .map { param => new DateTime(param.toLong).isAfter(DateTime.now) }
      .getOrElse(false)
  }
  val inviteSpaceIdParam = "inviteSpaceId"
  case class InviteString(invite: String)

  val handleInviteForm = Form(
    mapping(
      "invite" -> nonEmptyText
    )(InviteString.apply)(InviteString.unapply)
  )

  /**
   * The newer invite-handling function. This is designed to be called from the Client's
   * _handleInvite page. Returns the UserInfo for the current User, which might be a newly-synthesized
   * Guest User if nobody's logged in.
   */
  def handleInvite2(
    ownerIdStr: String,
    spaceIdStr: String
  ) = withRouting(ownerIdStr, spaceIdStr) { implicit rc =>
    implicit val request = rc.request
    implicit val timeout = Timeout(10 seconds)
    val rawForm = handleInviteForm.bindFromRequest
    rawForm.fold(
      errorForm => { BadRequest("Error: badly formatted request!") },
      info => {
        val loggedIn = rc.requester.isDefined
        // TODO: ideally, we should do something more graceful if parseInvite() fails, but don't be
        // *too* nice -- odds are fairly high that it's a hack attempt.
        NotifyInvitations.parseInvite(info.invite).get match {
          // This was an invitation to a particular person, by email address. We have the email and
          // Identity ID, which has been wrapped up into a GuestUser:
          case SpecificInvitation(personId, inviteUser) => {
            val updatedRc =
              if (rc.requester.isDefined)
                rc
              else
                rc.copy(requester = Some(inviteUser))
            for {
              spaceId <- SpaceOps.getSpaceId(updatedRc.ownerId, spaceIdStr)
              // TODO: this returns Joined or JoinFailed. Ideally, if JoinFailed, we should propagate the exception to
              // to the Client as a proper error. But again, it might well be an attempted security breach.
              dummy <- SpaceOps.spaceRegion ? SpaceSubsystemRequest(
                updatedRc.requesterOrAnon,
                spaceId,
                JoinRequest(updatedRc, personId)
              )
              userOpt = updatedRc.requester
              userInfoOpt <- ClientApi.userInfo(userOpt)
            } yield {
              if (loggedIn)
                Ok(write(userInfoOpt.get))
              else
                // Set up the Session for this Guest, and record when the "invitation" period expires:
                Ok(write(userInfoOpt.get)).withSession(
                  (inviteUser.toSession :+
                    (inviteTimeoutParam -> DateTime.now.plus(inviteTimeout).getMillis.toString) :+
                    (inviteSpaceIdParam -> spaceId.toString)): _*
                )
                // We add the Email address as its own cookie, for Client use:
                  .withCookies(Cookie(
                    User.guestEmailSessionParam,
                    inviteUser.mainIdentity.email.addr,
                    httpOnly = false
                  ))
            }
          }

          // This was an Open Invitation, sent out through a Shared Link. We have the ID of the
          // Custom Role that represents the Invitation:
          case OpenInvitation(inviteId) => {
            for {
              // TODO: review this logic! This is really weird-looking -- if we already have a logged-in User, are
              // we creating a new Identity for them? Why?
              // Note that these Identities do *not* exist in the System Shard. This should, in principle, be fine.
              // In the medium term, we will probably move away from System Shard more or less completely.
              NewOID(identityId) <- QuerkiCluster.oidAllocator ? NextOID
              inviteUser = IdentityAccess.makeTrivial(identityId)
              updatedRc =
                if (rc.requester.isDefined)
                  rc
                else
                  rc.copy(requester = Some(inviteUser))
              spaceId <- SpaceOps.getSpaceId(updatedRc.ownerId, spaceIdStr)
              // TODO: this returns Joined or JoinFailed. Ideally, if JoinFailed, we should propagate the exception to
              // to the Client as a proper error.
              joinReturned <- SpaceOps.spaceRegion ? SpaceSubsystemRequest(
                updatedRc.requesterOrAnon,
                spaceId,
                JoinByOpenInvite(updatedRc, inviteId)
              )
              joinSucceeded =
                joinReturned match {
                  case Joined        => true
                  case JoinFailed(_) => false
                }
              userOpt = updatedRc.requester
              userInfoOpt <- ClientApi.userInfo(userOpt)
            } yield {
              if (!joinSucceeded)
                // Crude, but we're going to use a simple empty String as the signal of a bad invitation:
                Ok("")
              else if (loggedIn)
                Ok(write(userInfoOpt.get))
              else
                // Set up the Session for this Guest, and record when the "invitation" period expires. Note
                // that, unlike the clause above, there is no email cookie.
                Ok(write(userInfoOpt.get)).withSession(
                  (inviteUser.toSession :+
                    (inviteTimeoutParam -> DateTime.now.plus(inviteTimeout).getMillis.toString) :+
                    (inviteSpaceIdParam -> spaceId.toString)): _*
                )
            }
          }
        }
      }
    )
  }

  /**
   * DEPRECATED
   */
  def handleInvite(
    ownerId: String,
    spaceId: String
  ) = withRouting(ownerId, spaceId) { implicit rc =>
    // This cookie gets set in PersonModule.InviteLoginChecker. If it isn't set, somebody is trying to sneak
    // in through the back door:
    val emailOpt = rc.sessionCookie(querki.identity.identityEmail)
    emailOpt match {
      case Some(email) => {
        // Okay, it's a legitimate invitation. Is this a signed-in user?
        rc.requester match {
          case Some(user) => {
            // Yes. Am I already a member of this Space?
            askSpace(rc.ownerId, spaceId)(SpaceSubsystemRequest(rc.requesterOrAnon, _, IsSpaceMemberP(rc))) {
              case IsSpaceMember(isMember) => {
                if (isMember) {
                  // Yes. Okay, just go the Space, since there's nothing to do here:
                  Redirect(routes.ClientController.space(ownerId, spaceId))
                } else {
                  // Not yet. Okay, go to joining the space:
                  withSpaceInfo { (info, ownerIdentity) => Ok(views.html.joinSpace(this, rc, info, ownerIdentity)) }
                }
              }
            }
          }

          case None => {
            withSpaceInfo { (info, ownerIdentity) =>
              // TODO: Detect if the identity (as given in the sessionCookie) is already a full user. If so,
              // skip the signup process and just give them login!
              // Nope. Let them sign up for Querki. This will loop through to signup, below:
              val startForm = SignupInfo(email, "", "", "")
              Ok(views.html.handleInvite(this, rc, signupForm.fill(startForm), info))
            }
          }
        }
      }
      case None =>
        doError(indexRoute, "For now, you can only sign up for Querki through an invitation. Try again soon.")
    }
  }

  // TODO: can we factor this together with dologin in a sensible way? The trick is that we want to finish login by showing
  // joinSpace...
  // DEPRECATED
  def joinlogin(
    ownerId: String,
    spaceId: String
  ) = withRouting(ownerId, spaceId) { implicit rc =>
    implicit val request = rc.request
    userForm.bindFromRequest.fold(
      errors => doError(Call(request.method, request.path), "I didn't understand that"),
      form => {
        val userOpt = UserAccess.checkQuerkiLogin(form.name, form.password)
        userOpt match {
          case Some(user) => {
            // Yes. Am I already a member of this Space?
            askSpace(rc.ownerId, spaceId)(SpaceSubsystemRequest(rc.requesterOrAnon, _, IsSpaceMemberP(rc))) {
              case IsSpaceMember(isMember) => {
                if (isMember) {
                  // Yes. Okay, just go the Space, since there's nothing to do here:
                  Redirect(routes.ClientController.space(ownerId, spaceId)).withSession(user.toSession: _*)
                } else {
                  withSpaceInfo { (info, ownerIdentity) =>
                    Ok(views.html.joinSpace(this, rc, info, ownerIdentity)).withSession(Session(
                      request.session.data ++ user.toSession
                    ))
                  }
                }
              }
            }
          }
          case None => doError(Call(request.method, request.path), "Login failed. Please try again.")
        }
      }
    )
  }

  val minPasswordLen = 8

  // DEPRECATED
  def passwordValidationError(password: String): Option[String] = {
    // For now, we're not doing much except the most trivial check:
    if (password.length >= minPasswordLen)
      None
    else
      Some(s"Password must be at least $minPasswordLen characters long")
  }

  // DEPRECATED
  def signup(
    ownerId: String,
    spaceId: String
  ) = withRouting(ownerId, spaceId) { implicit rc =>
    implicit val request = rc.request
    val rawForm = signupForm.bindFromRequest
    rawForm.fold(
      errorForm => {
        withSpaceInfo { (info, ownerIdentity) => BadRequest(views.html.handleInvite(this, rc, errorForm, info)) }
      },
      info => {
        passwordValidationError(info.password) match {
          case Some(err) => withSpaceInfo { (info, ownerIdentity) =>
              BadRequest(views.html.handleInvite(this, rc.withError(err), rawForm, info))
            }
          case None => {
            // Make sure we have a Person in a Space in the cookies -- that is required for a legitimate request
            val personOpt = rc.sessionCookie(querki.identity.personParam)
            // This is the email address they were *originally* invited through.
            val emailOpt = rc.sessionCookie(querki.identity.identityEmail)
            // This is the identity that was created for this invitation:
            val identityIdOpt = rc.sessionCookie(querki.identity.identityParam).map(OID(_))
            personOpt match {
              case Some(personId) => {
                // This pathway is considered "confirmed" if it came from an invitation to the same email
                // address as the one being signed up for:
                val emailConfirmed = emailOpt.map(_.toLowerCase() == info.email.toLowerCase()).getOrElse(false)
                UserAccess.createUser(info, emailConfirmed, identityIdOpt, true).map { user =>
                  // Edge case: if they signed up using a different email address than the one the invitation went
                  // to, we need to send their activation email:
                  if (emailOpt.isDefined && !emailConfirmed) {
                    Person.sendValidationEmail(rc, EmailAddress(info.email), user)
                  }
                  // We're now logged in, so start a new session. But preserve the personParam for the next step:
                  // Note that we auto-join the Space through this route:
                  Redirect(routes.LoginController.joinSpace(ownerId, spaceId)).withSession(
                    user.toSession :+ (querki.identity.personParam -> personId): _*
                  )
                }.recoverWith {
                  case error => {
                    val msg = error match {
                      case err: PublicException => err.display(request, ecology)
                      case _ =>
                        QLog.error("Internal Error during signup", error); "Something went wrong; please try again"
                    }
                    withSpaceInfo { (info, ownerIdentity) =>
                      BadRequest(views.html.handleInvite(this, rc.withError(msg), rawForm, info))
                    }
                  }
                }
              }
              case _ => doError(
                  indexRoute,
                  "Error -- you seem to have somehow gotten here without a valid invitation! Please drop us a note; this shouldn't be possible."
                )
            }
          }
        }
      }
    )
  }

  /**
   * This version of signup() is aimed at the user coming directly to the website and starting signup from there.
   * It is called from the client, so doesn't deal with HTML.
   *
   * This expects the Client to pass the guts of the request as POST params.
   *
   * TODO: we're currently expecting that all validation is being done at the Client level, but we should probably
   * sanity-check it here as well.
   */
  def signupStart() = withUser(false) { implicit rc =>
    implicit val request = rc.request
    val rawForm = signupForm.bindFromRequest
    rawForm.fold(
      errorForm => { BadRequest("Error: badly formatted request!") },
      info => {

        // Iff this is already a validated Guest session, then incorporate that Guest into the
        // new User:
        val (isValidatedEmail, identityOpt: Option[Identity]) = rc.requester.map { curUser =>
          if (curUser.isActualUser)
            throw new Exception(s"Somehow trying to create a new User, but we're already logged in!")

          // Sanity-check: we only allow you to claim the Guest session for a few minutes, to guard
          // against account theft if somebody walks away from their screen.
          if (withinTimeout(request)) {
            // Okay, we're a Guest:
            val identity = curUser.mainIdentity
            (identity.email.addr.toLowerCase == info.email.toLowerCase, Some(identity))
          } else
            (false, None)
        }.getOrElse((false, None))
        // If what we have is currently a Trivial Identity, then it doesn't really exist in MySQL yet:
        val identityExists = identityOpt.map(_.kind != IdentityKind.Trivial).getOrElse(false)

        UserAccess.createUser(info, isValidatedEmail, identityOpt.map(_.id), identityExists).flatMap { user =>
          // Okay -- user is created, so send out the validation email if needed:
          val validateFut =
            if (isValidatedEmail)
              fut(())
            else
              Person.sendValidationEmail(rc, EmailAddress(info.email), user)
          validateFut.flatMap { _ =>
            // Email sent, so we're ready to move on:
            ClientApi.userInfo(Some(user)).map { userInfoOpt =>
              Ok(write(userInfoOpt.get)).withSession(user.toSession: _*)
            }
          }
        }.recover {
          case error => {
            val msg = error match {
              case ex: ApiException     => write(ex)
              case err: PublicException => err.display(request, ecology)
              case _                    => QLog.error("Internal Error during signup", error); "Something went wrong; please try again"
            }
            BadRequest(s"$msg")
          }
        }
      }
    )
  }

  // DEPRECATED
  def joinSpace(
    ownerId: String,
    spaceId: String
  ) = withRouting(ownerId, spaceId) { rc =>
    rc.sessionCookie(querki.identity.personParam).map(OID(_)).map { personId =>
      askSpace(rc.ownerId, rc.spaceIdOpt.get)(SpaceSubsystemRequest(rc.requesterOrAnon, _, JoinRequest(rc, personId))) {
        case Joined            => Redirect(routes.ClientController.space(ownerId, spaceId))
        case JoinFailed(error) => doError(indexRoute, error)(rc)
      }
    }.getOrElse(doError(indexRoute, "You don't seem to be logged in."))
  }

  def sendPasswordReset() = withUser(false) { rc =>
    Ok(views.html.sendPasswordReset(this, rc))
  }

  def resetValidationStr(
    email: String,
    expires: Long
  ) = s"${email} ${expires}"
  def isSecure = Config.getBoolean("session.secure", false)

  def doSendPasswordReset() = withUser(false) { rc =>
    implicit val request = rc.request
    val rawForm = resetPasswordForm.bindFromRequest
    // We show the same error in all cases, to avoid information leakage
    def showError = {
      doError(routes.LoginController.sendPasswordReset, "That isn't a known login handle or email address")
    }
    rawForm.fold(
      errorForm => showError,
      handle => {
        val successOpt = for {
          user <- UserAccess.getUserByHandleOrEmail(handle)
          identity <- user.loginIdentity
          email = identity.email.addr
          expires = DateTime.now.plusDays(2).getMillis()
          hash = Encryption.calcHash(resetValidationStr(email, expires))
          subject = Wikitext("Reset your Querki password")
          body = Wikitext(
            s"""We received a request to reset the password for your account, ${user.mainIdentity.handle}.
               |If you made this request, please [click here](${routes.LoginController.resetPassword(
              email,
              expires,
              hash
            ).absoluteURL(isSecure)(rc.request)}), which will take you to a page
               |where you can enter a new password for your Querki account. This link will only be valid for the next two days, so please act
               |on it soon!
               |
               |If you did not make this request, please just ignore this email, and nothing will be changed.""".stripMargin
          )
          result = Email.sendSystemEmail(identity, subject, body)
        } yield true

        successOpt match {
          case Some(true) => doInfo(indexRoute, "Password update email has been sent")
          case _          => showError
        }
      }
    )
  }

  def resetPassword(
    email: String,
    expiresMillis: Long,
    hash: String
  ) = withUser(false) { rc =>
    val initialPasswordForm = PasswordChangeInfo(hash, "", "")
    Ok(views.html.resetPassword(this, rc, email, expiresMillis, hash, passwordChangeForm.fill(initialPasswordForm)))
  }

  def doResetPassword(
    email: String,
    expiresMillis: Long,
    hash: String
  ) = withUser(false) { rc =>
    def showError(msg: String) = doError(routes.LoginController.resetPassword(email, expiresMillis, hash), msg)
    if (!Encryption.authenticate(resetValidationStr(email, expiresMillis), hash))
      showError("That reset-password link doesn't seem to have been legal -- please try again.")
    else {
      val expires = new DateTime(expiresMillis)
      if (expires.isBeforeNow())
        showError(
          "That reset-password link has expired. You must reset your password within 2 days of clicking on Forgot my Password."
        )
      else {
        implicit val request = rc.request
        val rawForm = passwordChangeForm.bindFromRequest
        rawForm.fold(
          errorForm => showError("That wasn't a legal reset-password form???"),
          info => {
            if (info.newPassword == info.newPasswordAgain) {
              UserAccess.getUserByHandleOrEmail(email) match {
                case Some(user) => {
                  val identity = user.loginIdentity.get
                  val newUser = UserAccess.changePassword(user, identity, info.newPassword)
                  Redirect(indexRoute).flashing("info" -> "Password changed")
                }
                case None =>
                  showError(s"$email isn't a known Querki user! Please try the Forgot my Password link again.")
              }
            } else {
              doError(routes.LoginController.resetPassword(email, expiresMillis, hash), "The passwords didn't match")
            }
          }
        )
      }
    }
  }

  // login now simply happens through the index page
  def login = Redirect(indexRoute)

  def dologin = Action.async { implicit request =>
    val rc = PlayRequestContextFull(request, None, UnknownOID)
    userForm.bindFromRequest.fold(
      errors => doError(indexRoute, "I didn't understand that"),
      form => {
        val userOpt = UserAccess.checkQuerkiLogin(form.name, form.password)
        userOpt match {
          case Some(user) => {
            val redirectOpt = rc.sessionCookie(rc.returnToParam)
            redirectOpt match {
              case Some(redirect) => Redirect(redirect).withSession(user.toSession: _*)
              case None           => Redirect(indexRoute).withSession(user.toSession: _*)
            }
          }
          case None => doError(indexRoute, "Login failed. Please try again.")
        }
      }
    )
  }

  /**
   * A simplified version of login, intended for client / API use.
   */
  def clientlogin = Action.async { implicit request =>
    val rc = PlayRequestContextFull(request, None, UnknownOID)
    userForm.bindFromRequest.fold(
      errors => Ok("failed"),
      form => {
        val guestUserOpt = IdentityAccess.guestFromSession(request)

        val userOpt = UserAccess.checkQuerkiLogin(form.name, form.password)
        userOpt match {
          case Some(user) => {
            def writeUserInfo() = {
              ClientApi.userInfo(Some(user)).map { userInfoOpt =>
                Ok(write(userInfoOpt)).withSession(user.toSession: _*)
              }
            }

            // SUBTLE CASE: iff we were logged in as a Guest (thus, an invitee to a Space),
            // and we've logged in with a *different* Identity within the inviteTimeout (to mitigate
            // against account takeovers if somebody leaves a Guest logged in), then replace the invited
            // Identity. This is necessary for the edge cases where I have been invited
            // via Email A, but I actually use Querki with Email B.
            (guestUserOpt, request.session.get(inviteSpaceIdParam)) match {
              case (Some(guestUser), Some(spaceIdStr))
                  if (withinTimeout(request) && !user.hasIdentity(guestUser.mainIdentity.id)) => {
                val spaceId = OID(spaceIdStr)
                val realIdentity = guestUser.mainIdentity
                val msg = SpaceSubsystemRequest(
                  rc.requesterOrAnon,
                  spaceId,
                  ReplacePerson(guestUser.mainIdentity.id, user.mainIdentity)
                )
                implicit val timeout = Timeout(10 seconds)
                (SpaceOps.spaceRegion ? msg).flatMap { _ =>
                  writeUserInfo()
                }
              }

              case _ => {
                writeUserInfo()
              }
            }
          }
          case None => Ok("failed")
        }
      }
    )
  }

  def logout = Action {
    Redirect(indexRoute).withNewSession
  }
}
