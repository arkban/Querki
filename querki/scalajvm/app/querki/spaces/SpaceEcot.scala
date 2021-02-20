package querki.spaces

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Success

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.pattern._
import akka.util.Timeout

import models._

import querki.api.ClientRequest
import querki.core.NameUtils
import querki.ecology.{CreateActorFunc, Ecology, EcotIds, QuerkiEcot}
import querki.globals.{execContext, AnyProp, Future, _}
import querki.ql._
import querki.spaces.messages._
import querki.util.PublicException
import querki.util.ActorHelpers._
import querki.values.{ElemValue, EmptyValue, QLContext}

object SpaceEcotMOIDs extends EcotIds(37) {
  val CreateHereOID = moid(1)
  val ChangePropertiesOID = moid(2)
  val CreateThingFunctionOID = moid(3)
  val DeleteThingFunctionOID = moid(4)
  val MakePropertyBundleFunctionOID = moid(5)
  val CopyThingFunctionOID = moid(6)
}

class SpaceEcot(e:Ecology) extends QuerkiEcot(e) with SpaceOps with querki.core.MethodDefs {
  
  import SpaceEcotMOIDs._
  import SpaceMessagePersistence._
  
  val Models = initRequires[models.Models]
  val SystemManagement = initRequires[querki.system.SystemManagement]
  
  lazy val QL = interface[querki.ql.QL]
  
  lazy val PropValType = Models.PropValType
  
  /**
   * The one true handle to the Space Management system.
   */
  var _ref:Option[ActorRef] = None
  lazy val spaceManager = _ref.get
  var _region:Option[ActorRef] = None
  lazy val spaceRegion = _region.get
  
  // These two functions tell ClusterSharding the ID and shard for a given SpaceMessage. They are
  // then used to decide how to find/create the Space's Router (and thus, its troupe).
  val idExtractor:ShardRegion.ExtractEntityId = {
    case msg @ ClientRequest(req, rc) => (rc.spaceIdOpt.get.toString(), msg) 
    case msg @ SpaceMessage(req, spaceId) => (spaceId.toString(), msg) 
  }
  
  val shardResolver:ShardRegion.ExtractShardId = msg => msg match {
    case ClientRequest(req, rc) => rc.spaceIdOpt.get.shard
    case msg @ SpaceMessage(req, spaceId) => spaceId.shard
  }
  
  override def createActors(createActorCb:CreateActorFunc):Unit = {
    _region = SystemManagement.createShardRegion("Space", SpaceRouter.actorProps(ecology), 
        idExtractor, shardResolver)
    _ref = createActorCb(Props(classOf[SpaceManager], ecology, spaceRegion), "SpaceManager")
  }
  
  override def persistentMessages = persist(37,
    (classOf[DHCreateThing] -> 100),
    (classOf[DHModifyThing] -> 101),
    (classOf[DHDeleteThing] -> 102),
    (classOf[SpaceSnapshot] -> 103),
    (classOf[DHInitState] -> 104),
    (classOf[DHSetState] -> 105),
    (classOf[DHAddApp] -> 106)
  )
  
  implicit val stdTimeout = Timeout(10 seconds)
  
  def spaceIdGuts[R](ownerId:OID, spaceId:String, onSuccess:OID => R, onFailure:ThingError => R):Future[R] = {
    ThingId(spaceId) match {
      case AsOID(oid) => Future.successful(onSuccess(oid))
      case AsName(name) => {
        val canonName = NameUtils.canonicalize(name)
        ask(spaceManager, PersistMessages.GetSpaceByName(ownerId, canonName)).map {
          case SpaceId(spaceId) => onSuccess(spaceId)
          case err:ThingError => onFailure(err)
        }
      }
    }
  }
  
  def getSpaceId(ownerId:OID, spaceId:String):Future[OID] = {
    spaceIdGuts(ownerId, spaceId, oid => oid, err => throw new PublicException("Space.get.notFound", spaceId))
  }
  
  def spaceExists(ownerId:OID, spaceId:String):Future[Boolean] = {
    spaceIdGuts(ownerId, spaceId, oid => true, err => false)
  }
    
  def askSpaceManager[A,B](msg:SpaceMgrMsg)(cb: A => Future[B])(implicit m:Manifest[A]):Future[B] = {
    akka.pattern.ask(spaceManager, msg).mapTo[A].flatMap(cb)
  }
  
  def askSpaceManager2[B](msg:SpaceMgrMsg)(cb: PartialFunction[Any, Future[B]]):Future[B] = {
    akka.pattern.ask(spaceManager, msg).flatMap(cb)
  }
  
  // TODO: make this signature less idiotic:
  def askSpace[A, B](msg:SpaceMessage)(cb: A => Future[B])(implicit m:Manifest[A]):Future[B] = {
    akka.pattern.ask(spaceRegion, msg).mapTo[A].flatMap(cb)
  }
  
  def askSpace2[B](msg:SpaceMessage)(cb: PartialFunction[Any, Future[B]]):Future[B] = {
    akka.pattern.ask(spaceRegion, msg).flatMap(cb)
  }

  var _evolutionListener: Option[() => Unit] = None
  def notifyFullEvolution(): Unit = {
    _evolutionListener.map(_())
  }
  def registerFullEvolutionListener(f: () => Unit): Unit = {
    _evolutionListener = Some(f)
  }

  /***********************************************
   * FUNCTIONS
   ***********************************************/
  
  /**
   * This takes a single property-value parameter from _changeProperties or _createThing, and turns it
   * into a useful PropValType.
   */
  def getOnePropVal(inv: Invocation, context: QLContext, paramNum: Integer): InvocationValue[ElemValue] = {
    val parser = context.parser.get
    for {
      exp <- inv.rawParam(paramNum)
      (propName, vExp) <- 
        inv.opt(for {
          phrase <- exp.phrases.headOption
          stage <- phrase.ops.headOption
          (name, paramsOpt) <- stage match {
            case QLCall(name, _, params, _) => Some((name, params))
            case _ => None
          }
          param <- paramsOpt.flatMap(_.headOption)
        }
          yield (name.name, param.exp))
      thing <- inv.opt(inv.state.anythingByName(propName))
      // TODO: this is horrible. Make this right, with property error checking:
      prop = thing.asInstanceOf[AnyProp]
      resultContext <- inv.fut(parser.processExp(vExp, context, false))
      qv = resultContext.value
      // Do type coercion, if necessary:
      fixedType <-
        if (ElemValue.matchesTypeExact(qv.pType, prop.pType))
          inv.wrap(qv)
        else if (qv.pType.canCoerceTo(prop.pType)) {
          inv.wrap(qv.coerceTo(prop.pType).get)
        } else if (qv.pType == Core.UnknownType && qv.size == 0) {
          inv.wrap(EmptyValue(prop.pType))
        } else {
          inv.error("Func.paramWrongType", prop.displayName, "0", prop.pType.displayName, qv.pType.displayName)
        }
      }
      yield PropValType(Map(prop.id -> fixedType))
  }
  
  def getPropVals(inv:Invocation, context: QLContext, startingAt: Int): InvocationValue[PropMap] = {
    val nParams = inv.numParams
    val fullQV = (inv.wrap(EmptyValue(PropValType)) /: (startingAt until nParams)) { (invv, paramNum) =>
      invv.flatMap { qv =>
        getOnePropVal(inv, context, paramNum).map { elemVal =>
          val (newQV, _) = qv.append(elemVal)
          newQV
        }
      }
    }
    fullQV.map { qv =>
      // TODO: I'm sure this would be easier with Cats: we're just reducing List[PropMap], which is
      // a simple Monoidal reduce:
      (emptyProps /: qv.rawList(PropValType)) { (result, propMap) =>
        result ++ propMap
      }
    }
  }
  
  lazy val ChangeProperties = new InternalMethod(ChangePropertiesOID,
    toProps(
      setName("_changeProperties"),
      Categories(querki.datamodel.DataModelTag),
      SkillLevel(SkillLevelAdvanced),
      Summary("Change one or more Properties of the received Thing"),
      Details("""Most of the time in Querki, you edit Things using the UI directly, in 
        |the Editor. But
        |some Spaces want a bit of automation: they want to be able to make specific
        |changes when a button is pressed or a link is clicked.
        |
        |`_changeProperties()` provides this ability. You may specify as many 
        |parameters as you like, each of which should be a
        |Property Setter of the form `Property Name(value)`. So for example, if you had
        |a Whole Number Property named My Number, you would say `My Number(42)` to
        |initially set that Property to 42. Any Type of Property except for Functions can be
        |set this way, and you may set any or all of the Model's Properties.
        |
        |`_changeProperties()` produces the modified Thing; it is common to pass this to
        |`_edit` or `_navigateTo`.
        |
        |**Note:** the exact semantics of `_changeProperties()` will change down the line.
        |While it is fine to pass the resulting Thing to another *stage* (using `->`), you
        |should not count on being able to use it in later *phrases* (that is, lines not
        |connected by `->`). This is important so that we can later support multiple
        |changes that happen "atomically" in a light transactional system.""".stripMargin)))
  {
    override def qlApplyTop(inv:Invocation, transformThing:Thing):Future[QLContext] = {
      val vFut = for {
        context <- inv.contextElements
        thing <- inv.contextAllThings(context)
        initialProps <- getPropVals(inv, context, 0)
        msg = ChangeProps(inv.context.request.requesterOrAnon, inv.context.state, thing.id, initialProps)
        newState <- inv.fut(spaceRegion ? msg map { 
          case ThingFound(id, s) => { s }
        })
        newThing <- inv.opt(newState.anything(thing.id))
      }
        yield inv.context.nextFrom(ExactlyOne(LinkType(thing.id)), transformThing).withState(newState)
        
      vFut.get.map { contexts =>
        val qvs = contexts.map(_.value)
        val resultQV = QL.collectQVs(qvs, None, None)
        inv.receivedContext.next(resultQV)
      }
    }
  }
  
  lazy val CreateThingFunction = new InternalMethod(CreateThingFunctionOID,
    toProps(
      setName("_createThing"),
      Categories(querki.datamodel.DataModelTag),
      SkillLevel(SkillLevelAdvanced),
      Summary("Create a new Thing"),
      Signature(
        expected = Some(Seq.empty, "Anything"),
        reqs = Seq(
          ("model", LinkType, "The Model to create the new Thing from")
        ),
        opts = Seq.empty,
        returns = (LinkType, "The newly-created Thing.")
      ),
      Details("""Most of the time in Querki, you create new Things using the UI directly --
        |pressing a button or menu pick that creates the Thing and opens the Editor. But
        |some Spaces want a bit of automation: they want to be able to create a new Thing
        |as part of a `_QLButton()` press, often with specific Properties set in specific
        |ways.
        |
        |`_createThing()` immediately creates a new Thing. The first parameter *must* be
        |`model = <model name>`, saying what to create an Instance of.
        |
        |You may then specify as many parameters as you like, each of which should be a
        |Property Setter of the form `Property Name(value)`. So for example, if you had
        |a Whole Number Property named My Number, you would say `My Number(42)` to
        |initially set that Property to 42. Any Type of Property except for Functions can be
        |set this way, and you may set any or all of the Model's Properties.
        |
        |`_createThing()` produces the newly-created Thing; it is common to pass this to
        |`_edit` or `_navigateTo`.
        |
        |**Note:** the exact semantics of `_createThing()` will change down the line.
        |While it is fine to pass the resulting Thing to another *stage* (using `->`), you
        |should not count on being able to use it in later *phrases* (that is, lines not
        |connected by `->`). This is important so that we can later support multiple
        |changes that happen "atomically" in a light transactional system.""".stripMargin)))
  {
    override def qlApplyTop(inv:Invocation, transformThing:Thing):Future[QLContext] = {
      val vFut = for {
        model <- inv.processAs("model", LinkType)
        initialProps <- getPropVals(inv, inv.context, 1)
        msg = CreateThing(inv.context.request.requesterOrAnon, inv.context.state, Kind.Thing, model.id, initialProps)
        (thingId, newState) <- inv.fut(spaceRegion ? msg map { 
          case ThingFound(id, s) => { (id, s) }
        })
      }
        yield inv.context.nextFrom(ExactlyOne(LinkType(thingId)), transformThing).withState(newState)
        
      vFut.get.map(_.head)
    }
  }

  lazy val CopyThingFunction = new InternalMethod(CopyThingFunctionOID,
    toProps(
      setName("_copyThing"),
      Categories(querki.datamodel.DataModelTag),
      SkillLevel(SkillLevelAdvanced),
      Summary("Copy an existing Thing"),
      Signature(
        expected = Some(Seq(LinkType), "The Thing to copy"),
        reqs = Seq.empty,
        opts = Seq.empty,
        returns = (LinkType, "The newly-copied Thing.")
      ),
      Details(
        """Occasionally, you want to duplicate an existing Thing. This lets you do exactly that:
          |it receives a Thing, and produces a copy of it.
          |
          |This may take any number of property-setter parameters, the same as in `_createThing`. These
          |will override the copied parameters from the original Thing.
          |
          |Note that `Link Name` must be unique, so if you try to simply copy something that has a `Link Name`,
          |it is likely to fail. In this case, you should set the `Link Name` in the operation:
          |
          |[[```
          |_copyThing(Link Name(""New Thing Link Name""))
          |```]]
          |
          |If you feed a List into `_copyThing`, it will copy all of them. **This is dangerous**, so please
          |don't abuse it!
          |
          |Note that this will copy the Display Name of the original Thing if there was one, so you will
          |often want to feed this into a call to `_edit`, to fix up those details.""".stripMargin)))
  {
    override def qlApplyTop(inv:Invocation, transformThing:Thing):Future[QLContext] = {
      val vFut = for {
        original <- inv.contextAllThings
        model = original.model
        baseProps = original.propMap
        overrideProps <- getPropVals(inv, inv.context, 0)
        initialProps = baseProps ++ overrideProps
        msg = CreateThing(inv.context.request.requesterOrAnon, inv.context.state, Kind.Thing, model.id, initialProps)
        (thingId, newState) <- inv.fut(spaceRegion ? msg map {
          case ThingFound(id, s) => { (id, s) }
        })
      }
        yield inv.context.nextFrom(ExactlyOne(LinkType(thingId)), transformThing).withState(newState)

      vFut.get.map(_.head)
    }
  }


  lazy val DeleteThingFunction = new InternalMethod(DeleteThingFunctionOID,
    toProps(
      setName("_deleteThing"),
      Categories(querki.datamodel.DataModelTag),
      SkillLevel(SkillLevelAdvanced),
      Summary("Delete a Thing"),
      Signature(
        expected = Some(Seq(LinkType), "A Thing"),
        reqs = Seq.empty,
        opts = Seq.empty,
        returns = (AnyType, "Not a useful value. It is usually recommended to call `navigateTo` on a specific Thing after this.")
      ),
      Details("""This is the counterpart to `_createThing()`. It **immediately** and
                |**unconditionally** deletes the specified Thing. So use this function
                |with great care!""".stripMargin)))
  {
    override def qlApplyTop(inv:Invocation, transformThing:Thing):Future[QLContext] = {
      val vFut = for {
        thingId <- inv.contextAllAs(LinkType)
        msg = DeleteThing(inv.context.request.requesterOrAnon, inv.context.state, thingId)
        (thingId, newState) <- inv.fut(spaceRegion ? msg map { 
          case ThingFound(id, s) => { (id, s) }
        })
      }
        yield inv.context.nextFrom(ExactlyOne(IntType(0)), transformThing).withState(newState)
        
      vFut.get.map(_.head)
    }
  }
  
  lazy val MakePropertyBundleFunction = new InternalMethod(MakePropertyBundleFunctionOID,
    toProps(
      setName("_makePropertyBundle"),
      Categories(querki.datamodel.DataModelTag),
      SkillLevel(SkillLevelAdvanced),
      Summary("Create an ad-hoc collection of Property Values"),
      Signature(
        expected = Some(Seq.empty, "Anything"),
        reqs = Seq.empty,
        opts = Seq.empty,
        returns = (PropValType, "The collected Values, in a Bundle.")
      ),
      Details("""Occasionally, you have a complex QL expression, where you don't want to be passing a real Thing
                |from Stage to Stage, but instead you want a specific, rather ad-hoc bundle of Property Values.
                |`_makePropertyBundle` allows you to create such a bundle.
                |
                |When we talk about a "Property Bundle", we mean a collection of Property Values that has no
                |real *identity* -- it doesn't have an OID, or a Name, and you can't view it as a page in Querki.
                |It's just some value that you are going to use in further QL processing.
                |
                |As of this writing, this is a very new feature, and most QL functions do *not* work with Property Bundles yet. If you see a
                |function that doesn't do something sensible with a Property Bundle, and you would like to use
                |it with one, please write to us.""".stripMargin)))
  {
    override def qlApply(inv: Invocation): QFut = {
      for {
        context <- inv.contextElements
        bundle <- getPropVals(inv, context, 0)
      }
        yield ExactlyOne(PropValType(bundle))
    }
  }
  
  lazy val CreateHere = new InternalMethod(CreateHereOID,
    toProps(
      setName("_createHere"),
      Categories(querki.datamodel.DataModelTag),
      SkillLevel(SkillLevelAdvanced),
      Summary("**Deprecated** -- Create a new Thing as part of displaying this expression"),
      Details("""```
        |MODEL -> LINK PROPERTY._createHere -> THING
        |```
        |
        |This allows you to create a new Thing, right here on the page, as part of 
        |displaying. It is useful to put inside of a _QLButton, so that you can create something
        |and edit it, on the press of a button, without changing pages.
        |
        |**Warning:** this function is a little suspicious in how it works. It might get
        |replaced by some other approach in the future, so don't get too attached to it.
        |At this point, it is gently deprecated in favor of _createThing(); please start
        |to use that instead.
        |
        |(Technically speaking, the problem is that this function is *impure* -- it has
        |major side-effects. It is just about the only impure function in Querki, and we
        |may want to find a purer way to get the same result.)""".stripMargin)))
  {
    override def qlApplyTop(inv:Invocation, transformThing:Thing):Future[QLContext] = {
      // Need to shortcut with some mutation, since we don't have a good way to get this
      // side-effect out:
      var newState:Option[SpaceState] = None
      val vFut:QFut = for {
        model <- inv.contextFirstThing
        initialProps <- inv.fut(getInitialProps(inv))
        msg = CreateThing(inv.context.request.requesterOrAnon, inv.context.state, Kind.Thing, model.id, initialProps)
        newThingId <- inv.fut(spaceRegion ? msg map { 
          case ThingFound(id, s) => {
            newState = Some(s)
            id
          }
        })
      }
        yield ExactlyOne(LinkType(newThingId))
        
      vFut.map(v => inv.context.nextFrom(v, transformThing).withState(newState.get))
    }
    
    def getInitialProps(inv:Invocation):Future[PropMap] = {
      val linkBack = for {
        linkPropOpt <- inv.definingContextAsOptionalPropertyOf(LinkType)
        linkProp <- inv.opt(linkPropOpt)
        // It's important to create the new value using the correct Collection, since that's about to
        // get stored:
        ctype = linkProp.cType
        lexicalThing <- inv.opt(inv.lexicalThing match { case Some(t:Thing) => Some(t); case _ => None })
       }
        yield Map(linkProp(ctype(LinkType(lexicalThing))))
        
      linkBack.get.map(_.headOption.getOrElse(emptyProps))
    }
  }
  
  override lazy val props = Seq(
    ChangeProperties,
    CreateThingFunction,
    CopyThingFunction,
    DeleteThingFunction,
    MakePropertyBundleFunction,
    CreateHere
  )
}
