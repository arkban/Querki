package models

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import play.api._
import play.api.Configuration
import play.api.Play
import play.api.Play.current
import play.api.libs.concurrent._
import play.Configuration

// Database imports:
import anorm._
import play.api.db._
import play.api.Play.current

/**
 * A Space is the Querki equivalent of a database -- a collection of related Things,
 * Properties and Types.
 * 
 * Note that, just like everything else, a Space is a special sort of Thing. It can
 * have Properties (including user-defined ones), and can potentially inherit from a
 * Model.
 * 
 * A SpaceState is a Space at a specific point in time. Operations are usually performed
 * on a SpaceState, to keep them consistent. Changes are sent to the Space, which generates
 * a new SpaceState from them.
 * 
 * TODO: implement Space inheritance -- that is, Apps.
 */
case class SpaceState(
    s:OID, 
    m:ThingPtr,
    owner:OID,
    name:String,
    types:Map[OID, PType],
    spaceProps:Map[OID, Property],
    things:Map[OID, ThingState]) 
  extends Thing(s, s, m, Kind.Space) 
{
  def resolve[T <: ThingPtr](ptr:ThingPtr)(lookup: OID => T):T = {
    ptr match {
      case id:OID => lookup(id)
      // TODO: Ick -- this is evil, and suggests that the ThingPtr model is fundamentally
      // flawed still. The problem is, any given ThingPtr potentially covers a host of
      // subtypes; we only know from context that we're expecting a specific one.
      case _ => ptr.asInstanceOf[T]
    }
  }
  def typ(ptr:ThingPtr) = resolve(ptr) (types(_))
  def prop(ptr:ThingPtr) = resolve(ptr) (spaceProps(_))
  def thing(ptr:ThingPtr) = resolve(ptr) (things(_))
  
  def anything(oid:OID) = {
    // TODO: this should do something more sensible if the OID isn't found at all:
    things.getOrElse(oid, spaceProps.getOrElse(oid, types.getOrElse(oid, this)))
  }
}

sealed trait SpaceMessage

/**
 * The Actor that encapsulates a Space.
 * 
 * As a hard and fast rule, application code in Querki never alters a Space directly.
 * Instead, all changes are described as messages to the Space's Actor; that is
 * responsible for making the actual changes. This way, the database layer is firmly
 * encapsulated, and race conditions are prevented.
 * 
 * Similarly, you fetch the Space's current State from the Space Actor. The State is
 * an immutable object completely describing the Space at a single moment; you are
 * allowed to process that in any way, just not change it.
 * 
 * An Actor's name is based on its OID, as is its Thing Table in the DB. Use Space.sid()
 * to get the name. Note that this has *nothing* to do with the Space's Display Name, which
 * is user-defined. (And unique only to that user.)
 */
class Space extends Actor {
  override def preStart() = {
    // TODO: load the Space's current state
  } 
  
  def receive = {
    case req:CreateSpace => {
      // TODO: send back a RequestedSpace
    }
  }
}

object Space {
  def sid(id:OID) = "s" + id.toString
  def thingTable = sid _
  def historyTable(id:OID) = "h" + id.toString
}

sealed trait SpaceMgrMsg

// TODO: check whether the requester is authorized to look at this Space
case class GetSpace(id:OID, requester:Option[OID]) extends SpaceMgrMsg
sealed trait GetSpaceResponse
case class RequestedSpace(state:SpaceState) extends GetSpaceResponse
case class GetSpaceFailed(id:OID) extends GetSpaceResponse

case class ListMySpaces(owner:OID) extends SpaceMgrMsg
sealed trait ListMySpacesResponse
case class MySpaces(spaces:Seq[(OID,String)]) extends ListMySpacesResponse

// This responds eventually with a RequestedSpace:
case class CreateSpace(owner:OID, name:String) extends SpaceMgrMsg

class SpaceManager extends Actor {
  
  // The local cache of Space States.
  // TODO: this needs to age properly.
  // TODO: this needs a cap of how many states we will try to cache.
  var spaceCache:Map[OID,SpaceState] = Map.empty
  
  def addState(state:SpaceState) = spaceCache += (state.id -> state)
  
  // The System Space is hardcoded, and we create it at the beginning of time:
  addState(system.SystemSpace.State)
  
  var counter = 0
  
  // TEMP:
  val replyMsg = Play.configuration.getString("querki.test.replyMsg").getOrElse("MISSING REPLY MSG!")
  
  def receive = {
    case req:ListMySpaces => {
      val results = spaceCache.values.filter(_.owner == req.owner).map(space => (space.id, space.name)).toSeq
      sender ! MySpaces(results)
    }
    // TODO: this should go through the Space instead, for all except System. The
    // local cache in SpaceManager is mainly for future optimization in clustered
    // environments.
    case req:GetSpace => {
      val cached = spaceCache.get(req.id)
      if (cached.nonEmpty)
        sender ! RequestedSpace(cached.get)
      else
        sender ! GetSpaceFailed(req.id)
     }
    case req:CreateSpace => {
      // TODO: check that the owner hasn't run out of spaces he can create
      // TODO: check that the owner doesn't already have a space with that name
      // TODO: this involves DB access, so should be async using the Actor DSL
      val (spaceId, spaceActor) = createSpace(req.owner, req.name)
      // Now, let the Space Actor finish the process once it is ready:
      spaceActor.forward(req)
    }
  }
  
  private def createSpace(owner:OID, name:String) = {
    val spaceId = OID.next
    val spaceActor = context.actorOf(Props[Space], name = Space.sid(spaceId))
    DB.withTransaction { implicit conn =>
      SQL("""
          CREATE TABLE {tname} (
            id bigint NOT NULL,
            parent bigint NOT NULL,
            kind tinyint NOT NULL,
            props clob NOT NULL,
            PRIMARY KEY (id)
          )
          """).on("tname" -> Space.thingTable(spaceId)).executeUpdate()
      SQL("""
          INSERT INTO Spaces
          (id, shard, name, display, owner, size) VALUES
          ({sid}, {shard}, {name}, {display}, {ownerId}, 0)
          """).on("sid" -> spaceId.toString, "shard" -> 1.toString, "name" -> name,
                  "display" -> name, "ownerId" -> owner.toString).executeUpdate()
    }
    (spaceId, spaceActor)
  }
}

object SpaceManager {
  // I don't love having to hold a static reference like this, but Play's statelessness
  // probably requires that. Should we instead be holding a Path, and looking it up
  // each time?
  lazy val ref = Akka.system.actorOf(Props[SpaceManager], name="SpaceManager")
  
  // This is probably over-broad -- we're going to need the timeout to push through to
  // the ultimate callers.
  implicit val timeout = Timeout(5 seconds)
  
  // Send a message to the SpaceManager, expecting a return of type A to be
  // passed into the callback. This wraps up the messy logic to go from a
  // non-actor-based Play environment to the SpaceManager. We'll likely
  // generalize it further eventually.
  //
  // Type A is the response we expect to get back from the message, which will
  // be sent to the given callback.
  //
  // Type B is the type of the callback. I'm a little surprised that this isn't
  // inferred -- I suspect I'm doing something wrong syntactically.
  def ask[A,B](msg:SpaceMgrMsg)(cb: A => B)(implicit m:Manifest[A]):Promise[B] = {
    (ref ? msg).mapTo[A].map(cb).asPromise
  }
}