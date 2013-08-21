package controllers

import language.existentials
import play.api._
import play.api.data._
import play.api.data.Forms._
import play.api.libs.concurrent.Promise
import play.api.mvc._
import models._
import Property._
import system._
import models.system._
import models.system.SystemSpace._
import identity.User

import querki.html.HtmlRenderer

import SpaceError._

import querki.values.QLRequestContext

import querki.util.QLog

object Application extends Controller {
  
  case class UserForm(name:String, password:String)
  val userForm = Form(
    mapping(
      "name" -> nonEmptyText,
      "password" -> nonEmptyText
    )((name, password) => UserForm(name, password))
     ((user: UserForm) => Some((user.name, "")))
  )
  
  val newSpaceForm = Form(
    mapping(
      "name" -> nonEmptyText
    )((name) => name)
     ((name:String) => Some(name))
  )
  
  case class NewThingForm(fields:List[String], model:String)
  val newThingForm = Form(
    mapping(
      "field" -> list(text),
      "model" -> text
    )((field, model) => NewThingForm(field, model))
     ((info:NewThingForm) => Some((info.fields, info.model)))
  )
  
  /**
   * Standard error handler. Iff you get an error and the correct response is to redirect to
   * another page, use this. The only exception is iff you need to preserve data, and thus want
   * to simply redisplay the current page; in that case, set the error in the RequestContext before
   * constructing the page.
   */
  def doError(redirectTo:Call, errorMsg:String) = {
    // TODO: figure out a better way to do this, and make it configurable:
    try {
      throw new Exception("Got error; redirecting: " + errorMsg)
    } catch {
      case e:Throwable => Logger.info(e.toString, e)
    }
    Redirect(redirectTo).flashing("error" -> errorMsg)
  }
  
  def getUser(username:String):Option[User] = User.get(username)
  
  def getUserByThingId(thingIdStr:String):OID = {
    val thingId = ThingId(thingIdStr)
    thingId match {
      case AsOID(oid) => oid
      case AsName(name) => {
        if (name.length() == 0) UnknownOID
        else {
          val userOpt = getUser(name)
          userOpt map (_.id) getOrElse UnknownOID
        }
      }
    }
  }
  
  def ownerName(state:SpaceState) = User.getName(state.owner)
  
  def userFromSession(request:RequestHeader) = User.get(request)
  // Workaround to deal with the fact that Security.Authenticated has to get a non-empty
  // result in order to let things through. So if a registered user is *optional*, we need to
  // return something:
  def forceUser(request: RequestHeader) = userFromSession(request) orElse Some(User.Anonymous)

  // TODO: preserve the page request, and go there after they log in
  def onUnauthorized(request: RequestHeader) = Results.Redirect(routes.Application.login)

  // Fetch the User from the session, or User.Anonymous if they're not found.
  def withAuth(f: => User => Request[AnyContent] => Result):EssentialAction = {
    val handler = { user:User =>
      Action(request => f(user)(request))
    }
    // Note that forceUsername can never fail, it just returns empty string
    Security.Authenticated(forceUser, onUnauthorized)(handler)
  }
  
  // TODO: we shouldn't be calling onUnauthorized directly! Instead, we should get a passed-in
  // handler to deal with lack of authorization, because we want to do different things in the
  // AJAX vs. page-view cases.
  //
  // Note that the requireLogin flag is critical, and subtle. This call will
  // *try* to get an authenticated user, but will only *require* it iff requireLogin is set.
  // This reflects the fact that there are many more or less public pages. It is the responsibility
  // of the caller to use this flag sensibly. Note that RequestContext.requester is guaranteed to
  // be set iff requireLogin is true.
  def withUser(requireLogin:Boolean)(f: RequestContext => Result) = withAuth { user => implicit request =>
    if (requireLogin && user == User.Anonymous) {
      onUnauthorized(request)
    } else {
      // Iff requireLogin was false, we might not have a real user here, so massage it:
      val userParam = if (user == User.Anonymous) None else Some(user)
      f(RequestContext(request, userParam, UnknownOID, None, None))
    }
  }
  
  /**
   * Helper for asking the SpaceManager for info. Assumes that the process is
   * asynchronous, and buffers the incoming HTTP request accordingly.
   * 
   * @tparam A The type of the expected return message from SpaceManager. This usually names a trait;
   * the actual messages should be derived from that.
   * @param msg The message to send to the SpaceManager.
   * @param cb A partial function that takes the SpaceManager response and produces a result.
   */
  def askSpaceMgr[A](msg:SpaceMgrMsg)(cb: A => Result)(implicit m:Manifest[A]) = {
    Async {
      SpaceManager.ask[A, Result](msg)(cb)
    }
  }
  
  /**
   * Given a Space, this fetches that Space's current state before calling the core logic.
   * 
   * TBD: Why the ridiculous return signature? Because I am getting cryptic errors about withSpace
   * being recursive otherwise.
   */
  def withSpace(
        requireLogin:Boolean, 
        ownerIdStr:String,
        spaceId:String, 
        thingIdStr:Option[String] = None,
        errorHandler:Option[PartialFunction[(ThingFailed, RequestContext), Result]] = None
      )(f: (RequestContext => Result)):EssentialAction = withUser(false) { rc =>
    val requester = rc.requester getOrElse User.Anonymous
    val thingId = thingIdStr map (ThingId(_))
    val ownerId = getUserByThingId(ownerIdStr)
    def withFilledRC(rc:RequestContext, stateOpt:Option[SpaceState], thingOpt:Option[Thing])(cb:RequestContext => Result):Result = {
      val filledRC = rc.copy(ownerId = ownerId, state = stateOpt, thing = thingOpt)
      // Give the listeners a chance to chime in:
      val updatedRC = PageEventManager.requestReceived(filledRC)
      val state = stateOpt.get
      val result =
        // Okay, now we have enough information to check whether we have a Space-specific authorization:
        if ((requireLogin && updatedRC.requester.isEmpty) || !state.canRead(updatedRC.requester.getOrElse(User.Anonymous), thingOpt.map(_.id).getOrElse(state)))
          onUnauthorized(updatedRC.request)
        else
          cb(updatedRC)
      updatedRC.updateSession(result)
    }
    askSpaceMgr[ThingResponse](GetThing(requester, ownerId, ThingId(spaceId), thingId)) {
      case ThingFound(id, state) => {
        val thingOpt = id match {
          case UnknownOID => None
          case oid:OID => state.anything(oid)
        }
        // Log what we got back -- turn this on as needed:
        //QLog.spewThing(thingOpt.getOrElse(state))
        if (thingIdStr.isDefined && thingOpt.isEmpty)
          doError(routes.Application.index, "That wasn't a valid path")
        else {
          withFilledRC(rc, Some(state), thingOpt)(f)
        }
      }
      case err:ThingFailed => {
        val ThingFailed(error, msg, stateOpt) = err
        if (stateOpt.isDefined)
          withFilledRC(rc, stateOpt, None)(filledRC => errorHandler.flatMap(_.lift((err, filledRC))).getOrElse(doError(routes.Application.index, msg)))
        else
          onUnauthorized(rc.request)
      }
    }     
  }

  /**
   * Convenience wrapper for withSpace -- use this for pages that are talking about
   * a specific Thing.
   */
  def withThing(requireLogin:Boolean, ownerId:String, spaceId:String, thingIdStr:String,
        errorHandler:Option[PartialFunction[(ThingFailed, RequestContext), Result]] = None) = { 
    withSpace(requireLogin, ownerId, spaceId, Some(thingIdStr), errorHandler) _
  }
  
  def index = withUser(false) { rc =>
    Ok(views.html.index(rc))
  }    
  
  // TODO: in the long run, we will want spidering of the main system pages, and allow users to
  // choose to have their pages indexed. We're quite a ways from that, though.
  def robots = Action { implicit request =>
    Ok("""# For the time being, Querki requests that robots stay out. This will change eventually.
        
user-agent: *
disallow: /
""").as("text/plain")
  }

  def spaces = withUser(true) { rc => 
    askSpaceMgr[ListMySpacesResponse](ListMySpaces(rc.requester.get.id)) { 
      case MySpaces(list) => Ok(views.html.spaces(rc, list))
    }
  }
    
  def space(ownerId:String, spaceId:String) = withSpace(false, ownerId, spaceId) { implicit rc =>
    Ok(views.html.thing(rc))
  }
  
  def thing(ownerId:String, spaceId:String, thingId:String) = withThing(false, ownerId, spaceId, thingId, Some({ 
    case (ThingFailed(UnknownName, _, stateOpt), rc) => {
      // We didn't find the requested Thing, so display a TagThing for it instead:
      val rcWithName = rc.copy(thing = Some(TagThing(thingId, stateOpt.get)))
      Ok(views.html.thing(rcWithName))
    }
  })) { implicit rc =>
    // Uncomment this to see details of the Thing we're displaying:
    //QLog.spewThing(rc.thing.getOrElse(rc.state.get))
    // rc now has all the interesting information copied into it:
    Ok(views.html.thing(rc))
  }
  
  def newSpace = withUser(true) { rc =>
    Ok(views.html.newSpace(rc))
  }
  
  def doNewSpace = withUser(true) { rc =>
    implicit val request = rc.request
    val requester = rc.requester.get
    newSpaceForm.bindFromRequest.fold(
      errors => doError(routes.Application.newSpace, "You have to specify a legal space name"),
      name => {
        if (NameProp.validate(name)) {
          askSpaceMgr[ThingResponse](CreateSpace(requester.id, name)) {
            case ThingFound(_, state) => Redirect(routes.Application.space(requester.name, state.toThingId))
            case ThingFailed(error, msg, stateOpt) => doError(routes.Application.newSpace, msg)
          }
        } else {
          doError(routes.Application.newSpace, "That's not a legal Space name")
        }
      }
    )
  }
  
  def getOtherProps(state:SpaceState, kind:Kind.Kind, existing:PropList):Seq[Property[_,_]] = {
    val existingProps = existing.keys                                                                                                  
    // This lists all of the visible properties that aren't in the existing list, and removes the
    // InternalProps:
    implicit val s = state
    val candidates = (state.allProps.values.toSet -- existingProps).toSeq.filterNot(_.ifSet(InternalProp))

    // TODO: sort alphabetically
    
    // Now, filter out ones that aren't applicable to the target Kind:
    val propsToShow = candidates filter { candidate =>
      // TODO: this pattern -- "if this QList property exists, then do something to each value" -- seems
      // common. Find the right factoring for it:
      if (candidate.hasProp(AppliesToKindProp)) {
        val allowedKinds = candidate.getPropVal(AppliesToKindProp).cv
        (false /: allowedKinds)((current, allowedKind) => current || (AppliesToKindProp.pType.get(allowedKind) == kind))
      } else {
        true
      }
    }
    
    propsToShow.sortBy(_.displayName)
  }

  def otherModels(state:SpaceState, mainModel:Thing):Iterable[Thing] = {
    mainModel.kind match {
      case Kind.Thing | Kind.Attachment => state.allModels
      // For the time being, we're only allowing basic Properties. We might allow Properties to
      // serve as Models, but one thing at a time.
      case Kind.Property => Seq(UrProp)
      case Kind.Space => Seq(SystemSpace.State)
      case _ => throw new Exception("Don't yet know how to create a Thing of kind " + mainModel.kind)
    }
  }
  
  def showEditPage(rc: RequestContext, model:Thing, props:PropList, errorMsg:Option[String] = None) = {
    val state = rc.state.get
    val propList = prepPropList(props, model, rc.state.get)
    try { 
      val page = views.html.editThing(
        rc.copy(error = errorMsg),
        model,
        otherModels(state, model),
        propList,
        getOtherProps(state, model.kind, props)
      )
      if (errorMsg.isDefined)
        BadRequest(page)
      else
        Ok(page)    
    } catch {
      case e:Error => Logger.error("Error while displaying Editor: " + e)
      // TODO: put a real error message here:
      BadRequest("Internal error!")
    }
  }
  
  def prepPropList(propList:PropList, model:Thing, state:SpaceState):Seq[(Property[_,_], DisplayPropVal)] = {
    val propsToEdit = model.getPropOpt(InstanceEditPropsProp)(state).map(_.rawList)
    propsToEdit match {
      // If the model specifies which properties we actually want to edit, then use just those, in that order:
      case Some(editList) => {
        val withOpts = (Seq.empty[(Property[_,_], Option[DisplayPropVal])] /: editList) { (list, oid) =>
          val propOpt = state.prop(oid)
          propOpt match {
            case Some(prop) => {
              val v = propList.get(prop)
              list :+ (prop, v)
            }
            case None => {
              Logger.warn("Was unable to find Property " + oid + " in prepPropList()")
              list
            }
          }
        }
        withOpts.filter(_._2.isDefined).map(pair => (pair._1, pair._2.get))
      }
      // Otherwise, we default to doing it by name:
      case None => propList.toList
    }
  }
  
  def createThing(ownerId:String, spaceId:String, modelIdOpt:Option[String]) = withSpace(true, ownerId, spaceId) { implicit rc =>
    implicit val state = rc.state.get
    val modelThingIdOpt = modelIdOpt map (ThingId(_))
    val modelOpt = modelThingIdOpt flatMap (rc.state.get.anything(_))
    val model = modelOpt getOrElse SimpleThing
    showEditPage(rc, model, PropList.inheritedProps(None, model))
  }
  
  def createProperty(ownerId:String, spaceId:String) = withSpace(true, ownerId, spaceId) { implicit rc =>
    showEditPage(
        rc, 
        UrProp,
        PropList.inheritedProps(None, UrProp)(rc.state.get))
  }
  
  def doCreateThing(ownerId:String, spaceId:String, subCreate:Option[Boolean]) = {
    editThingInternal(ownerId, spaceId, None, false)
  }
  
  def editThingInternal(ownerId:String, spaceId:String, thingIdStr:Option[String], partial:Boolean, 
      successCb:Option[(SpaceState, Thing, OID) => SimpleResult[_]] = None,
      errorCb:Option[(String, Thing, List[FormFieldInfo]) => SimpleResult[_]] = None) = 
  withSpace(true, ownerId, spaceId, thingIdStr) { implicit rc =>
    implicit val request = rc.request
    implicit val state = rc.state.get
    val user = rc.requester.get
    val rawForm = newThingForm.bindFromRequest
    
    rawForm.fold(
      // TODO: What can cause this?
      errors => doError(routes.Application.space(ownerId, spaceId), "Something went wrong"),
      info => {
        val context = QLRequestContext(rc)
    
        // Whether we're creating or editing depends on whether thing is specified:
        val thing = rc.thing
        val rawProps = info.fields map { propIdStr => 
          val propId = OID(propIdStr)
          val propOpt = state.prop(propId)
          propOpt match {
            case Some(prop) => {
              HtmlRenderer.propValFromUser(prop, thing, rawForm, context)              
            }
            // TODO: this means that an unknown property was specified. We should think about the right error path here:
            case None => FormFieldInfo(UrProp, None, true, false)
          }
        }
        val oldModel =
          if (info.model.length() > 0)
            state.anything(OID(info.model)).get
          else
            thing.map(_.getModel).getOrElse(throw new Exception("Trying to edit thing, but we don't know the model!"))
        
        val kind = oldModel.kind
        
        def formFlag(fieldName:String):Boolean = {
          rawForm(fieldName).value.map(_ == "true").getOrElse(false)
        }
        
        val makeAnother = formFlag("makeAnother")        
        val fromAPI = formFlag("API")
        
        def makeProps(propList:List[FormFieldInfo]):PropList = {
          val modelProps = PropList.inheritedProps(thing, oldModel)
          val nonEmpty = propList filterNot (_.isEmpty)
          (modelProps /: nonEmpty) { (m, fieldInfo) =>
            val prop = fieldInfo.prop
            val disp =
              if (m.contains(prop))
                m(prop).copy(v = fieldInfo.value)
              else
                DisplayPropVal(thing, prop, fieldInfo.value)
            m + (prop -> disp)              
          }
        }
        
        val redisplayStr = rawForm("redisplay").value.getOrElse("")
        
        if (redisplayStr.length() > 0 && redisplayStr.toBoolean) {
          showEditPage(rc, oldModel, makeProps(rawProps))
//        } else if (info.newModel.length > 0) {
//          // User is changing models. Replace the empty Properties with ones
//          // appropriate to the new model, and continue:
//          // TODO: for now, there is no way to get to here -- it's an annoying edge case,
//          // and mucking up the UI. Later, let's reintroduce a less-intrusive way to
//          // invoke this.
//          val model = state.anything(OID(info.newModel)).get
//          showEditPage(rc, model, makeProps(rawProps))
        } else {
          // User has submitted a creation/change. Is it legal?
          val filledProps = rawProps.filterNot(_.isEmpty)
          val illegalVals = filledProps.filterNot(_.isValid)
          if (illegalVals.isEmpty) {
            // Everything parses, anyway, so send it on to the Space for processing:
            val propPairs = filledProps.map { pair =>
              val FormFieldInfo(prop, value, _, _) = pair
              (prop.id, value.get)
            }
            val props = Thing.toProps(propPairs:_*)()
            val spaceMsg = if (thing.isDefined) {
              if (partial || oldModel.hasProp(OIDs.InstanceEditPropsOID)) {
                // Editing an instance, so we only have a subset of the props, not the full list.
                // NOTE: the reason this is separated from the next clause is because ChangeProps gives us
                // no way to *delete* a property. We don't normally expect to do that in a limited-edit instance,
                // but we certainly need to be able to in the general case.
                // TODO: refactor these two clauses together. ModifyThing is inappropriate when InstanceEditProps is
                // set, because it expects to be editing the *complete* list of properties, not just a subset.
                // The right answer is probably to enhance the PropMap to be able to describe a deleted property,
                // possibly with a constant pseudo-PropValue. Then have the editor keep track of the deleted
                // properties, and send the deletions as a pro-active change when finished.
                ChangeProps(user, rc.ownerId, ThingId(spaceId), thing.get.id.toThingId, props)
              } else {
                // Editing an existing Thing. If InstanceEditPropsOID isn't set, we're doing a full edit, and
                // expect to have received the full list of properties.
                ModifyThing(user, rc.ownerId, ThingId(spaceId), thing.get.id.toThingId, OID(info.model), props)
              }
            } else {
              // Creating a new Thing
              CreateThing(user, rc.ownerId, ThingId(spaceId), kind, OID(info.model), props)
            }
        
            askSpaceMgr[ThingResponse](spaceMsg) {
              case ThingFound(thingId, newState) => {
                // TODO: the default pathway described here really ought to be in the not-yet-used callback, and get it out of here.
                // Indeed, all of this should be refactored:
                successCb.map(cb => cb(newState, oldModel, thingId)).getOrElse {
                  val thing = newState.anything(thingId).get
                  
                  if (makeAnother)
                    showEditPage(rc.copy(state = Some(newState)), oldModel, PropList.inheritedProps(None, oldModel)(newState))
                  else if (rc.isTrue("subCreate")) {
                    Ok(views.html.subCreate(rc, thing));
                  } else if (fromAPI) {
                    // In the AJAX case, we just send back the OID of the Thing:
                    Ok(thingId.toThingId.toString)
                  } else {
                    Redirect(routes.Application.thing(ownerName(newState), newState.toThingId, thing.toThingId))
                  }
                }
              }
              case ThingFailed(error, msg, stateOpt) => {
                errorCb.map(cb => cb(msg, oldModel, rawProps)).getOrElse {
                  if (fromAPI) {
                    NotAcceptable(msg)
                  } else {
                    showEditPage(rc, oldModel, makeProps(rawProps), Some(msg))
                  }
                }
              }
            }
          } else {
            // One or more values didn't parse against their PTypes. Give an error and continue:
            val badProps = illegalVals.map { info => info.prop.displayName }
            val errorMsg = "Illegal values for " + badProps.mkString
            errorCb.map(cb => cb(errorMsg, oldModel, rawProps)).getOrElse {
              if (fromAPI) {
                NotAcceptable(errorMsg)
              } else {
                showEditPage(rc, oldModel, makeProps(rawProps), Some(errorMsg))
              }
            }
          }
        }
      }      
    )
  }
  
  // TODO: this doesn't feel like it belongs here at all. Think about refactoring. Indeed, there
  // may well be a Tags Module fighting to break out...
  def preferredModelForTag(implicit state:SpaceState, nameIn:String):Thing = {
    val tagProps = state.propsOfType(TagSetType).filter(_.hasProp(OIDs.LinkModelOID))
    val name = NameType.canonicalize(nameIn)
    if (tagProps.isEmpty)
      SimpleThing
    else {
      val candidates = state.allThings.toSeq
    
      // Find the first Tag Set property (if any) that is being used with this Tag:
      val tagPropOpt = tagProps.find { prop =>
        val definingThingOpt = candidates.find { thing =>
          val propValOpt = thing.getPropOpt(prop)
          propValOpt.map(_.contains(name)).getOrElse(false)
        }
        definingThingOpt.isDefined
      }
      
      // Get the Link Model for that property:
      val modelOpt = 
        for (
          tagProp <- tagPropOpt;
          linkModelPropVal <- tagProp.getPropOpt(LinkModelProp);
          modelId = linkModelPropVal.first;
          model <- state.anything(modelId)
          )
          yield model

      modelOpt.getOrElse(SimpleThing)
    }
  }
  
  def editThing(ownerId:String, spaceId:String, thingIdStr:String) = withSpace(true, ownerId, spaceId, Some(thingIdStr), Some({ 
    // TODO: can/should we refactor this out to a new Tags Module?
    case (ThingFailed(UnknownName, _, stateOpt), rc) => {
      // We didn't find the requested Thing, so we're essentially doing a variant of Create instead.
      // This happens normally when you "Edit" a Tag.
      implicit val state = rc.state.get
      // If this Tag is used in a Tag Set Property with a Link Model, use that:
      val model = preferredModelForTag(state, thingIdStr)
      val name = NameType.toDisplay(thingIdStr)
      val defaultText = state.getPropOpt(ShowUnknownProp).map(_.v).getOrElse(ExactlyOne(LargeTextType(TagThing.defaultDisplayText)))
      showEditPage(rc, model, 
          PropList.inheritedProps(None, model) ++
          PropList((NameProp -> DisplayPropVal(None, NameProp, Some(ExactlyOne(NameType(name))))),
                   (DisplayTextProp -> DisplayPropVal(None, DisplayTextProp, Some(defaultText)))))
    }
  })) { implicit rc =>
    implicit val state = rc.state.get
    val thing = rc.thing.get
    // TODO: security check that I'm allowed to edit this
	val model = thing.getModel
	showEditPage(rc, model, PropList.from(thing))
  }
  
  def doEditThing(ownerId:String, spaceId:String, thingIdStr:String) = editThingInternal(ownerId, spaceId, Some(thingIdStr), false)

  // TODO: this should really have its own security property, Can View Source, which should default to Can Read. But for now,
  // we'll make do with Can Read, which is implicit in withThing:
  def viewThing(ownerId:String, spaceId:String, thingIdStr:String) = withThing(false, ownerId, spaceId, thingIdStr) { implicit rc =>
    Ok(views.html.viewSource(rc))
  }
  
  /**
   * This is the AJAX-style call to change a single property value. As of this writing, I expect it to become
   * the dominant style before too long.
   * 
   * TODO: note that I've had to turn off requireLogin. That's mainly because the definition of "login" used for it is
   * too stringent. We need to rewrite withSpace/withThing to cope with the late-parsed SpaceSpecificUser:
   * 
   * DEPRECATED. Can we remove this?
   */
  def setProperty(ownerId:String, spaceId:String, thingId:String, propId:String, newVal:String) = withThing(false, ownerId, spaceId, thingId) { implicit rc =>
    rc.requester match {
      case Some(requester) => {
	    val thing = rc.thing.get
	    val propOpt = rc.state.get.prop(ThingId(propId))
	    propOpt match {
	      case Some(prop) => {
	        val request = ChangeProps(requester, rc.ownerId, rc.state.get.id, thing.id, Thing.toProps(prop.id -> HtmlRenderer.propValFromUser(prop, newVal))())
	        askSpaceMgr[ThingResponse](request) {
	          case ThingFound(thingId, state) => {
	            Ok("Successfully changed to " + newVal)
	          }
	          case ThingFailed(error, msg, stateOpt) => {
	            Logger.info("Failed to change property: " + msg); Ok("Failed to change property: " + msg)
	          }
	        }
	      }
	      case None => {
	        Ok("Unknown property " + propId)
	      }
	    }        
      }
      
      case _ => Ok("You aren't logged in, so you can't change anything")
    }
  }

  /**
   * This is essentially the interactive version of doEditThing. It expects to be called from AJAX, with a form that
   * has been constructed to look like the traditional Editor window.
   */
  def setProperty2(ownerId:String, spaceId:String, thingId:String) = {
    editThingInternal(ownerId, spaceId, Some(thingId), true)
  }
  
  /**
   * Fetch the value of a single property, in HTML-displayable form, via AJAX.
   * 
   * This is used by the Editor to get the display text for properties, but is likely to be broadly useful in the long run.
   * 
   * Note that canRead is applied automatically for the Thing, up in withSpace().
   */
  def getPropertyDisplay(ownerId:String, spaceId:String, thingId:String, prop:String) = withThing(true, ownerId, spaceId, thingId) { implicit rc =>
    val resultOpt = for (
      thing <- rc.thing;
      prop <- rc.prop;
      wikitext = thing.render(rc, Some(prop))
        )
      yield wikitext.display.toString
    
    Ok(resultOpt.getOrElse("Couldn't find that property value!"))
  }
  
  /**
   * Fetch the standard property-editor HTML for the specified property.
   */
  def getPropertyEditor(ownerId:String, spaceId:String, thingId:String, prop:String, i:Int) = withSpace(true, ownerId, spaceId, if (thingId.length() > 0) Some(thingId) else None) 
  { implicit rc =>
    val resultOpt = for {
      prop <- rc.prop;
      propVal = DisplayPropVal(rc.thing, prop, None)
    }
      yield views.html.showPropertyTemplate(rc, prop, propVal, i).toString.trim()
      
    Ok(resultOpt.getOrElse("Couldn't create that property editor!"))
  }

  /**
   * AJAX call to fetch the existing tag values for the specified property.
   */
  def getTags(ownerId:String, spaceId:String, propId:String, q:String) = withSpace(true, ownerId, spaceId) { implicit rc =>
    implicit val space = rc.state.get
    val lowerQ = q.toLowerCase()
    val tagsOpt = for
      (
        prop <- space.prop(ThingId(propId))
      )
        yield TagsForPropertyMethod.fetchTags(space, prop).filter(_.toLowerCase().contains(lowerQ))
        
    val tagsSorted = tagsOpt.map(tags => tags.toList.sorted)
    val thingsSorted = space.allThings.toSeq.map(_.displayName).filter(_.toLowerCase().contains(lowerQ)).sorted
        
    val tagsAndThings = 
      tagsSorted match {
        case Some(tags) => tags ++ (thingsSorted.toList.diff(tags))
        case None => thingsSorted
      }
        
    // TODO: introduce better JSONification for the AJAX code:
    val JSONtags = "[" + tagsAndThings.map(name => "{\"display\":\"" + name + "\", \"id\":\"" + name + "\"}").mkString(",") + "]"
    Ok(JSONtags)
  }
  
  def getLinksFromSpace(spaceIn:SpaceState, prop:Property[_,_], lowerQ:String):Seq[(String,OID)] = {
    implicit val space = spaceIn
    
    val thingsSorted = {
      val allThings = space.allThings.toSeq
    
      val linkModelPropOpt = prop.getPropOpt(LinkModelProp)
      val thingsFiltered =
        linkModelPropOpt match {
          case Some(propAndVal) => {
            val targetModel = propAndVal.first
            allThings.filter(_.isAncestor(targetModel))
          }
          case None => allThings
        }
    
      thingsFiltered.map(t => (t.displayName, t.id)).filter(_._1.toLowerCase().contains(lowerQ)).sortBy(_._1)
    }
    
    space.app match {
      case Some(app) => thingsSorted ++ getLinksFromSpace(app, prop, lowerQ)
      case None => thingsSorted
    }
  }

  def getLinks(ownerId:String, spaceId:String, propId:String, q:String) = withSpace(true, ownerId, spaceId) { implicit rc =>
    val lowerQ = q.toLowerCase()
    val space = rc.state.get
    val propOpt = space.prop(ThingId(propId))
    
    val results = propOpt match {
      case Some(prop) => getLinksFromSpace(space, prop, lowerQ)
      case None => Seq.empty
    } 
    
    // TODO: introduce better JSONification for the AJAX code:
    val items = results.map(item => "{\"display\":\"" + item._1 + "\", \"id\":\"" + item._2 + "\"}")
    val JSONtags = "[" + items.mkString(",") + "]"
    Ok(JSONtags)
  }

  def upload(ownerId:String, spaceId:String) = withSpace(true, ownerId, spaceId) { implicit rc =>
    Ok(views.html.upload(rc))
  }
  
  // TODO: I think this function is the straw that breaks the camel's back when it comes to code structure.
  // It has too many nested levels, and they're only going to get worse. It needs a big rewrite.
  //
  // A better solution is probably to use 2.10's Try monad pretty rigorously. Say that askSpaceMgr returns
  // a monadic response, that composes by calling the next step in the chain. If you get ThingFound, we
  // continue; if you get ThingFailed, we fail the Try with an error. Done right, and all of this can
  // become a nice for() statement, and will be much easier to reason about in the long run. That will
  // have to wait until we have 2.10 going, though.
  //
  // TODO: generalize upload. You should be able to select the desired file type from a whitelist,
  // and upload that type properly. There should be type-specific validation -- for instance, CSS should
  // be Javascript-scrubbed. There should be type-specific post-processing -- for instance, photos
  // should automatically generate thumbnails.
  //
  // TODO: limit the size of the uploaded file!!!
  def doUpload(ownerId:String, spaceId:String) = withSpace(true, ownerId, spaceId) { implicit rc =>
    implicit val state = rc.state.get
    val user = rc.requester.get
    rc.request.body.asMultipartFormData flatMap(_.file("uploadedFile")) map { filePart =>
      val filename = filePart.filename
	  val tempfile = filePart.ref.file
	  // TBD: Note that this codec forces everything to be treated as pure-binary. That's
	  // because we are trying to be consistent about attachments. Not clear whether
	  // that's the right approach in the long run, though.
	  val source = io.Source.fromFile(tempfile)(scala.io.Codec.ISO8859)
	  val contents = try {
	    // TODO: is there any way to get the damned file into the DB without copying it
	    // through memory like this?
	    source.map(_.toByte).toArray
	  } finally {
	    source.close()
	  }
	  val attachProps = Thing.toProps(DisplayNameProp(filename))()
	  askSpaceMgr[ThingResponse](
	    CreateAttachment(user, rc.ownerId, state.id.toThingId, contents, MIMEType.JPEG, contents.size, OIDs.PhotoBaseOID, attachProps)) {
	    case ThingFound(attachmentId, state2) => {
	      Redirect(routes.Application.thing(ownerId, state.toThingId, attachmentId.toThingId))
	    }
	    case ThingFailed(error, msg, stateOpt) => {
          doError(routes.Application.upload(ownerId, spaceId), msg)
	    }
	  }
	} getOrElse {
      doError(routes.Application.upload(ownerId, spaceId), "You didn't specify a file")
	}
  }

  // TODO: this is broken from a security POV. It should be rewritten in terms of GetSpace!
  def attachment(ownerIdStr:String, spaceId:String, thingIdStr:String) = withUser(false) { rc =>
    val ownerId = getUserByThingId(ownerIdStr)
    askSpaceMgr[AttachmentResponse](GetAttachment(rc.requesterOrAnon, ownerId, ThingId(spaceId), ThingId(thingIdStr))) {
      case AttachmentContents(id, size, mime, content) => {
        Ok(content).as(mime)
      }
      case AttachmentFailed() => BadRequest
    }     
  }
  
  def exportThing(ownerId:String, spaceId:String, thingIdStr:String) = withSpace(true, ownerId, spaceId, Some(thingIdStr)) { implicit rc =>
    implicit val state = rc.state.get
    val thing = rc.thing.get
    // TODO: security check that I'm allowed to export this
    val export = thing.export
    Ok(export).as(MIMEType.JSON)
  }

  def userByName(userName:String) = {
    // TBD: Note that, for now at least, this simply returns my own spaces. I'm leery about
    // allowing people to see each other's spaces too casually.
    // TBD: is this the appropriate response to, say, "http://querki.net/jducoeur/"? Or
    // should this show the profile page instead?
    withUser(true) { rc => 
      askSpaceMgr[ListMySpacesResponse](ListMySpaces(rc.requester.get.id)) { 
        case MySpaces(list) => Ok(views.html.spaces(rc, list))
      }
    }    
  }

  // TODO: that onUnauthorized will infinite-loop if it's ever invoked. What should we do instead?
  def login = 
    Security.Authenticated(forceUser, onUnauthorized) { user =>
      Action { implicit request =>
        if (user == User.Anonymous)
          Ok(views.html.login(RequestContext(request, None, UnknownOID, None, None)))
        else
          Redirect(routes.Application.index) 
      }
    }
  
  def dologin = Action { implicit request =>
    val rc = RequestContext(request, None, UnknownOID, None, None)
    userForm.bindFromRequest.fold(
      errors => doError(routes.Application.login, "I didn't understand that"),
      form => {
        val userOpt = User.checkLogin(form.name, form.password)
        userOpt match {
          case Some(user) => Redirect(routes.Application.index).withSession(user.toSession:_*)
          case None => doError(routes.Application.login, "I don't know who you are")
        }
      }
    )
  }
  
  def logout = Action {
    Redirect(routes.Application.index).withNewSession
  }
  
  /**
   * A tiny test function, to see if I understand how to use Ajax calls:
   */
  def testAjax(i1:String, i2:String) = Action { implicit request =>
    Logger.info(s"Got test values $i1 and $i2")
    try {
      val result = i1.toInt + i2.toInt
      Ok(result.toString)
    } catch {
      case e:Exception => Logger.info("Got an error while testing Ajax -- probably a badly formed number"); Ok("")
    }
  }
  
  def showTestAjax = withUser(false) { rc =>
    Ok(views.html.testAjax(rc))
  }
  
  def javascriptRoutes = Action { implicit request =>
    import routes.javascript._
    Ok(
      Routes.javascriptRouter("jsRoutes")(
        routes.javascript.Application.testAjax,
        routes.javascript.Application.setProperty,
        routes.javascript.Application.setProperty2,
        routes.javascript.Application.getPropertyDisplay,
        routes.javascript.Application.getPropertyEditor,
        routes.javascript.Application.doCreateThing
      )
    ).as("text/javascript")
  }
}