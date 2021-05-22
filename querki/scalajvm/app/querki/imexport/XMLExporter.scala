package querki.imexport

import scalatags.Text.short._
import scalatags.generic
import scalatags.text._

import models._

import querki.core.{IsLinkType, NameUtils}
import querki.core.MOIDs._
import querki.globals._
import querki.types.ModelTypeBase
import querki.types.MOIDs._
import querki.values.QValue

/**
 * XML Exporter. This is an instance-per-invocation class, with one for each export operation.
 *
 * @author jducoeur
 */
private[imexport] class XMLExporter(implicit val ecology: Ecology) extends EcologyMember with NameUtils {

  lazy val Core = interface[querki.core.Core]
  lazy val PropListManager = interface[querki.core.PropListManager]
  lazy val System = interface[querki.system.System]

  lazy val LinkType = Core.LinkType
  lazy val NameType = Core.NameType

  lazy val SystemSpace = System.State
  lazy val systemId = SystemSpace.id

  implicit def PropNameOrdering = PropListManager.PropNameOrdering

  import QuerkiML._
  implicit def tidAttr = new ThingAttr
  implicit def asOIDAttr = new AsOIDAttr
  implicit def oidAttr = new OIDAttr

  final val standardSpaceNS = "https://www.querki.net/"

  var prefixMap = Map.empty[OID, String]

  def exportSpace(state: SpaceState): String = {
    implicit val s = state

    prefixMap += (System.State.id -> "ss:")
    prefixMap += (state.id -> "")

    val spaceNamespace = s"https://www.querki.net/u/${state.ownerHandle}/${state.linkName.map(canonicalize).get}"

    val (mods, insts) = state.localThings.partition(_.isModel)

    val complete =
      querki(
        "xmlns:ss".a := standardSpaceNS,
        "xmlns:s1".a := spaceNamespace,
        space(
          namespace := spaceNamespace,
          stdAttrs(state),
          types(allTypes),
          spaceProps(allProperties),
          thingProps(state),
          models(mods.toSeq.map(oneModel)),
          instances(insts.toSeq.map(oneInstance))
        )
      )

    val rawStr = """<?xml version="1.0" encoding="UTF-8"?>""" + complete.toString()
    rawStr
  }

  def oneType(pt: ModelTypeBase)(implicit state: SpaceState) = {
    state.anything(pt.basedOn).map { model =>
      val typProps = pt.props - ModelForTypePropOID
      typ(
        tid(pt),
        modelref := tnameAndId(model),
        thingProps(pt, typProps)
      )
    }
  }

  def allTypes(implicit state: SpaceState): Seq[Tag] = {
    state.types.values.toSeq.sortBy(_.linkName).map { pt =>
      pt match {
        case mt: ModelTypeBase => oneType(mt)
        case _                 => None
      }
    }.flatten
  }

  def oneProp(prop: AnyProp)(implicit state: SpaceState): Tag = {
    val propProps = prop.props - CollectionPropOID - TypePropOID
    property(
      stdAttrs(prop),
      coll := tname(prop.cType),
      ptyp := tname(prop.pType),
      thingProps(prop, propProps)
    )
  }

  def allProperties(implicit state: SpaceState): Seq[Tag] = {
    state.spaceProps.values.toSeq.sortBy(_.linkName).map { prop =>
      oneProp(prop)
    }
  }

  def oneModel(t: Thing)(implicit state: SpaceState): Tag = {
    model(
      stdAttrs(t),
      thingProps(t)
    )
  }

  def oneInstance(t: Thing)(implicit state: SpaceState): Tag = {
    Tag(
      t.getModelOpt.map(tname).getOrElse(QuerkiML.exportOID(t.model)),
      List(tid(t) +: creator(t) ++: Seq(thingProps(t))),
      false
    )
  }

  def stdAttrs(t: Thing)(implicit state: SpaceState) = {
    Seq(
      tid(t),
      // We explicitly assume that everything has a model, since the only exception is UrThing:
      modelref := tnameAndId(t.getModelOpt.get)
    ) ++ t.linkName.map(canonicalize).map(name := _) ++ creator(t)
  }

  def thingProps(t: Thing)(implicit state: SpaceState): Tag = thingProps(t, t.props)

  def thingProps(
    t: PropertyBundle,
    pm: PropMap
  )(implicit
    state: SpaceState
  ): Tag = {
    val propPairs = for {
      pair <- pm
      propId = pair._1
      v = pair._2
      propOpt = state.prop(propId)
      if (propOpt.isDefined)
      prop = propOpt.get
    } yield (prop, v)

    val sortedProps = propPairs.toSeq.sortBy(_._1)

    props(
      sortedProps.map { pair =>
        val (prop, qv) = pair
        Tag(
          tname(prop),
          List(propValue(prop, qv)),
          false
        )
      }
    )
  }

  def propValue(
    prop: AnyProp,
    qv: QValue
  )(implicit
    state: SpaceState
  ): Seq[Tag] = {
    qv.cv.toSeq.map { elemv =>
      elem(
        elemv.pType match {
          case pt: IsLinkType => {
            val topt: Option[Thing] = for {
              oid <- elemv.getOpt(LinkType)
              thing <- state.anything(oid)
            } yield thing

            topt.flatMap { thing =>
              // Elide illegal links. These currently arise from Required Link Properties
              // that never get filled in, which are a failure in the data model.
              if (thing.id == UnknownOID)
                None
              else
                Some(tnameAndId(thing))
            }
          }

          case mt: ModelTypeBase => {
            val bundleOpt = elemv.getOpt(mt)
            bundleOpt.map(bundle => thingProps(bundle, bundle.props))
          }

          case NameType => {
            // We want names in their literal form, not serialized:
            elemv.getOpt(NameType)
          }

          case _ => {
            // We fall back to ordinary serialization, which is fine for most types:
            elemv.pType.serialize(elemv)
          }
        }
      )
    }
  }

  def withNS(t: Thing)(rest: => String): String = {
    prefixMap(t.spaceId) + rest
  }

  def tid(t: Thing) = id := t.id.toThingId

  def creator(t: Thing): Seq[Modifier] = {
    val pair = for {
      c <- t.creatorOpt
      identityId <- c.identityIdOpt
      attrPair = creatorAttr := identityId.id
    } yield attrPair

    pair.toList
  }

  def tnameBase(t: Thing)(implicit state: SpaceState) = {
    t.linkName.map(tn => withNS(t)(canonicalize(tn)))
  }

  def tnameAndId(t: Thing)(implicit state: SpaceState) = {
    s"${tnameBase(t).getOrElse("")} ${QuerkiML.exportOID(t.id)}"
  }

  def tname(t: Thing)(implicit state: SpaceState) = {
    tnameBase(t).getOrElse(QuerkiML.exportOID(t.id))
  }
}
