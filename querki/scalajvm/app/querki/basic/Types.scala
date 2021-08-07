package querki.basic

import scala.concurrent.Future

import querki.ecology._

import models._

import querki.core.{IsTextType, NameableType, TextTypeBasis}

import querki.util.SafeUrl
import querki.values.{ElemValue, QLContext, SpaceState}

import MOIDs._

trait PlainTextBaseType { self: CoreEcot with TextTypeBasis =>

  abstract class PlainTextType(
    tid: OID,
    pf: PropMap
  ) extends SystemType[PlainText](tid, pf)
       with PTypeBuilder[PlainText, String]
       with IsTextType
       with NameableType
       with TextTypeUtils {
    def doDeserialize(v: String)(implicit state: SpaceState) = PlainText(v)
    def doSerialize(v: PlainText)(implicit state: SpaceState) = v.text

    def getName(context: QLContext)(v: ElemValue): String = get(v).text

    def equalNames(
      str1: PlainText,
      str2: PlainText
    ): Boolean = {
      str1.text.toLowerCase.contentEquals(str2.text.toLowerCase())
    }

    // TODO: in most cases, this should render as a link, the same was Tag Set does. But that breaks
    // Display Name. Hmm. Should we special case Display Name?
    def doWikify(
      context: QLContext
    )(
      v: PlainText,
      displayOpt: Option[Wikitext] = None,
      lexicalThing: Option[PropertyBundle] = None
    ) =
      Future.successful(Wikitext(v.text))

    override def doComp(
      context: QLContext
    )(
      left: PlainText,
      right: PlainText
    ): Boolean = { left.text.toLowerCase < right.text.toLowerCase }

    override def doMatches(
      left: PlainText,
      right: PlainText
    ): Boolean = equalNames(left, right)

    override def validate(
      v: String,
      prop: Property[_, _],
      state: SpaceState
    ): Unit = validateText(v, prop, state)

    def doDefault(implicit state: SpaceState) = PlainText("")
    override def wrap(raw: String): valType = PlainText(raw)

    def doComputeMemSize(v: PlainText): Int = v.raw.length

    def rawString(elem: ElemValue): String = {
      val v = get(elem)
      v.text
    }
  }
}
