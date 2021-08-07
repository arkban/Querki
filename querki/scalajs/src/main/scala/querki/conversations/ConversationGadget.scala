package querki.conversations

import org.scalajs.dom.{raw => dom}
import dom.Element
import scalatags.JsDom.all.{input => inp, _}

import org.querki.gadgets._
import org.querki.jquery._
import org.querki.squery.Focusable._

import querki.data.ThingInfo
import querki.display.WrapperDiv
import querki.globals._

private[conversations] class ConversationGadget(
  convOpt: Option[ConvNode],
  canComment: Boolean,
  thingId: TID,
  replyPromptTextOpt: Option[String] = None,
  replyLinkClassOpt: Option[String] = None
)(implicit
  val ecology: Ecology
) extends Gadget[dom.HTMLDivElement]
     with EcologyMember {
  lazy val Gadgets = interface[querki.display.Gadgets]

  lazy val replyPromptText = replyPromptTextOpt.getOrElse("Click here to reply...")

  /**
   * TODO: this is all wrong! It just does a straight flattening, but what we really want is much more
   * subtle, flattening only the primary nodes. We may, in fact, need to restructure the classes a bit to
   * make the tree make more sense: instead of having a ConversationGadget at all, we might have just
   * CommentGadget, and that recursively renders its replies.
   */
  def flattenNodes(node: ConvNode): Seq[CommentGadget] = {
    new CommentGadget(node.comment, thingId) +: node.responses.flatMap(flattenNodes(_))
  }
  lazy val flattenedNodes = convOpt.map(flattenNodes(_)).getOrElse(Seq.empty)

  lazy val commentContainer = Gadget(div(cls := "_commentContainer col-md-offset1 col-md-9", flattenedNodes))

  def doRender() =
    if (convOpt.isEmpty) {
      div()
    } else {
      div(
        cls := "_convThread row",
        commentContainer,
        if (canComment) {
          replyContainer
        }
      )
    }

  lazy val replyContainer =
    (new WrapperDiv()(ecology))(cls := "_replyContainer col-md-offset1 col-md-9").initialContent(replyPlaceholder)

  def replyPlaceholder: SimpleGadget =
    replyLinkClassOpt match {
      case Some(styles) => {
        Gadget(
          a(cls := styles, replyPromptText, onclick := { () => showRealReplyInput() })
        )
      }
      case None => {
        Gadget(
          inp(
            cls := "_replyPlaceholder form-control",
            tpe := "text",
            placeholder := replyPromptText,
            onclick := { () => showRealReplyInput() },
            onkeydown := { () => showRealReplyInput() }
          )
        )
      }
    }

  def showRealReplyInput(): Unit = {
    replyContainer.replaceContents(realReply.rendered)
    realReply.focus()
  }

  lazy val realReply = new ReplyGadget(Some(flattenedNodes.last.comment.id), "Reply here...", thingId, onNewComment)

  def onNewComment(newNode: ConvNode) = {
    val gadgets = flattenNodes(newNode).map(_.rendered)
    $(commentContainer.elem).append(gadgets)
    replyContainer.replaceContents(replyPlaceholder.rendered)
    Gadgets.hookPendingGadgets()
  }
}

object ConversationGadget {

  def fromElem(e: Element)(implicit ecology: Ecology): ConversationGadget = {
    val thingId = TID($(e).dataString("thingid"))
    val suppressReplies = $(e).hasClass("suppressreplies")
    val canComment =
      if (suppressReplies)
        false
      else
        $(e).data("cancomment").get.asInstanceOf[Boolean]
    val replyPromptTextOpt = $(e).data("replyprompt").toOption.map(_.asInstanceOf[String])
    val replyLinkClassOpt = $(e).data("replylinkclass").toOption.map(_.asInstanceOf[String])
    val commentInfos = $(e).find("._convCommentData").mapElems(CommentGadget.infoFromElem(_))
    val rootNode = (commentInfos.foldRight(Seq.empty[ConvNode]) { (info, prevNode) =>
      Seq(ConvNode(info, prevNode))
    }).headOption
    val gadget = new ConversationGadget(rootNode, canComment, thingId, replyPromptTextOpt, replyLinkClassOpt)
    $(e).replaceWith(gadget.rendered)
    gadget
  }
}
