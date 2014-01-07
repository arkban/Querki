package querki.ecology

import models.{Collection, OID, OIDMap, Property, PType, PTypeBuilder, ThingState}

import querki.values.SpaceState

/**
 * Represents a "plug-in" part of the system.
 * 
 * An Ecot is a collection of Properties, Things, Listeners and (typically) some code to
 * integrate with specialized libraries, which adds a particular kind of capability to
 * Querki.
 * 
 * This is the base class that should usually be used to instantiate Ecots in Querki. It
 * adds a large number of convenience methods that shadow types and calls that get used
 * frequently, to reduce the number of boilerplate imports.
 * 
 * =================
 * Building an Ecot
 * =================
 * 
 * An Ecot will typically includes a number of Thing definitions -- these *must* be declared
 * as lazy vals, and referenced in an overridden types, props or things (as appropriate).
 * 
 * And init-time dependencies that this Ecot requires (including Properties used in the
 * constructors of those Things) must be declared as *NON*-lazy vals using initRequires().
 */
abstract class QuerkiEcot(ecologyIn:Ecology) extends Ecot {
  
  // Note that this cannot, sadly, be a val, because it is needed in Ecot's constructor:
  implicit def ecology = ecologyIn
  
  /**
   * The PTypes introduced by this Module, if any.
   */
  lazy val types:Seq[PType[_]] = Seq.empty
  /**
   * The Properties introduced by this Module, if any.
   */
  lazy val props:Seq[Property[_,_]] = Seq.empty
  /**
   * The Things (usually Models, but not necessarily) introduced by this Module, if any.
   */
  lazy val things:Seq[ThingState] = Seq.empty
  
  /**
   * If the Module requires any specialized Things, Properties, Types or Collections,
   * add them to the state here. The returned state should be the passed-in one plus
   * the Module's stuff. (Note that the passed-in state is the System Space -- you are
   * adding stuff to System.)
   * 
   * Individual Modules should not usually need to override this; instead, just define
   * your objects in the types, props and things properties, and they will be added
   * automatically.
   */
  def addSystemObjects(state:SpaceState):SpaceState = {
    val result = state.copy(
      spaceProps = OIDMap[Property[_,_]](props:_*) ++: state.spaceProps, 
      things = OIDMap[ThingState](things:_*) ++: state.things,
      types = OIDMap[PType[_]](types:_*) ++: state.types)
    
    result
  }
  
  /* ************************************************************
   * Convenience aliases
   * 
   * All of the following are basic sugar, to reduce the number of imports required in Ecots.
   * You are allowed and encouraged to use them freely, when appropriate.
   * 
   * Yes, this list is intentionally a bit long. But it MUST NOT include anything in an Interface:
   * it is a big collection of very-commonly-used types and OIDs.
   * 
   * Do NOT add things here too casually -- it introduces a lot of coupling. It should be used
   * only in cases that have proven to be very commonly used already.
   * ************************************************************/
  
  // Common Collections:
  val ExactlyOne = models.system.ExactlyOne
  val Optional = models.system.Optional
  val QList = models.system.QList
  val QSet = models.system.QSet
  
  // Common Types:
  val IntType = models.system.IntType
  val LargeTextType = models.system.LargeTextType
  val LinkType = models.system.LinkType
  val QLType = models.system.QLType
  val TextType = models.system.TextType
  val YesNoType = models.system.YesNoType

  // Common Property constructors, so they can be used in Thing declarations without introducing init
  // dependencies:
  def Summary(text:String) = (querki.conventions.MOIDs.PropSummaryOID -> ExactlyOne(TextType(text)))
  def Details(text:String) = (querki.conventions.MOIDs.PropDetailsOID -> ExactlyOne(LargeTextType(text)))
  def AppliesToKindProp(kind:Int) = (models.system.OIDs.AppliesToKindOID -> QList(IntType(kind)))
  def NotInherited = (querki.core.MOIDs.NotInheritedOID -> ExactlyOne(YesNoType(true)))
  
  lazy val SkillLevelBasic = querki.identity.skilllevel.MOIDs.SkillLevelBasicOID
  lazy val SkillLevelStandard = querki.identity.skilllevel.MOIDs.SkillLevelStandardOID
  lazy val SkillLevelAdvanced = querki.identity.skilllevel.MOIDs.SkillLevelAdvancedOID
  def SkillLevel(level:OID) = (querki.identity.skilllevel.MOIDs.SkillLevelPropOID -> ExactlyOne(LinkType(level)))
  
  // The standard convenience sugar for defining a Property in an Ecot:
  class SystemProperty[VT, -RT](pid:OID, t:PType[VT] with PTypeBuilder[VT, RT], c:Collection, p:models.Thing.PropFetcher) 
    extends Property[VT, RT](pid, models.system.OIDs.systemOID, querki.core.MOIDs.UrPropOID, t, c, p, querki.time.epoch)
}
