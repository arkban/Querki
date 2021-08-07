package querki.core

import models.{PropertyThingOps, ThingOps}

import querki.ecology._
import querki.globals._
import querki.util.QLog
import querki.values.QLContext

/**
 * Ecots that define "methods" (what we're now generally calling "functions") should mix this in.
 */
trait MethodDefs { self: QuerkiEcot =>

  // Since Methods usually declare Summaries, we need to depend on Conventions:
  val Conventions = initRequires[querki.conventions.Conventions]
  val Functions = initRequires[querki.core.Functions]
  val Signature = initRequires[querki.ql.Signature]

  lazy val AnyType = Signature.AnyType

  lazy val QUnit = Core.QUnit

  type QFut = querki.values.QFut

  class MethodThingOps(method: InternalMethod) extends PropertyThingOps(method) {

    // Okay, this is a bit horrible, bouncing back and forth to the Thing like this. But
    // it allows us to declare InternalMethods (which don't need to be serialized) concisely.
    override def qlApplyTop(
      inv: Invocation,
      transformThing: Thing
    ): Future[QLContext] = {
      method.qlApplyTop(inv, transformThing)
    }
  }

  /**
   * Internal methods -- functions defined in-code that can be assigned as properties -- should
   * inherit from this.
   */
  class InternalMethod(
    tid: OID,
    p: PropMap,
    modelId: OID = querki.core.MOIDs.UrPropOID
  ) extends SystemProperty[String, String](
      tid,
      Core.InternalMethodType,
      QUnit,
      (p + (querki.datamodel.MOIDs.IsFunctionOID -> ExactlyOne(YesNoType(true)))),
      modelId
    ) {

    /**
     * Methods should override this to implement their own functionality, if they just
     * need to return a plain QValue. Note that this will get wrapped by Future.successful()
     * before being passed up the line.
     */
    def qlApply(inv: Invocation): QFut = {
      // By default, we just pass the incoming context right through:
      Future.successful(inv.context.value)
    }

    /**
     * Methods may override this if they want full control of the resulting
     * QLContext.
     */
    def qlApplyTop(
      inv: Invocation,
      transformThing: Thing
    ): Future[QLContext] = {
      qlApply(inv).map(next => inv.context.nextFrom(next, transformThing))
    }

    override def thingOps(e: Ecology): ThingOps = new MethodThingOps(this)
  }

  /**
   * ******************************************************
   *
   * Abstract Functions and Implementations
   *
   * If you define a Function as an AbstractFunction, that means you can separately define
   * FunctionImpls that say how to implement it for various Types. This is *highly* recommended
   * when in any doubt at all about the required Type. It is basically Querki's simplified
   * version of a typeclass -- not as powerful, but all you need most of the time.
   *
   * As part of the declaration of the AbstractFunction, you declare what it "abstract over" --
   * that is, what do you inspect to determine the type of the Function. This is usually
   * Received -- the received value -- but could potentially be the defining value or the
   * first parameter. (Both to-be-implemented when we care.)
   *
   * ******************************************************
   */

  sealed trait AbstractsOver {
    def over: OID
  }
  case object Received extends AbstractsOver { val over = FunctionMOIDs.AbstractOverReceivedOID }

  /**
   * A Function that doesn't actually define the functionality itself. Instead,
   * this is generic over the received value (or another element), and the system looks up
   * a FunctionImpl that matches that Type.
   */
  class AbstractFunction(
    tid: OID,
    over: AbstractsOver,
    p: PropMap
  ) extends InternalMethod(tid, p + Functions.AbstractOverProp(over.over)) {

    /**
     * Looks for an implementation of this AbstractFunction for a given Thing or one of its Models.
     */
    def findImplForThing(
      t: Thing,
      myImpls: FunctionImplsForOne
    )(implicit
      state: SpaceState
    ): Option[Thing] = {
      if (t.id == MOIDs.RootOID)
        None
      else {
        myImpls.map.get(t.id).orElse(findImplForThing(t.getModel, myImpls))
      }
    }

    def findImpl(
      inv: Invocation,
      myImpls: FunctionImplsForOne
    )(implicit
      state: SpaceState
    ): Option[Thing] = {
      if (over == Received) {
        val pt = inv.context.value.pType

        if (pt == Core.LinkType) {
          for {
            oid <- inv.context.value.firstAs(Core.LinkType)
            t <- inv.state.anything(oid)
            result <- findImplForThing(t, myImpls)
          } yield result
        } else {
          // Look for an implementation by Type
          // For now, we only allow exact matches. We *might* extend this to coercions down the
          // line, but let's not bother yet.
          myImpls.map.get(pt.id)
        }
      } else
        // To be implemented -- eventually, we want to allow for at least Defining in the AbstractsOver:
        ???
    }

    override def qlApplyTop(
      inv: Invocation,
      transformThing: Thing
    ): Future[QLContext] = {
      val implMap = Functions.implMap(inv.state)
      val result = for {
        myImpls <- implMap.map.get(id)
        impl <- findImpl(inv, myImpls)(inv.state)
      } yield impl.qlApplyTop(inv, transformThing)

      result.getOrElse(throw new Exception(s"Didn't find an implementation of $displayName for ${inv.context}!"))
    }
  }

  /**
   * The implementation of a previously-defined AbstractFunction.
   *
   * Note that implementations do not bother to have names -- they're just specialized implementations.
   *
   * The implementation should define a qlApply().
   *
   * Implementations, unlike most Functions, have a specific parent, the Implementation Model. This is
   * mainly so that we can efficiently locate all of the Implementations in the Space.
   *
   * The implementation copies the Signature of the function that it is implementing, if there is one,
   * so that it can use the parameters consistently.
   */
  class FunctionImpl(
    pid: OID,
    implementsFunction: MethodDefs#AbstractFunction,
    implementsTypes: Seq[OID]
  ) extends InternalMethod(
      pid,
      toProps(
        Functions.ImplementsFunctionProp(implementsFunction.id),
        Functions.ImplementsTypesProp(implementsTypes: _*)
      )
        ++ {
          implementsFunction.props.get(querki.ql.SignatureMOIDs.SignaturePropOID) match {
            case Some(sig) => toProps((querki.ql.SignatureMOIDs.SignaturePropOID -> sig))
            case _         => toProps()
          }
        },
      FunctionMOIDs.ImplementationModelOID
    )
}
