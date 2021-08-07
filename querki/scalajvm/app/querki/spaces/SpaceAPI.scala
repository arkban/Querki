package querki.spaces

import akka.actor._
import org.querki.requester._
import models._
import org.joda.time.DateTime
import querki.identity.IdentityPersistence.UserRef
import querki.identity.User
import querki.persistence.{PersistentActorCore, UseKryo}
import querki.spaces.messages.SpaceMessage
import querki.values.{SpaceState, SpaceVersion}

/**
 * The abstraction of a Space, so that SpacePlugins can interact with it.
 *
 * IMPORTANT: this must NEVER be used outside of a SpacePlugin, when that Plugin has been properly
 * invoked by the Space itself! Anything else is incredibly dangerous!
 */
trait SpaceAPI[RM[_]] extends PersistentActorCore {

  /**
   * Accessor to the current state of this Space.
   */
  def currentState: SpaceState

  /**
   * Persists the specified events, and lets you hook into what happens afterwards.
   */
  def persistAllAnd(events: collection.immutable.Seq[UseKryo]): RM[Seq[UseKryo]]

  /**
   * Create a new Thing.
   *
   * IMPORTANT: this is strictly for internal use, and does *not* do as much massaging as usual!
   *
   * @param sendAck Iff true, the usual ThingFound message will be sent to sender.
   */
  def doCreate(
    who: User,
    modelId: OID,
    props: PropMap,
    kind: Kind.Kind,
    thingIdOpt: Option[OID],
    creatorOpt: Option[UserRef] = None,
    createTimeOpt: Option[DateTime] = None
  )(implicit
    state: SpaceState
  ): RM[ChangeResult]

  /**
   * The newer and better way to modify a Thing.
   */
  def modifyThing(
    who: User,
    thingId: ThingId,
    modelIdOpt: Option[OID],
    rawNewProps: PropMap,
    replaceAllProps: Boolean
  )(
    state: SpaceState
  ): RM[ChangeResult]

  def loadAppVersion(
    appId: OID,
    version: SpaceVersion,
    appsSoFar: Map[OID, SpaceState]
  ): RM[SpaceState]

  /**
   * The heart of processing commands from the outside.
   *
   * This wraps around a function that takes the current SpaceState, and produces a transformation
   * of that state -- events that need to be persisted, the thing (if any) that serves as the "direct object"
   * of this transformation, and the resulting SpaceState. It then persists the events, updates the State
   * to the final State in the result list, and sends out the ThingFound/ThingError for the last entry.
   */
  def runAndSendResponse(
    opName: String,
    localCall: Boolean,
    func: SpaceState => RM[ChangeResult],
    publishEvent: Boolean
  )(
    state: SpaceState
  ): RM[List[ChangeResult]]

  /**
   * Lower-level guts of runAndSendResponse. This runs the given functions, and persists them, but does *not*
   * send anything. Anything that is potentially being called cross-node should use this, since runAndSendResponse
   * sends the local-only ThingFound response.
   *
   * Returns the ChangeResults, and the *final*, post-persistence SpaceState. (With an updated version number.)
   */
  def runChanges(funcs: Seq[SpaceState => RM[ChangeResult]])(state: SpaceState): RM[(List[ChangeResult], SpaceState)]

  /**
   * Fetches a set of new OIDs, for bulk operations.
   */
  def allocThingIds(nIds: Int): RM[Seq[OID]]
}

/**
 * A SpacePlugin allows external Ecots to add specialized messages that each Space can handle.
 * This is mainly for separation of concerns, so that the Space can be used as a synchronization
 * point without having to know about the entire world.
 *
 * Extend this class to create your specific plugin.
 *
 * Note that this takes an RM type parameter -- this is the monadic abstraction of RequestM, and *must*
 * be used instead of RequestM. In return, your Plugin can work in synchronous unit tests.
 *
 * ==Rules for SpacePlugins==
 * - Only use a SpacePlugin if you have a chunk of code that ''must'' happen inside the Space processing,
 *   SpacePlugins are automatically a little expensive, even if they are rarely invoked. They are
 *   mainly intended for situations that potentially need to ''alter'' the Space in ways that
 *   aren't encompassed by the ordinary messages.
 * - The SpacePlugin's receive function must be '''fast''', and must '''never''' block. It may send
 *   messages, and handle responses, for asynchronous processing.
 * - The SpacePlugin takes an RM type parameter, which is the abstraction of RequestM. It must '''always'''
 *   use that for handling asynchronous communication. This allows it to be synchronously unit-tested.
 * - The SpacePlugin must be self-contained. It will be owned by the Space itself, and will be discarded
 *   when the Space is unloaded.
 * - The SpacePlugin will only be called during command processing, ''not'' during recovery. Any persistence
 *   effects should happen through normal SpaceEvents.
 */
abstract class SpacePlugin[RM[_]](
  val space: SpaceAPI[RM],
  rtc: RTCAble[RM]
) {

  /**
   * The receive handler.
   *
   * This will be plugged into the Space's receive pipeline, and offered messages that are not otherwise
   * dealt with.
   */
  def receive: Actor.Receive
}

/**
 * This trait should be implemented by Ecots that want to plug their own code into all Spaces.
 *
 * TODO: the typeclasses here are *all* messed up -- I'm sure we are tying ourselves far too deeply
 * in knots. This needs rationalization!
 */
trait SpacePluginProvider {

  /**
   * Creates a plugin for the given Space.
   */
  def createPlugin[RM[_]](
    space: SpaceAPI[RM],
    rtc: RTCAble[RM]
  ): SpacePlugin[RM]
}
