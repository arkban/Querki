package querki.api

import scala.concurrent.Future

import models.Wikitext
import querki.data.ThingInfo

trait CommonFunctions {
  import CommonFunctions._
  
  /**
   * Pro-actively fetches the "standard" Things (from System Space) that the Client cares about.
   * This list is pretty ad-hoc, but it's useful to get them in advance.
   *
   * Note that, on the Client, this is actually exposed as the StandardThings structure. But that
   * gets serialized as a Map for going across the wire.
   */
  def getStandardThings():Future[Map[String, ThingInfo]]
  
  /**
   * Check on the progress of a long-running Operation.
   * 
   * Some functions return an OperationHandle. In those cases, the Client should periodically call
   * getProgress() to see how it's coming. When OperationProgress returns with complete set to true,
   * the Client must call acknowledgeComplete() to say that it knows everything has finished properly.
   */
  def getProgress(handle:OperationHandle):Future[OperationProgress]
  
  def acknowledgeComplete(handle:OperationHandle):Unit
  
  /**
   * Fetch the current Terms of Service.
   */
  def fetchTOS():Future[TOSInfo]
}

object CommonFunctions {
  
  /**
   * Describes the current Terms of Service.
   */
  case class TOSInfo(version:Int, text:Wikitext)

}

/**
 * The handle that API entry points can return for long-running operations. The client must then
 * call CommonFunctions.checkProgress() using that handle periodically.
 */
sealed trait OperationHandle
case class ActorOperationHandle(path:String) extends OperationHandle

/**
 * A description of the current state of a long-running operation.
 */
case class OperationProgress(msg:String, percent:Int, complete:Boolean, failed:Boolean)

trait PassthroughHandlerBase {
  def pass(name:String):ThingInfo
}

/**
 * This is tricky magic. PassthroughHandler is defined completely differently on the Server
 * and Client, so that the Server mashalls and the Client unmarshalls from the same calls.
 *
 * IMPORTANT: when you add a new value here, you should replace the definition of the name
 * in its server-side definition with a call to commonName() instead, rather than duplicating
 * the strings!
 */
class StandardThings(h:PassthroughHandlerBase) {
  
  object core {
    val exactlyOneColl = h.pass("Required")
    val optionalColl = h.pass("Optional")
    val listColl = h.pass("List")
    val setColl = h.pass("Set")
    
    val linkType = h.pass("Thing Type")
    val tagType = h.pass("Tag Type")
  
    val urProp = h.pass("Property")
    val nameProp = h.pass("Link Name")
    val collectionProp = h.pass("Property Collection")
    val typeProp = h.pass("Property Type")
    val isModelProp = h.pass("Is a Model")
  }
  
  object apps {
    val canUseAsAppPerm = h.pass("Who Can Use as an App")
    val canManipulateAppsPerm = h.pass("Who Can Manipulate Apps")
    
    val summaryProp = h.pass("_Gallery Space Summary")
    val detailsProp = h.pass("_Gallery Space Details")
  }
  
  object basic {
    val simpleThing = h.pass("Simple-Thing")
    val displayNameProp = h.pass("Name")
    val defaultView = h.pass("Default View")
    val printView = h.pass("Print View")
  }

  object conventions {
    val detailsProp = h.pass("Details")
    val summaryProp = h.pass("Summary")  
  }

  object conversations {
    val commentNotifyProp = h.pass("_getCommentNotifications")
  }

  object css {
    val stylesheet = h.pass("Stylesheet")
  }

  object editing {
    val instancePropsProp = h.pass("Instance Properties")
  }
  
  object links {
    val linkModelProp = h.pass("Restrict to Model")
  }
  
  object publication {
    val publishableProp = h.pass("Is a Publishable Model")
    val publishedProp = h.pass("_publishedInstance")
    val canPublishPerm = h.pass("Who Can Publish")
    val hasUnpublishedChangesProp = h.pass("_hasUnpublishedChanges")
    val publishNotesProp = h.pass("_publishNotes")
    val spaceHasPublicationsProp = h.pass("_spaceHasPublications")
    val canReadAfterPublishPerm = h.pass("Who Can Read After Publication")
  }
  
  object roles {
    val canExplorePerm = h.pass("Who Can Explore")
    val canManageSecurityPerm = h.pass("Who Can Manage Security")
  }
  
  object security {
    val canReadPerm = h.pass("Who Can Read")
    val canCreatePerm = h.pass("Who Can Create")
    val canDesignPerm = h.pass("Who Can Design")
    val canManageDataPerm = h.pass("Who Can Manage Data")
    val customRoleModel = h.pass("_customRoleModel")
    val sharedInviteModel = h.pass("_sharedInviteModel")
    val inviteTextProp = h.pass("Space Invitation Text")
    val personRolesProp = h.pass("Person Roles")
    val rolePermissionsProp = h.pass("Role Permissions")
    val inviteRoleLink = h.pass("_inviteRoleLink")
    val inviteRequiresMembership = h.pass("_inviteRequiresMembership")
    val isOpenInvite = h.pass("_isOpenInvitation")
    
    val appliesToSpace = h.pass("_Applies To Space")
    val appliesToModels = h.pass("_Applies To Models")
    val appliesToInstances = h.pass("_Applies To Instances")
    
    val owner = h.pass("Owner")
    val members = h.pass("Members")
    val public = h.pass("Public")
  }
  
  object skillLevel {
    val skillLevelEasy = h.pass("Skill Level Easy")
    val skillLevelStandard = h.pass("Skill Level Standard")
    val skillLevelAdvanced = h.pass("Skill Level Advanced")
  }
  
  object tags {
    val isReifiedTag = h.pass("_Is Reified Tag")
  }
  
  object types {
    val deriveNameProp = h.pass("_deriveName")
    val deriveAlways = h.pass("Always Derive Name")
    val deriveNever = h.pass("Never Derive Name")
  }
  
  // This is necessary in order to force the objects to come into being. Each of the
  // above objects must be named here:
  def touchEverything() = {
    Seq(apps, basic, conventions, conversations, core, css, editing, links, publication, roles, security, skillLevel, tags, types)
  }
}
