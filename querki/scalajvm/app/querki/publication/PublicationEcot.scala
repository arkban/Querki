package querki.publication

import scala.concurrent.duration._
import akka.pattern._
import akka.util.Timeout

import com.github.nscala_time.time.Imports.DateTimeFormat

import models._
import querki.api.commonName
import querki.ecology._
import querki.globals._
import querki.spaces.messages._
import querki.time.DateTime
import querki.values.QLContext

import PublicationCommands._
import PublicationEvents._

object MOIDs extends EcotIds(68) {
  val CanPublishOID = moid(1)
  val CanReadAfterPublicationOID = moid(2)
  val PublishableModelOID = moid(3)
  val MinorUpdateOID = moid(4)
  val PublishedOID = moid(5)
  val UnpublishedChangesOID = moid(6)
  val GetChangesOID = moid(7)
  val PublishEventTypeOID = moid(8)
  
  val PublishWhoOID = moid(9)
  val PublishDateOID = moid(10)
  val PublishMinorUpdateOID = moid(11)
  val PublishedThingTypeOID = moid(12)
  val PublishedThingsOID = moid(13)
  val PublishedThingOID = moid(14)
  val PublishedUpdateOID = moid(15)
  val PublishedDisplayNameOID = moid(16)
  val PublishedViewOID = moid(17)
  val PublishNotesPropOID = moid(18)
  val PublishedNotesFunctionOID = moid(19)
  
  val RecentChangesPageOID = moid(20)
  val SpaceHasPublicationsOID = moid(21)

  val PublishFunctionOID = moid(22)
}

class PublicationEcot(e:Ecology) extends QuerkiEcot(e) with querki.core.MethodDefs with Publication {
  import MOIDs._
  
  val AccessControl = initRequires[querki.security.AccessControl]
  val Basic = initRequires[querki.basic.Basic]
  val QDuration = initRequires[querki.time.QDuration]
  val Roles = initRequires[querki.security.Roles]
  val Time = initRequires[querki.time.Time]
  val Typeclasses = initRequires[querki.typeclass.Typeclasses]
  
  lazy val ApiRegistry = interface[querki.api.ApiRegistry]
  lazy val Person = interface[querki.identity.Person]
  lazy val QL = interface[querki.ql.QL]
  lazy val SpaceOps = interface[querki.spaces.SpaceOps]
  
  override def postInit() = {
    ApiRegistry.registerApiImplFor[PublicationFunctions, PublicationFunctionsImpl](SpaceOps.spaceRegion)
  }
  
  override def persistentMessages = persist(68,
    (classOf[PublishEvent] -> 100),
    (classOf[PublishedThingInfo] -> 101),
    (classOf[StartPublishingRSS] -> 102),
    (classOf[CurrentPublicationState] -> 103)
  )
  
  val PublicationTag = "Publication"
  
  /**
   * This is essentially an adapter for the not-quite-as-pure-as-it-should-be StatePure.
   */
  private case class StateEvolver(state:SpaceState)(implicit val ecology:Ecology) extends querki.spaces.SpacePure with EcologyMember {
    val id = state.id
    
    def enhance(pub:CurrentPublicationState):SpaceState = {
      (state /: pub.changes.values.flatten) { (s, evt) =>
        evolveState(Some(s))(evt)
      }      
    }
  }
  
  def enhanceState(state:SpaceState, pub:CurrentPublicationState):SpaceState = {
    StateEvolver(state).enhance(pub)
  }

  /******************************************
   * TYPES
   ******************************************/
  
  lazy val PublishEventType = new SystemType[OnePublishEvent](PublishEventTypeOID,
    toProps(
      setName("_publishEventType"),
      Categories(PublicationTag),
      Core.InternalProp(true),
      Summary("Represents a single Publish or Update event"))) with SimplePTypeBuilder[OnePublishEvent]
  {
    def doDeserialize(v:String)(implicit state:SpaceState) = ???
    def doSerialize(v:OnePublishEvent)(implicit state:SpaceState) = ???
    val defaultRenderFormat = DateTimeFormat.forPattern("MM/dd/yyyy")
    
    def doWikify(context:QLContext)(v:OnePublishEvent, displayOpt:Option[Wikitext] = None, lexicalThing:Option[PropertyBundle] = None) = {
      // TODO: for the moment, this is assuming exactly one Thing per Event. That may not always be the case:
      val thingInfo = v.things.head
      val when = defaultRenderFormat.print(v.when)
      val eventKind = if (thingInfo.isUpdate) "updated" else "published"
      Future.successful(Wikitext(s"* $when: [${thingInfo.displayName}](${thingInfo.thingId.toThingId.toString}) $eventKind"))
    }
    
    def doDefault(implicit state:SpaceState) = ???
    
    def doComputeMemSize(v:OnePublishEvent):Int = 0
  }
  
  lazy val PublishedThingType = new SystemType[OnePublishedThing](PublishedThingTypeOID,
    toProps(
      setName("_publishedThingType"),
      Categories(PublicationTag),
      Core.InternalProp(true),
      Summary("Represents a single Thing in a Publish or Update event"),
      Details("""While you usually Publish or Update a single Thing at a time, Querki is designed to
        |allow multi-Thing Publish events in the future. So from a Publish or Update event you call
        |`_publishedThings` to get the list of Things involved, and that produces one of these.""".stripMargin))) with SimplePTypeBuilder[OnePublishedThing]
  {
    def doDeserialize(v:String)(implicit state:SpaceState) = ???
    def doSerialize(v:OnePublishedThing)(implicit state:SpaceState) = ???
    val defaultRenderFormat = DateTimeFormat.forPattern("MM/dd/yyyy")
    
    def doWikify(context:QLContext)(thingInfo:OnePublishedThing, displayOpt:Option[Wikitext] = None, lexicalThing:Option[PropertyBundle] = None) = {
      val eventKind = if (thingInfo.isUpdate) "updated" else "published"
      Future.successful(Wikitext(s"* [${thingInfo.displayName}](${thingInfo.thingId.toThingId.toString}) $eventKind"))
    }
    
    def doDefault(implicit state:SpaceState) = ???
    
    def doComputeMemSize(v:OnePublishedThing):Int = 0
  }

  /******************************************
   * FUNCTIONS
   ******************************************/

  lazy val PublishFunction = new InternalMethod(PublishFunctionOID,
    toProps(
      setName("_publish"),
      Categories(PublicationTag),
      Summary("Publish the received Things, just like pressing the Publish button in the UI"),
      Signature(
        expected = Some(Seq(LinkType), "The Things to publish"),
        reqs = Seq.empty,
        opts = Seq.empty,
        returns = (AnyType, "The Things that were published (same as the input list)")
      ),
      Details("")
    ))
  {
    // TODO: this is annoyingly hard-coded. Think this through -- how long should it be? Should it
    // be configurable? (Probably.)
    implicit val timeout = Timeout(10 seconds)

    override def qlApply(inv: Invocation): QFut = {
      for {
        // TODO: this would be more efficient if we fetched the entire List of Things here, and sent
        // it as a list in the Publish() request. Enhance Invocation to support that?
        thing <- inv.contextAllThings
        who = inv.context.request.requesterOrAnon
        cmd = SpaceSubsystemRequest(who, inv.state.id, Publish(who, List(thing.id), emptyProps, inv.state))
        PublishResponse(updatedState) <- inv.fut(SpaceOps.spaceRegion ? cmd)
      } yield ExactlyOne(LinkType(thing))
    }
  }
  
  lazy val GetChangesFunction = new InternalMethod(GetChangesOID,
    toProps(
      setName("_getChanges"),
      Categories(PublicationTag),
      Summary("Fetch the Publication history of this Space, so you can see what has changed."),
      Signature(
        expected = None,
        reqs = Seq.empty,
        opts = Seq(
          ("since", Time.QDate, Core.QNone, "When the changes should start"),
          ("until", Time.QDate, Core.QNone, "When the changes should end"),
          ("duration", QDuration.DurationType, Core.QNone, "(Instead of `since`) What period before `until` to cover"),
          ("changesTo", LinkType, Core.QNone, "The Model(s) to include in the list"),
          ("includeMinor", YesNoType, ExactlyOne(YesNoType(false)), "If true, include Minor changes in the list"),
          ("reverse", YesNoType, ExactlyOne(YesNoType(false)), "If true, list the results in reverse-chronological order, with the most recent first")
        ),
        returns = (PublishEventType, "A series of Publication Events, in chronological order.")
      ),
      Details("Fill this in!")))
  {
    override def qlApply(inv:Invocation):QFut = {
      // TODO: this is annoyingly hard-coded. Think this through -- how long should it be? Should it
      // be configurable? (Probably.)
      implicit val timeout = Timeout(10 seconds)
      for {
        since <- inv.processAsOpt("since", Time.QDate)
        until <- inv.processAsOpt("until", Time.QDate)
        duration <- inv.processAsOpt("duration", QDuration.DurationType)
        start = {
          since.orElse {
            duration match {
              case Some(d) => {
                // There's a duration. When do we end?
                val end = until.getOrElse(DateTime.now)
                val period = QDuration.toPeriod(d, inv.state)
                Some(end.minus(period))
              }
              // No since and no duration, so there's no start time
              case None => None
            }
          }
        }
        changesTo <- inv.processAsList("changesTo", LinkType)
        includeMinor <- inv.processAs("includeMinor", YesNoType)
        reverse <- inv.processAs("reverse", YesNoType)
        who = inv.context.request.requesterOrAnon
        cmd = SpaceSubsystemRequest(who, inv.state.id, GetEvents(who, start, until, includeMinor, false))
        RequestedEvents(events) <- inv.fut(SpaceOps.spaceRegion ? cmd)
        orderedEvents = if (reverse) events.reverse else events
        filteredEvents = filterOnModels(changesTo, orderedEvents)(inv.state)
      }
        yield QList.makePropValue(filteredEvents.map(PublishEventType(_)), PublishEventType)
    }
    
    /**
     * This filters out Events that don't involve the desired Models. It also filters out deleted Instances.
     */
    def filterOnModels(changesTo:List[OID], events:Seq[OnePublishEvent])(implicit state:SpaceState):Seq[OnePublishEvent] = {
      if (changesTo.isEmpty) {
        events.filter { event =>
          event.things.exists { thingInfo =>
            state.anything(thingInfo.thingId).isDefined
          }
        }
      } else {
        events.filter { event =>
          event.things.exists { thingInfo =>
            state.anything(thingInfo.thingId).map { thing =>
              changesTo.contains(thing.model)
            }.getOrElse(false)
          }
        }
      }
    }
  }
    
  /***********************************************
   * ACCESSORS
   * 
   * These are access functions for OnePublishEvent and OnePublishedThing.
   ***********************************************/
  
  lazy val PublishWhoImpl = new FunctionImpl(PublishWhoOID, Typeclasses.WhoMethod, Seq(PublishEventType))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        evt <- inv.contextAllAs(PublishEventType)
        identity = evt.who
        person <- inv.opt(Person.localPerson(identity.id)(inv.state))
      }
        yield ExactlyOne(LinkType(person))
    }
  }
  
  lazy val PublishDateImpl = new FunctionImpl(PublishDateOID, Typeclasses.DateMethod, Seq(PublishEventType))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        evt <- inv.contextAllAs(PublishEventType)
        dateTime = evt.when
      }
        yield ExactlyOne(Time.QDate(dateTime))
    }
  }
  
  lazy val PublishMinorUpdateFunction = new InternalMethod(PublishMinorUpdateOID, 
    toProps(
      setName("_isMinorUpdate"),
      Categories(PublicationTag),
      Summary("This will be true iff this was marked as a Minor Update")))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        evt <- inv.contextAllAs(PublishEventType)
      }
        yield ExactlyOne(YesNoType(evt.isMinor))
    }
  }
  
  lazy val PublishedThingsFunction = new InternalMethod(PublishedThingsOID,
    toProps(
      setName("_publishedThings"),
      Categories(PublicationTag),
      Summary("This produces the Things involved in a Publish or Update event."),
      Details("""These are of [[_publishedThingType]], and have their own functions to get information from them.""".stripMargin)))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        evt <- inv.contextAllAs(PublishEventType)
        thingInfo <- inv.iter(evt.things)
      }
        yield ExactlyOne(PublishedThingType(thingInfo))
    }
  }
  
  lazy val PublishedThingImpl = new FunctionImpl(PublishedThingOID, Typeclasses.ThingMethod, Seq(PublishedThingType))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        thingInfo <- inv.contextAllAs(PublishedThingType)
      }
        yield ExactlyOne(LinkType(thingInfo.thingId))
    }
  }
  
  lazy val PublishedUpdateFunction = new InternalMethod(PublishedUpdateOID,
    toProps(
      setName("_isAnUpdate"),
      Categories(PublicationTag),
      Summary("This says whether a [[_publishedThingType]] is an Update (if true) or the first Publish for this Thing (if false)")))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        thingInfo <- inv.contextAllAs(PublishedThingType)
      }
        yield ExactlyOne(YesNoType(thingInfo.isUpdate))
    }
  }
  
  lazy val PublishedDisplayNameFunction = new InternalMethod(PublishedDisplayNameOID,
    toProps(
      setName("_publishedName"),
      Categories(PublicationTag),
      Summary("Given a [[_publishedThingType]], this produces its Name at the time it was Published or Updated.")))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        thingInfo <- inv.contextAllAs(PublishedThingType)
      }
        yield ExactlyOne(TextType(thingInfo.displayName))
    }
  }
  
  lazy val PublishedViewFunction = new InternalMethod(PublishedViewOID,
    toProps(
      setName("_publishedView"),
      Categories(PublicationTag),
      Summary("Given a [[_publishedThingType]], this produces what it looked like when it was Published or Updated.")))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        thingInfo <- inv.contextAllAs(PublishedThingType)
      }
        // This is pre-rendered, so wrap it accordingly:
        yield ExactlyOne(QL.ParsedTextType(HtmlWikitext(thingInfo.display)))
    }
  }
  
  lazy val PublishedNotesFunction = new InternalMethod(PublishedNotesFunctionOID,
    toProps(
      setName("_publishedNotes"),
      Categories(PublicationTag),
      Summary("Any Change Notes that were included with this Publish or Update.")))
  {
    override def qlApply(inv:Invocation):QFut = {
      for {
        evt <- inv.contextAllAs(PublishEventType)
        notes <- inv.opt(evt.notes)
      }
        yield ExactlyOne(LargeTextType(notes.text))
    }
  }
  
  /***********************************************
   * PROPERTIES
   ***********************************************/
  
  lazy val CanPublishPermission = AccessControl.definePermission(
      CanPublishOID, 
      commonName(_.publication.canPublishPerm), 
      "Who is allowed to Publish Instances in this Space; this is always defined on the Space itself, never on individual Things",
      Seq(Roles.EditorRole),
      Seq(AccessControl.AppliesToSpace),
      false, false)
  
  lazy val CanReadAfterPublication = AccessControl.definePermission(
      CanReadAfterPublicationOID, 
      commonName(_.publication.canReadAfterPublishPerm), 
      "After an Instance has been Published, who can read it?",
      Seq(AccessControl.PublicTag),
      Seq(AccessControl.AppliesToInstances),
      false, true)
      
  // TODO: this should become an Internal Property, once we have a formal Publication page in the UI:
  lazy val PublishableModelProp = new SystemProperty(PublishableModelOID, YesNoType, ExactlyOne,
    toProps(
      setName(commonName(_.publication.publishableProp)),
      setInternal,
      Summary("Indicates that Instances of this Model will be formally Published when they are ready"),
      Details("""In some Spaces, particularly public ones such as blogs, FAQs, and other information sources,
        |you want to impose some structure when creating new Things. Instead of just making everything publicly
        |available as soon as you create them, you want to work on them for a while first, drafting them
        |privately, and then making them publicly available once they are ready. This step is called
        |Publication, and this Property makes it happen.
        |
        |If you have a Model that you want to publish like this, add this Property and turn it on. This
        |will change the workflow for Instances of the Model in a number of ways:
        |
        |* Newly-created Instances will have the Who Can Read permission set to the same value as the
        |Model itself. (You should usually set that to Editors only.)
        |* The Editor for Instances will gain a new button that lets you Publish that Instance.
        |* When it is published, the Instance will become Public. (This is the Who Can Read After Publications
        |permission; you can change it to something other than Public if you prefer.)
        |* The Space gains a Recent Changes page, which shows the Published Instances in Publication order.
        |* Once Published, Instances can later be formally Updated, which adds another entry to Recent Changes.
        |* The Space gains an RSS feed, so that Published Instances can be monitored from outside Querki. This
        |allows you to treat any sort of Querki information as a sort of blog.""".stripMargin)))
 
  lazy val MinorUpdateProp = new SystemProperty(MinorUpdateOID, YesNoType, ExactlyOne,
    toProps(
      setName("_minorUpdateInternal"),
      setInternal,
      Summary("Iff set, this Update should be considered Minor."),
      Details("""This is the Property behind the "Minor Update" button in the Editor. It is an
        |internal meta-Property on the Publication event itself, rather than on the Thing. You should
        |never use this directly""".stripMargin)))
  
  lazy val PublishedProp = new SystemProperty(PublishedOID, YesNoType, ExactlyOne,
    toProps(
      setName(commonName(_.publication.publishedProp)),
      setInternal,
      Summary("Set to true when this Instance gets Published.")))
  
  lazy val HasUnpublishedChanges = new SystemProperty(UnpublishedChangesOID, YesNoType, ExactlyOne,
    toProps(
      setName(commonName(_.publication.hasUnpublishedChangesProp)),
      setInternal,
      Summary("Set to true iff this Thing has been Published, then edited but not yet Updated.")))
  
  lazy val PublishNotesProp = new SystemProperty(PublishNotesPropOID, LargeTextType, Optional,
    toProps(
      setName(commonName(_.publication.publishNotesProp)),
      setInternal,
      Summary("The notes to include with the next Publish or Update of this Thing.")))
  
  lazy val SpaceHasPublications = new SystemProperty(SpaceHasPublicationsOID, YesNoType, Optional,
    toProps(
      setName(commonName(_.publication.spaceHasPublicationsProp)),
      setInternal,
      Summary("This is set to true iff the Space contains Publishable Models")))

  override lazy val props = Seq(
    PublishFunction,
    GetChangesFunction,
    
    PublishWhoImpl,
    PublishDateImpl,
    PublishMinorUpdateFunction,
    PublishedThingsFunction,
    PublishedThingImpl,
    PublishedUpdateFunction,
    PublishedDisplayNameFunction,
    PublishedViewFunction,
    PublishedNotesFunction,
    
    CanPublishPermission,
    CanReadAfterPublication,
    PublishableModelProp,
    MinorUpdateProp,
    PublishedProp,
    HasUnpublishedChanges,
    PublishNotesProp,
    SpaceHasPublications
  )
  
  /******************************************
   * THINGS
   ******************************************/
  
  lazy val RecentChangesPage = ThingState(RecentChangesPageOID, systemOID, RootOID,
    toProps(
      setName("recent-space-changes"),
      setInternal,
      Categories(PublicationTag),
      Summary("Displays the most recently published changes in this Space."),
      Basic.DisplayNameProp("Recent Changes"),
      Basic.DisplayTextProp("""[[_getChanges(reverse=true) ->
        |""{{well well-sm:
        |[[_date]]: [[_who]] [[_if(_publishedThings -> _isAnUpdate, ""updated"", ""published"")]] 
        |[[_publishedThings -> _thing]]
        |[[_if(_not(_equals(_publishedThings -> _thing -> Name, _publishedThings -> _publishedName)), "" (then named [[_publishedThings -> _publishedName]]) "")]] 
        |[[_if(_isMinorUpdate, ""(minor update)"")]] [[_if(_isNonEmpty(_publishedNotes), ""
        |
        |[[_publishedNotes]]"")]]
        |}}""]]""".stripMargin)))
  
  override lazy val things = Seq(
    RecentChangesPage
  )
}
