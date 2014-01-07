package models

import scala.xml._

import play.api.templates.Html

import Thing._

import ql._

import querki.ecology.Ecology
import querki.values._

/**
 * Properties have Types. There's nothing controversial here -- Types are usually
 * things like Text, Number and so on. But note that Types are themselves Things;
 * this is specifically so that we can potentially add user-defined Types down
 * the road.
 */
abstract class PType[VT](i:OID, s:OID, m:OID, pf:PropFetcher)(implicit e:Ecology) extends Thing(i, s, m, Kind.Type, pf, querki.time.epoch)(e) {
  
  type valType = VT

  /**
   * Each PType is required to implement this -- it is the deserializer for the
   * type.
   */
  def doDeserialize(ser:String):VT
  final def deserialize(ser:String):ElemValue = ElemValue(doDeserialize(ser), this)
  
  /**
   * Also required for all PTypes, to serialize values of this type.
   */
  def doSerialize(v:VT):String
  final def serialize(v:ElemValue):String = doSerialize(get(v))
  
  /**
   * Takes a value of this type, and turns it into displayable form. Querki
   * equivalent to toString.
   */
  def doWikify(context:QLContext)(v:VT, displayOpt:Option[Wikitext] = None):Wikitext
  /**
   * Take a value of this type and turn it into a Wikitext. Formerly called "render", but that conflicts
   * in weird signature ways with Thing.render. (It appears that you can't have multiple overloads with
   * default values, even if the previous parameters differentiate between the overloads.)
   */
  final def wikify(context:QLContext)(v:ElemValue, displayOpt:Option[Wikitext] = None):Wikitext = doWikify(context)(get(v), displayOpt)
  
  /**
   * Takes a value of this type, and renders it for showing in debug messages.
   */
  def doDebugRender(context:QLContext)(v:VT) = v.toString
  final def debugRender(context:QLContext)(v:ElemValue):String = doDebugRender(context)(get(v))
  
  /**
   * Also required for all PTypes -- the default value to fall back on.
   */
  def doDefault:VT
  final def default:ElemValue = ElemValue(doDefault, this)
  
  /**
   * Turns this value into an appropriate form for user editing. Currently that means
   * a String, although that's likely to get more interesting soon.
   */
  def doToUser(v:VT):String = doSerialize(v)
  final def toUser(v:ElemValue):String = doToUser(get(v))
  
  /**
   * This compares two values. It is used to sort Collections.
   */
  def doComp(context:QLContext)(left:VT, right:VT):Boolean = { math.Ordering.String.lt(doToUser(left), doToUser(right)) } 
  final def comp(context:QLContext)(left:ElemValue, right:ElemValue):Boolean = doComp(context)(get(left), get(right))
  
  /**
   * The type unwrapper -- takes an opaque ElemValue and returns the underlying value.
   * This is a fundamentally unsafe operation, so it should always be performed in the
   * context of a Property.
   */
  def get(v:ElemValue):VT = v.get(this)
  
  /**
   * Can this String value be legitimately interpreted as this type? This either passes, or
   * throws an Exception, preferably a useful PublicException.
   * 
   * This is closely related to doFromUser -- iff something can be parsed by doFromUser, it
   * should validate cleanly. It is intended for UI use.
   * 
   * Types may override this, especially if their validation is related to the Property.
   * (Typically because they interact with other meta-Properties on this one.)
   * 
   * IMPORTANT: this can throw Exceptions, and specifically PublicExceptions! Calls must be
   * wrapped in a Tryer!
   */
  def validate(v:String, prop:Property[_,_], state:SpaceState):Unit = {
    doFromUser(v)
  }
  
  /**
   * Display the appropriate input control for this type, with the default value set as specified.
   * 
   * All Types that can be user-input should define this.
   * 
   * TBD: in practice, I don't love this. The coupling is roughly correct, but it winds up mixing
   * HTML-specific code into a very primitive level of the system. There should probably instead be
   * side classes for each PType, which describe how to render them in particular circumstances. But
   * we'll get to that...
   */
  def renderInputXml(prop:Property[_,_], state:SpaceState, currentValue:DisplayPropVal, v:ElemValue):scala.xml.Elem
  def renderInput(prop:Property[_,_], state:SpaceState, currentValue:DisplayPropVal, v:ElemValue):Elem = {
    renderInputXml(prop, state, currentValue, v)
  }
  
  /**
   * Parses form input from the user. By default, we assume that this is the same
   * as deserialization, but override this when that's not true.
   * 
   * This should throw an Exception if the input is not legal. This is used in
   * validation.
   */
  protected def doFromUser(str:String):VT = doDeserialize(str)
  final def fromUser(str:String):ElemValue = ElemValue(doFromUser(str), this)
  
  /**
   * If this Type implies special processing when named in a QL expression (other than simply returning
   * the value of the property), override this method
   * The guts of applying a QL function. Note that this allows two contexts, which is often relevant
   * for these:
   * 
   *   incomingContext -> definingContext.prop(params)
   *   
   * If this isn't partially applied, the incomingContext is used for both. See Property for the main
   * usage of this.
   */
  def qlApplyFromProp(definingContext:QLContext, incomingContext:QLContext, prop:Property[VT,_], params:Option[Seq[QLPhrase]]):Option[QValue] = None
  
  /**
   * Iff defined, this Type must *always* be used with the specified Collection.
   * 
   * This is mostly intended for use with Type Aliases.
   */
  def requiredColl:Option[Collection] = None
  
  /**
   * Types can override this to provide default renderings when you look at a Property of this Type.
   */
  def renderProperty(prop:Property[_,_])(implicit request:RequestContext):Option[Wikitext] = None
  
  /**
   * The PType-math version of ==; this is here so that specific PTypes can override it.
   */
  def matches(left:ElemValue, right:ElemValue):Boolean = {
    doMatches(get(left), get(right))
  }
  def doMatches(left:VT, right:VT):Boolean = {
    left == right
  }
  
  /**
   * The PType that underlies this one. Mainly here to support the DelegatingType mechanism.
   */
  lazy val realType:PType[VT] = this
  
  /**
   * The usual width to show for this Type, with this Property, in Bootstrap's base-12 terms.
   * 
   * Defaults to 6, but any editable Type really ought to set this.
   */
  def editorSpan(prop:Property[_,_]):Int = 6
}

/**
 * Late-resolving PType, used for those occasional cases that need late references to break circular cycles at
 * the start of time. This should be used *very* sparingly! This basically wraps around a real PType, but
 * late-resolves everything.
 * 
 * TBD: is this good enough? Do I need to deal with the fields in the signature as well? Could be painful if so --
 * might have to refactor all the way down to Thing, to make those constructor fields into trait fields instead.
 */
class DelegatingType[VT](resolver: => PType[VT])(implicit e:Ecology = querki.ecology.Ecology) extends PType[VT](UnknownOID, UnknownOID, UnknownOID, () => emptyProps)(e) {
  /**
   * Note that this is intentionally recursive, so it works with multiple layers of wrapping.
   */
  override lazy val realType:PType[VT] = resolver.realType
  
  def doDeserialize(v:String) = realType.doDeserialize(v)
  def doSerialize(v:VT) = realType.doSerialize(v)
  def doWikify(context:QLContext)(v:VT, displayOpt:Option[Wikitext] = None) = realType.doWikify(context)(v)
  
  override def doMatches(left:VT, right:VT) = realType.doMatches(left, right)
  
  def renderInputXml(prop:Property[_,_], state:SpaceState, currentValue:DisplayPropVal, v:ElemValue):Elem = 
    realType.renderInputXml(prop, state, currentValue, v)

  lazy val doDefault = realType.doDefault
  
  override def toString = super.toString + ": " + realType.toString()
}

/**
 * Marker type, used to signify "no real type" in empty collections.
 */
object UnknownType extends PType[Unit](UnknownOID, UnknownOID, UnknownOID, () => emptyProps)(querki.ecology.Ecology) {
  def doDeserialize(v:String) = throw new Exception("Trying to use UnknownType!")
  def doSerialize(v:Unit) = throw new Exception("Trying to use UnknownType!")
  def doWikify(context:QLContext)(v:Unit, displayOpt:Option[Wikitext] = None) = throw new Exception("Trying to use UnknownType!")
  
  def renderInputXml(prop:Property[_,_], state:SpaceState, currentValue:DisplayPropVal, v:ElemValue):Elem = 
    throw new Exception("Trying to use UnknownType!")

  lazy val doDefault = throw new Exception("Trying to use UnknownType!")
}

trait PTypeBuilderBase[VT, -RT] {
  
  def pType:PType[VT]
  
  type rawType = RT
  
  def wrap(raw:RT):VT
  def apply(raw:RT):ElemValue = ElemValue(wrap(raw), pType)  
}
trait PTypeBuilder[VT, -RT] extends PTypeBuilderBase[VT, RT] { this:PType[VT] =>
  def pType = this
}
trait SimplePTypeBuilder[VT] extends PTypeBuilder[VT, VT] { this:PType[VT] =>
  def wrap(raw:VT) = raw
}
