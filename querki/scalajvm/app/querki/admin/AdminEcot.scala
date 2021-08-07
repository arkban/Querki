package querki.admin

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor._
import akka.cluster.ddata._
import akka.cluster.singleton._
import akka.pattern._
import akka.util.Timeout

import models.Wikitext

import querki.core.QLText
import querki.ecology._
import querki.email.EmailNotifier
import querki.globals._
import querki.identity.{Identity, PublicIdentity, User, UserId}
import querki.notifications._
import querki.spaces.messages.{GetSpacesStatus, SpaceStatus}
import querki.time.DateTime
import querki.values.QLContext

case class SystemStatus(spaces: Seq[SpaceStatus])

private[admin] object MOIDs extends EcotIds(43) {
  val HeaderOID = moid(1)
  val BodyOID = moid(2)
  val ClusterAddressOID = moid(3)
  val InspectByEmailCmdOID = moid(4)
  val DeleteEmailAddressCmdOID = moid(5)
  val ShowThingCmdOID = moid(6)
  val ShowSpaceIdCmdOID = moid(7)
}

private[admin] trait AdminInternal extends EcologyInterface {

  def createMsg(
    from: PublicIdentity,
    header: String,
    body: String
  ): Notification
  def TimedSpaceKey: ORSetKey[String]
  def setTimedSpaces(spaces: Set[OID]): Unit
  def curTimedSpaces: Set[OID]
}

class AdminEcot(e: Ecology)
  extends QuerkiEcot(e)
     with EcologyMember
     with AdminOps
     with AdminInternal
     with querki.core.MethodDefs {
  import AdminActor._
  import MOIDs._

  // Sub-Ecot, which deals with all the Commands.
  val cmds = new AdminCommands(e)

  lazy val ApiRegistry = interface[querki.api.ApiRegistry]
  lazy val Basic = interface[querki.basic.Basic]
  lazy val NotifierRegistry = interface[querki.notifications.NotifierRegistry]
  lazy val QL = interface[querki.ql.QL]
  lazy val SpaceOps = interface[querki.spaces.SpaceOps]
  lazy val SpacePersistence = interface[querki.spaces.SpacePersistence]
  lazy val System = interface[querki.system.System]
  lazy val SystemManagement = interface[querki.system.SystemManagement]

  lazy val SystemState = System.State

  /**
   * The one true handle to the Admin Actor, which deals with asynchronous communications with the other Actors.
   */
  var _ref: Option[ActorRef] = None
  lazy val adminActor = _ref.get

  var _monitorManager: Option[ActorRef] = None
  lazy val monitorManager = _monitorManager.get
  var _monitorProxy: Option[ActorRef] = None
  lazy val monitor = _monitorProxy.get

  override def createActors(createActorCb: CreateActorFunc): Unit = {
    _ref = createActorCb(Props(classOf[AdminActor], ecology), "Admin")

    val (mgr, proxy) = SystemManagement.createClusterSingleton(
      createActorCb,
      AdminMonitor.actorProps(ecology),
      "MonitorManager",
      "MonitorProxy",
      PoisonPill
    )
    _monitorManager = mgr
    _monitorProxy = proxy
  }

  override def postInit() = {
    NotifierRegistry.register(SystemMessageNotifier)
    ApiRegistry.registerApiImplFor[AdminFunctions, AdminFunctionsImpl](monitor, true)
  }

  override def term() = {
    NotifierRegistry.unregister(SystemMessageNotifier)
  }

  val TimedSpaceKey = ORSetKey[String]("TimedSpaces")
  // Ick. Yes, this is a var. This reflects the fact that we want all of the Spaces on this node
  // to be able to quickly check whether they are being timed. I'd like a better approach, but I'm
  // not sure it's worth doing this entirely cleanly.
  var _curTimedSpaces = collection.immutable.Set.empty[OID]
  def setTimedSpaces(spaces: Set[OID]) = _curTimedSpaces = spaces
  def curTimedSpaces = _curTimedSpaces
  def isTimedSpace(spaceId: OID) = _curTimedSpaces.contains(spaceId)

  def getSpacesStatus[B](req: User)(cb: SystemStatus => B): Future[B] = {
    akka.pattern.ask(adminActor, GetSpacesStatus(req))(Timeout(5 seconds)).mapTo[SystemStatus].map(cb)
  }

  def sendSystemMessage(
    req: User,
    header: String,
    body: String
  ) = {
    adminActor ! SendSystemMessage(req, header: String, body: String)
  }

  def getAllUserIds(req: User): Future[Seq[UserId]] = {
    // Very long timeout for this one, becaue it really might take a long time:
    implicit val timeout = Timeout(1 minute)
    val fut = adminActor ? GetAllUserIdsForAdmin(req)
    fut.mapTo[AllUserIds].map { _.users }
  }

  object Notifiers {
    val SystemMessage: Short = 1
  }

  object SystemMessageNotifier extends Notifier {
    def id = NotifierId(MOIDs.ecotId, Notifiers.SystemMessage)

    // System Messages don't get summarized -- they are infrequent and important:
    def summarizeAt: SummarizeAt.SummarizeAt = SummarizeAt.None

    def summarizeNew(
      context: QLContext,
      notes: Seq[Notification]
    ): Future[SummarizedNotifications] = {
      if (notes.length != 1)
        throw new Exception("SystemMessageNotifier.summarizeNew expects exactly one notification at a time!")

      val note = notes.head
      render(context, note).map { rendered =>
        SummarizedNotifications(rendered.headline, rendered.content, notes)
      }
    }

    def render(
      context: QLContext,
      note: Notification
    ): Future[RenderedNotification] = {
      val rawPayload = note.payload
      val payload = SpacePersistence.deserProps(rawPayload, SystemState)
      val futuresOpt = for {
        headerQV <- payload.get(HeaderOID)
        headerQL <- headerQV.firstAs(LargeTextType)
        header = QL.process(headerQL, context)
        bodyQV <- payload.get(BodyOID)
        bodyQL <- bodyQV.firstAs(TextType)
        body = QL.process(bodyQL, context)
      } yield (header, body)

      futuresOpt match {
        case Some((headerFut, bodyFut)) => {
          for {
            header <- headerFut
            body <- bodyFut
          } yield RenderedNotification(header, body)
        }
        case None => throw new Exception("SystemMessageNotifier received badly-formed Notification?")
      }
    }

    def emailNotifier: Option[EmailNotifier] = None
  }

  def createMsg(
    from: PublicIdentity,
    header: String,
    body: String
  ): Notification = {
    val payload = toProps(SystemMsgHeader(header), SystemMsgBody(body))
    Notification(
      EmptyNotificationId,
      from.id,
      None,
      SystemMessageNotifier.id,
      DateTime.now,
      None,
      None,
      SpacePersistence.serProps(payload, SystemState)
    )
  }

  /**
   * *********************************************
   * FUNCTIONS
   * *********************************************
   */

  lazy val ClusterAddressFunction = new InternalMethod(
    ClusterAddressOID,
    toProps(
      setName("_clusterAddress"),
      setInternal,
      Summary("Produces the address of this Space inside the Querki Cluster"),
      Details("This is how the Advanced Page shows where this Space currently resides.")
    )
  ) {

    override def qlApply(inv: Invocation): QFut = {
      fut(ExactlyOne(Basic.PlainTextType(SystemManagement.clusterAddress)))
    }
  }

  /**
   * *********************************************
   * PROPERTIES
   * *********************************************
   */

  lazy val SystemMsgHeader = new SystemProperty(
    HeaderOID,
    LargeTextType,
    ExactlyOne,
    toProps(
      setName("_systemMessageHeader"),
      setInternal
    )
  )

  lazy val SystemMsgBody = new SystemProperty(
    BodyOID,
    TextType,
    ExactlyOne,
    toProps(
      setName("_systemMessageBody"),
      setInternal
    )
  )

  override lazy val props = Seq(
    ClusterAddressFunction,
    SystemMsgHeader,
    SystemMsgBody
  )
}
