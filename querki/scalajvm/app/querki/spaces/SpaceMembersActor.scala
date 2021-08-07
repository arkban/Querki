package querki.spaces

import scala.util.{Failure, Success}

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.pipe

import org.querki.requester._

import querki.globals._
import querki.identity.InvitationResult
import querki.spaces.messages._

private[spaces] class SpaceMembersActor(
  e: Ecology,
  val spaceId: OID,
  val spaceRouter: ActorRef
) extends Actor
     with Requester
     with Stash
     with EcologyMember {
  implicit val ecology = e

  lazy val AccessControl = interface[querki.security.AccessControl]
  lazy val Person = interface[querki.identity.Person]

  lazy val maxMembers = Config.getInt("querki.public.maxMembersPerSpace", 100)

  lazy val tracing = TracingSpace(spaceId, "SpaceMembers: ")

  def receive = {
    case CurrentState(state, _) => {
      tracing.trace(s"Got Initial CurrentState v${state.version}")
      unstashAll()
      context.become(normalReceive(state))
    }

    case _ => stash()
  }

  // Normal behavior -- at any given time, state is the current known SpaceState
  def normalReceive(state: SpaceState): Receive = {
    case CurrentState(newState, _) => {
      tracing.trace(s"CurrentState v${newState.version}")
      context.become(normalReceive(newState))
    }

    case SpaceSubsystemRequest(_, _, msg) => msg match {
        // Someone is attempting to join this Space:
        case JoinRequest(rc, personId) => {
          tracing.trace(s"JoinRequest($personId)")
          implicit val s = state
          val result: Option[Future[JoinResult]] = Person.acceptInvitation(rc, personId) {
            case ThingFound(id, state)       => Future.successful(Joined)
            case ThingError(error, stateOpt) => Future.successful(JoinFailed(error))
            case _                           => Future.successful(JoinFailed(new PublicException("Space.join.unknownError", state.displayName)))
          }
          result match {
            case Some(fut) => pipe(fut) to sender
            case None      => sender ! JoinFailed(new PublicException("Space.join.unknownError", state.displayName))
          }
        }

        case JoinByOpenInvite(rc, roleId) => {
          tracing.trace(s"JoinByOpenInvite")
          implicit val s = state
          loopback(Person.acceptOpenInvitation(rc, roleId)).map {
            // acceptOpenInvitation returns an Exception iff something went wrong. Otherwise,
            // it's strictly side-effecting.
            _ match {
              case Some(ex) => sender ! JoinFailed(ex)
              case None     => sender ! Joined
            }
          }
        }

        case ReplacePerson(guestId, actualIdentity) => {
          tracing.trace(s"ReplacePerson(${guestId}, ${actualIdentity.id})")
          Person.replacePerson(guestId, actualIdentity)(state, this).map { _ =>
            sender ! PersonReplaced
          }
        }

        case InviteRequest(rc, inviteeEmails, collabs) => {
          tracing.trace(s"InviteRequest")
          val nCurrentMembers = Person.people(state).size
          if (!rc.requesterOrAnon.isAdmin && (nCurrentMembers + inviteeEmails.size + collabs.size) > maxMembers) {
            // This is just a belt-and-suspenders check -- SecurityFunctionImpl should already have screened for this:
            sender ! InvitationResult(Seq.empty, Seq.empty)
          } else {
            val inviteRM = loopback(Person.inviteMembers(rc, inviteeEmails, collabs, state))
            inviteRM.onComplete {
              case Success(_)  =>
              case Failure(ex) => QLog.error("Got an Exception while processing InviteRequest", ex)
            }
            for {
              result <- inviteRM
            } sender ! result
          }
        }

        case RemoveMembers(rc, memberIds) => {
          tracing.trace(s"RemoveMembers($memberIds)")
          // Belt and suspenders check:
          if (AccessControl.isManager(rc.requesterOrAnon, state)) {
            val resultRM = (RequestM.successful(true) /: memberIds) { (last, memberId) =>
              last.flatMap { soFar =>
                // Accumulate the results: return true iff all of these return true.
                // Do we care about these results? Not sure, but QI.9v5kfif was what
                // happened when we got over-enthusiastic about interpreting them:
                Person.removePerson(rc, memberId)(state, this).map(_ && soFar)
              }
            }

            resultRM.foreach(_ => sender ! RemoveMembersResult)
          }
        }

        case IsSpaceMemberP(rc) => {
          tracing.trace(s"IsSpaceMemberP")
          sender ! IsSpaceMember(AccessControl.isMember(rc.requesterOrAnon, state))
        }
      }
  }
}

private[spaces] object SpaceMembersActor {

  def actorProps(
    e: Ecology,
    spaceId: OID,
    spaceRouter: ActorRef
  ) = Props(classOf[SpaceMembersActor], e, spaceId, spaceRouter)
}
