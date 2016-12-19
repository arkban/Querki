package querki.display.rx

import org.scalajs.dom
import dom.Element
import scalatags.JsDom.all._
import rx._
import org.querki.gadgets.core.TypedGadget
import org.querki.jquery._

import querki.globals._

/**
 * A reactive wrapper around Gadgets, to make them a bit easier to use. 
 * 
 * Think of this as a reactive container into which you put a Gadget of a particular type, 
 * which other code can hang off of. This is how you build complex reactive structures, while
 * still keep the declarations of the Gadgets in the Scalatags hierarchy so that you can
 * see what goes where.
 * 
 * So you typically declare this as an empty holder, and then assign to it later in the
 * Scalatags, like this:
 * {{{
 * val myGadget = GadgetRef[MyGadget]
 * 
 * def funcUsingMyGadget = {
 *   myGadget.someFunction()
 * }
 * 
 * def funcUsingMyGadgetIfExists = {
 *   myGadget.map(_.someOtherFunction())
 * }
 * ...
 * val rendered =
 *   p(
 *     "Here's the gadget",
 *     myGadget <= new MyGadget(...)
 *   )
 * }}}
 * Note that the GadgetRef will implicitly unwrap to the underlying Gadget if the environment
 * calls for that. Use that with some care -- it will throw an exception if the Gadget hasn't
 * been set yet! If you aren't certain whether the Gadget has been set, use map() or flatMap()
 * instead, and it has Option-like semantics. 
 * 
 * You don't usually create this by hand -- use the methods in the companion object to make
 * GadgetRef and GadgetElementRef.
 * 
 * TODO: is there any way to make this sensitive to the actual element type of the Gadget, without
 * forcing us to add another type parameter everywhere?
 * 
 * TODO: this is annoyingly dependent on jQuery. If we could make it work with raw DOM, we could
 * extract an inner "core" library with no jQuery dependency. That's worth doing, I think.
 * 
 * @author jducoeur
 */
class GadgetRef[G <: Gadget[_]](implicit val ecology:Ecology) extends Gadget[Element] with EcologyMember {

  // TODO: this interface, and the EcologyMember trait in general, is solely here to
  // support Pages.updatePage(). We need to make this more generally hookable, so we
  // can extract GadgetRef up to the library.
  lazy val Pages = interface[querki.pages.Pages]
  
  def doRender = ???
  
  /**
   * The actual renderer for this reference.
   * 
   * If this reference has not yet been set, it will create and return an empty span.
   * 
   * TODO: this is type-anonymous -- it simply expects to return an Element. Can we do better?
   */
  override def createFrag = {
    opt().map(g => g.asInstanceOf[Gadget[Element]].render).getOrElse(span().render).asInstanceOf[Element]
  }
  
  /**
   * The underlying Gadget that we're tracking here. If you want to react to the
   * Gadget's creation, or be safe when you aren't sure about creation order, observe this.
   */
  val opt = Var[Option[G]](None)
  
  /**
   * The safe way to run some code iff this Gadget actually exists.
   */
  def map[T](f:G => T):Option[T] = opt().map(f)
  def flatMap[T](f:G => Option[T]) = opt().flatMap(f)
  def foreach(f:G => Unit):Unit = opt().foreach(f)
  
  /**
   * Shorthand for this extremely common case.
   */
  def mapOrElse[T](f:G => T, default:T):T = map(f).getOrElse(default)
  
  /**
   * Convenience wrapper for getting at the underlying element, if it's been set up yet.
   */
  def mapElem[T](f:Element => T):Option[T] = flatMap(_.asInstanceOf[Gadget[Element]].elemOpt.map(f))
  
  /**
   * Just delegate the insertion signal to the underlying Gadget, if there is one.
   */
  override def onInserted() = foreach(_.onInserted())
  
  /**
   * Returns the underlying Gadget. Use with care: this will throw if the Gadget hasn't been
   * created yet!
   */
  def get = opt().get
  
  def isDefined = opt().isDefined
  def isEmpty = opt().isEmpty
  
  /**
   * Reassigns the contents of this reference, rendering the new Gadget and putting it into place if needed.
   * 
   * This is usually called via the <= or <~ operators.
   * 
   * TODO: there is a grungy asInstanceOf in here. Is there any way to pick up the actual type of the
   * underlying Gadget without polluting the type signature of this call in such a way that you have to
   * redundantly state it every time?
   */
  def reassign(g:G, retainPrevious:Boolean) = {
    if (elemOpt.isDefined && g.elemOpt.isDefined && elemOpt.get == g.elemOpt.get) {
      // Reassigning the same value is a no-op
    } else {
      val prevElemOpt = elemOpt
      
      // If we've been through rendering, render and insert the new one:
      parentOpt.foreach { parent =>
        val r = g.rendered.asInstanceOf[Element]
        $(r).insertBefore(elem)
        elemOptRx() = Some(r)
        g.onInserted()
      }
      
      // Detach/remove the previous element from the DOM, if it is there:
      prevElemOpt.foreach { e =>
        if (retainPrevious)
          $(e).detach()
        else
          $(e).remove()
      }
      
      opt() = Some(g)

      // Since we're changing the page layout, update things:
      Pages.updatePage(this)
    }
    this    
  }
  
  /**
   * Set this to the actual Gadget when it's created. 
   * 
   * If this reference was already rendered, this will render the new gadget and reparent it where the
   * reference lives.
   */
  def <=(g:G) = reassign(g, false)
  
  /**
   * The same as <=, but doesn't remove the old value from the DOM. Use this if you expect to reuse the
   * Gadgets that you are assigning. (Typically when this reference is somehow modal, and you don't want
   * to regenerate the Gadgets every time because they are expensive.)
   */
  def <~(g:G) = reassign(g, true)
  
  /**
   * This defines a callback for when the Gadget actually gets defined. Note that this does *not*
   * mean that the underlying Element has been created! If you care about the underlying DOM element,
   * use whenRendered() instead.
   * 
   * This function is chainable.
   */
  def whenSet(f:G => Unit) = {
    Obs(opt) {
      map(f)
    }
    this
  }
  
  /**
   * Invoke the given function when this Gadget is actually rendered. At that point, the Gadget's elem
   * has been set, so you can operate on it. This is sometimes the right place to add, eg, event hooks.
   */
  def whenRendered(f:G => Unit) = {
    whenSet { g =>
      if (g.elemOpt.isDefined)
        f(g)
      else {
        Obs(g.elemOptRx) {
          if (g.elemOpt.isDefined)
            f(g)
        }
      }
    }
  }
}

/**
 * A variant of GadgetRef, which you should use when you're just dealing with a raw TypedTag, not
 * a Gadget per se.
 * 
 * All this really does is provide type-safety for this situation, without requiring that GadgetRef
 * itself know about T.
 * 
 * Create this using GadgetRef.of[].
 */
class GadgetElementRef[T <: dom.Element](implicit e:Ecology) extends GadgetRef[Gadget[T]] {
  /**
   * This is similar to GadgetRef <= operation, but works with a raw TypedTag and wraps it in a
   * Gadget. This is used when you declared it with GadgetRef.of[].
   */
  def <=(tag:scalatags.JsDom.TypedTag[T]) = reassign(new TypedGadget(tag), false)
  def <~(tag:scalatags.JsDom.TypedTag[T]) = reassign(new TypedGadget(tag), true)
}

object GadgetRef {
  /**
   * The usual way to create a GadgetRef for a particular type of Gadget.
   */
  def apply[G <: Gadget[_]](implicit e:Ecology) = new GadgetRef[G]
  /**
   * This allows you to create a GadgetRef that will work with an underlying TypedTag.
   */
  def of[T <: dom.Element](implicit e:Ecology) = new GadgetElementRef[T]
}
