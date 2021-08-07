package querki.spaces

import akka.actor._
import akka.persistence._
import akka.persistence.cassandra.query.scaladsl._
import akka.persistence.query._
import akka.stream.ActorMaterializer

import org.querki.requester._

import models._
import Kind.Kind
import querki.admin.SpaceTimingActor.MonitorMsg
import querki.cluster.OIDAllocator._
import querki.conversations.ConversationTransitionActor
import querki.globals._
import querki.identity.{Identity, IdentityPersistence, PublicIdentity, User}
import querki.identity.IdentityPersistence.SystemUserRef
import querki.persistence._
import querki.publication.{AddPublicationEvents, CurrentPublicationState, PublishedAck, ThingPublished}
import querki.spaces.messages._
import querki.time.DateTime
import querki.values.{QValue, SpaceVersion}

import PersistMessages._
import SpaceMessagePersistence.SpaceEvent

/**
 * This is the master controller for a single Space. It is a PersistentActor -- all
 * changes made here are persisted to Cassandra before they take effect.
 *
 * This replaces the old Space.scala, which did the same thing using the MySQL-based
 * SpacePersister.
 *
 * The bulk of the real logic here can be found in the SpaceCore trait, which has been
 * separated out for testability. This class consists mainly of filling in the abstract
 * methods from SpaceCore.
 *
 * Note that SpaceCore takes an "RM" interaction Monad; that is RequestM here, but is
 * a simple Identity Monad for synchronous testing.
 *
 * TODO: the current workflow doesn't have anywhere for the old "Evolve" mechanism to fit.
 * Where should that go?
 */
class PersistentSpaceActor(
  e: Ecology,
  val id: OID,
  stateRouter: ActorRef,
  persistenceFactory: SpacePersistenceFactory,
  timeSpaceOps: Boolean
) extends SpaceCore[RequestM](RealRTCAble)(e)
     with Requester
     with PersistentQuerkiActor
     with IdentityPersistence {
  lazy val QuerkiCluster = interface[querki.cluster.QuerkiCluster]

  /**
   * Iff set to true, this will produce *prodigious* spewage. Use with caution!
   *
   * Deprecated: use the less-horrifying TracingSpace instead.
   */
  lazy val monitorSpaces = Config.getBoolean("querki.test.traceSpaces", false)

  /**
   * This is the Actor that manages all MySQL operations -- in the medium term, access to the System
   * database.
   */
  lazy val persister = persistenceFactory.getSpacePersister(id)

  /**
   * We override SpaceCore's receiveCommand so that we can hit the plugins.
   */
  override def receiveCommand = super.receiveCommand.orElse(pluginReceive)

  ///////////////////////////////////////////
  //
  // Concrete definitions of SpaceCore abstract methods
  //

  /**
   * This is where the SpaceChangeManager slots into the real process, allowing other Ecots a chance to chime
   * in on the change before it happens.
   */
  def offerChanges(
    who: User,
    modelId: Option[OID],
    thingOpt: Option[Thing],
    kind: Kind,
    propsIn: PropMap,
    changed: Seq[OID]
  )(
    state: SpaceState
  ): RequestM[ThingChangeRequest] = {
    val initTcr = ThingChangeRequest(who, this, state, stateRouter, modelId, thingOpt, kind, propsIn, changed)
    SpaceChangeManager.thingChanges(RequestM.successful(initTcr))
  }

  /**
   * This was originally from SpacePersister -- it fetches a new OID to assign to a new Thing.
   */
  def allocThingId(): RequestM[OID] = {
    QuerkiCluster.oidAllocator.request(NextOID).map {
      case NewOID(thingId) => thingId
    }
  }

  def allocThingIds(nIds: Int): RequestM[Seq[OID]] = {
    QuerkiCluster.oidAllocator.request(GiveOIDBlock(nIds)).map {
      case NewOIDs(ids) => ids
    }
  }

  /**
   * Tells any outside systems about the updated state.
   */
  def notifyUpdateState(events: Option[List[SpaceEvent]]): Unit = {
    stateRouter ! CurrentState(currentState, events)
  }

  /**
   * Sends a message to the MySQL side, telling it that this Space's name has changed.
   *
   * TBD: this is currently fire-and-forget. Is that reasonable? Possibly we should wait for
   * this is resolve, since it's a pretty major change. But what do we do if it fails?
   *
   * This code is currently in SpacePersister; we'll need to send a new message there.
   */
  def changeSpaceName(
    newName: String,
    newDisplay: String
  ): Unit = {
    persister ! SpaceChange(newName, newDisplay)
  }

  /**
   * This is called when a Space is booted up and has *no* messages in its history. In that case,
   * we should check to see if it exists in the old-style form in MySQL.
   */
  def recoverOldSpace(): RequestM[Option[SpaceState]] = {
    for {
      // Need to fetch the Owner, so we can tell the App Loader about them:
      SpaceOwner(owner) <- persister ? GetOwner
      // Load the apps before we load this Space itself:
      // TODO: there aren't any real Apps yet, and this code path is probably just plain wrong,
      // so let's kill it for now:
//      apps <- Future.sequence(SpaceChangeManager.appLoader.collect(AppLoadInfo(owner, id, this))).map(_.flatten)
      loadRes <- persister ? Load(Seq.empty)
      sOpt = loadRes match {
        case Loaded(s) => Some(s)
        // This Space is new, and doesn't exist in the older world:
        case NoOldSpace => None
        case other      => throw new Exception(s"Got a bad response in PersistentSpaceActor.recoverOldSpace: $other!")
      }
      // TODO: for the moment, if we have upgraded an old Space, we need to check whether it needs to get
      // the Instance Permissions objects created on it. This should eventually be able to go away -- it is
      // only needed for relatively old Spaces.
      adjustedState: Option[SpaceState] <- sOpt match {
        case Some(s) => checkInstancePermissions(s).map(Some(_))
        case _       => RequestM.successful(None)
      }
      // Now transition the Conversations to the new world, if there are any:
      _ <- adjustedState match {
        case Some(s) => {
          val transitionActor =
            context.actorOf(ConversationTransitionActor.actorProps(ecology, s, stateRouter, persistenceFactory))
          transitionActor.request(ConversationTransitionActor.RunTransition).map { resp =>
            context.stop(transitionActor)
          }
        }
        case None => RequestM.successful(None)
      }
    } yield adjustedState
  }

  /**
   * Based on the owner's OID, go get the actual Identity.
   */
  def fetchOwnerIdentity(ownerIdIn: OID): RequestM[PublicIdentity] = {
    // Belt and suspenders protection -- we've hit cases where the ownerIdIn simply fails. In that case,
    // fall back to the MySQL layer, but complain about it.
    def getOwnerId(): RequestM[OID] = {
      if (ownerIdIn == UnknownOID) {
        QLog.error(s"Space $id got UnknownOID as its owner in fetchOwnerIdentity(). Falling back to MySQL...")
        persister.request(GetOwner).map {
          case SpaceOwner(id) => id
        }
      } else
        RequestM.successful(ownerIdIn)
    }

    getOwnerId().flatMap { ownerId =>
      IdentityAccess.getIdentity(ownerId).flatMap { idOpt: Option[PublicIdentity] =>
        idOpt match {
          case Some(identity) => Future.successful(identity)
          case None           => Future.failed(new Exception(s"Couldn't find owner Identity ${ownerId} for Space $id!"))
        }
      }
    }
  }

  def monitor(msg: => String): Unit = {
    if (timeSpaceOps) {
      stateRouter ! MonitorMsg(msg, DateTime.now)
    }
    if (monitorSpaces) {
      QLog.spew(s"SPACE MONITOR ($id): $msg")
    }
  }

  def sendPublicationChanges(events: List[SpaceEvent with UseKryo]): RequestM[CurrentPublicationState] = {
    val msg = AddPublicationEvents(events)
    stateRouter.requestFor[CurrentPublicationState](msg)
  }

  def notifyPublished(
    req: User,
    thingId: OID
  )(implicit
    state: SpaceState
  ): RequestM[PublishedAck] = {
    stateRouter.requestFor[PublishedAck](ThingPublished(req, thingId))
  }

  ///////////////////////////////////////////
  //
  // App Loading
  //

  lazy val readJournal =
    PersistenceQuery(context.system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  /**
   * Given the OID and version of an App, return that State.
   *
   * Note that this is mutually recursive with loadAppsFor() -- the returned State will have
   * its Apps in place.
   */
  def loadAppVersion(
    appId: OID,
    version: SpaceVersion,
    appsSoFar: Map[OID, SpaceState]
  ): RequestM[SpaceState] = {
    val appPersistenceId = toPersistenceId(appId)
    val source = readJournal.currentEventsByPersistenceId(appPersistenceId, 0, version.v)
    implicit val mat = ActorMaterializer()
    loopback {
      source.runFold(emptySpace.copy(s = appId)) {
        case ((curState, EventEnvelope(offset, appPersistenceId, sequenceNr, evt: SpaceEvent))) =>
          evolveState(Some(curState))(evt)
      }
    }.flatMap { loadedState =>
      mat.shutdown()
      // Now that we've loaded this App, recurse in and load *its* Apps:
      loadAppsFor(loadedState, appsSoFar)
    }
  }

  /**
   * This is fairly extreme: it is used when SpaceCore believes that it is in a bad state.
   */
  def selfDestruct(): Unit = {
    stateRouter ! querki.util.Shutdown
  }

  ///////////////////////////////////////////

  /**
   * Make sure that the specified Thing (the Space or one of its Models) has Instance Permissions if it either had some
   * in the old world, or if forceCreate is specified.
   */
  def checkInstancePermissionsOn(
    t: Thing,
    instancePermissions: Iterable[Property[OID, _]],
    forceCreate: Boolean
  )(implicit
    state: SpaceState
  ): RequestM[SpaceState] = {
    def addPerm(perm: Property[OID, _]): Option[(OID, QValue)] = {
      // If this Permissions had a separate "children" version, read from that instead. (This
      // only applies to Can Edit.)
      val oldPerm = perm.getPropOpt(AccessControl.ChildPermissionsProp) match {
        case Some(pv) => state.prop(pv.first).get
        case _        => perm
      }

      t.getPropOpt(oldPerm).map { pv =>
        (perm.id -> pv.v)
      }
    }

    // Gather up the Instance Permissions on this Thing, if any:
    val props = (emptyProps /: instancePermissions) { (map, perm) =>
      map ++ addPerm(perm)
    }

    if (!props.isEmpty || forceCreate)
      // Okay, we actually need to create the Instance Permissions Thing:
      for {
        permThingId <- allocThingId()
        stateWithPermThing = createPure(
          SystemUserRef,
          Kind.Thing,
          permThingId,
          AccessControl.InstancePermissionsModel.id,
          props,
          DateTime.now
        )(state)
        propsWithPerms = t.props + AccessControl.InstancePermissionsProp(permThingId)
        spaceWithAdjustedThing =
          modifyPure(t.id, t, Some(t.model), propsWithPerms, true, DateTime.now)(stateWithPermThing)
      } yield spaceWithAdjustedThing
    else
      // There weren't any Instance Permissions, and we aren't forcing the issue, so just let it be:
      RequestM.successful(state)
  }

  /**
   * Make sure that this Space has an Instance Permissions object. If it is an old Space, and already has some instance permissions
   * set on the Space itself, transition those.
   *
   * Note that this intentionally shadows the wider "state" -- this function is now pure-functional, albeit async. It takes
   * the current SpaceState, and returns an adjusted one.
   *
   * IMPORTANT: this is only called at Upgrade time! This doesn't go through the usual song and dance for recording new
   * objects, so should only be called when upgrading an old-style MySQL Space to a new-style Cassandra one!
   */
  def checkInstancePermissions(state: SpaceState): RequestM[SpaceState] = {
    state.getPropOpt(AccessControl.InstancePermissionsProp)(state) match {
      case Some(pv) =>
        RequestM.successful(state) // Nothing to do here -- the property exists, which is what we care about
      case _ => {
        implicit val s = state

        val instancePermissions =
          AccessControl.allPermissions(s).filter(_.ifSet(AccessControl.IsInstancePermissionProp))
        val models = state.models

        // We force the creation of Instance Permissions on the Space, and then do it for any Models
        // that need it. Note that, since we're flatMapping over RequestM, this will be synchronous and
        // in-order. But checkInstancePermissionsOn() is smart enough to only do a loopback if it needs
        // to actually *do* something:
        (checkInstancePermissionsOn(state, instancePermissions, true)(state) /: models) { (reqm, model) =>
          reqm.flatMap(curState => checkInstancePermissionsOn(model, instancePermissions, false)(curState))
        }
      }
    }
  }
}

object PersistentSpaceActor {

  def actorProps(
    e: Ecology,
    persistenceFactory: SpacePersistenceFactory,
    stateRouter: ActorRef,
    id: OID,
    timeSpaceOps: Boolean
  ) =
    Props(classOf[PersistentSpaceActor], e, id, stateRouter, persistenceFactory, timeSpaceOps)
}
