package querki.core

import scala.xml.NodeSeq

import models._

import querki.ecology._
import querki.globals._
import querki.values.{ElemValue, QLContext, SpaceState}

object TypeUtils {

  trait CommonInputRenderers { self: SystemTypeBase[_] with EcologyMember =>
    private lazy val Core = interface[querki.core.Core]

    def renderAnyText(
      prop: Property[_, _],
      context: QLContext,
      currentValue: DisplayPropVal,
      v: ElemValue,
      elemT: PType[_]
    )(
      doRender: (String) => NodeSeq
    ): NodeSeq = {
      val str = elemT.toUser(v)(context.state)
      val xml = doRender(str)
      xml
    }

    def renderLargeText(
      prop: Property[_, _],
      context: QLContext,
      currentValue: DisplayPropVal,
      v: ElemValue,
      elemT: PType[_]
    ): NodeSeq = {
      renderAnyText(prop, context, currentValue, v, elemT) { cv =>
        <textarea class="_largeTextEdit form-control" rows="2">{cv}</textarea>
      }
    }

    def renderText(
      prop: Property[_, _],
      context: QLContext,
      currentValue: DisplayPropVal,
      v: ElemValue,
      elemT: PType[_]
    ): NodeSeq = {
      renderAnyText(prop, context, currentValue, v, elemT) { cv =>
        <input class="_textEdit form-control" type="text" value={cv}/>
      }
    }

    def renderBlank(
      prop: Property[_, _],
      context: QLContext,
      currentValue: DisplayPropVal,
      elemT: PType[_]
    ): NodeSeq = {
      renderText(prop, context, currentValue, Core.TextType(""), Core.TextType)
    }
  }

  abstract class SystemTypeBase[T](
    tid: OID,
    spaceId: OID,
    modelId: OID,
    pf: PropMap
  )(implicit
    val ecology: Ecology
  ) extends PType[T](tid, spaceId, modelId, pf)
       with EcologyMember
       with CommonInputRenderers {
    // Types is where the various validators and such live:
    lazy val Types = interface[querki.types.Types]
    private lazy val Core = interface[querki.core.Core]

    def renderInputXml(
      prop: Property[_, _],
      context: QLContext,
      currentValue: DisplayPropVal,
      v: ElemValue
    ): Future[NodeSeq] = {
      // TBD: this is smelly -- the fact that we need to know here how to render Optional is a nasty abstraction
      // break. But in general, rendering probably doesn't belong here: ultimately, rendering depends on the
      // Collection/Type matrix, and there doesn't seem to be a nice clean division of responsibilities...
      val renderedBlank = for (
        ev <- currentValue.effectiveV;
        if (displayEmptyAsBlank && ev.cType == Core.Optional && ev.isEmpty)
      )
        yield renderBlank(prop, context, currentValue, this)

      Future.successful(renderedBlank.getOrElse(renderText(prop, context, currentValue, v, this)))
    }

    // Iff a Type wants to render QNone as blank text instead of the default value, set this to true
    val displayEmptyAsBlank: Boolean = false
  }

  abstract class SystemType[T](
    tid: OID,
    pf: PropMap
  )(implicit
    e: Ecology
  ) extends SystemTypeBase[T](tid, SystemIds.systemOID, MOIDs.UrTypeOID, pf)

  /**
   * Optional side-trait that a Type can implement to advertise that it only allows specific discrete values.
   */
  trait DiscreteType[VT] extends PType[VT] {

    /**
     * The legal values of this Type on this Property, in order.
     */
    def range(prop: Property[VT, _])(implicit state: SpaceState): Seq[VT]
  }
}
