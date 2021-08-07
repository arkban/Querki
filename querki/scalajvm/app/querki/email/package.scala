package querki

import scala.concurrent.Future

import akka.actor.ActorRef

import models.Property

// For the HACKs below:
import scala.util.Try
import models.{OID, Thing, Wikitext}

import querki.core.QLText
import querki.identity.{Identity, IdentityId, User}
import querki.notifications.NotifierId
import querki.values.{QLContext, SpaceState}

import querki.ecology._

package object email {

  object MOIDs extends EcotIds(2) {
    val EmailTypeOID = oldMoid(1)
    val EmailPropOID = oldMoid(2)
    val EmailTemplateOID = oldMoid(3)
    val EmailToOID = oldMoid(4)
    val EmailSendOID = oldMoid(5)
    val EmailSubjectOID = oldMoid(6)
//    val EmailCcOID = moid(7)
    val EmailBodyOID = oldMoid(8)
    val EmailShowSendOID = oldMoid(9)
    val SentToOID = oldMoid(10)
    val RecipientsOID = oldMoid(11)

    val EmailLinkOID = moid(1)
  }

  val EmailTag = "Email"

  /**
   * The character that we use to separate strings involving email addresses. Chosen mostly
   * because it is not a legal character in email addresses.
   */
  val emailSepChar = ';'

  trait Email extends EcologyInterface {

    /**
     * The email address that ordinary user emails will come from.
     */
    def from: String

    /**
     * The place to send Notifications that want to go to Email.
     */
    def identityEmail: ActorRef

    /**
     * Sends an email, as specified in the EmailMsg.
     */
    def sendEmail(msg: EmailMsg): Unit

    /**
     * Sends an "official" email in the name of Querki itself.
     *
     * USE WITH CAUTION! This should only be used for significant emails that do not come from a
     * specific person. Excessive use of these could get us labeled as spammers, which we don't want.
     *
     * TODO: in the long run, this should wind up mostly subsumed under a more-general Notifications
     * system, which will include, eg, system-displayed notifications and social network messages,
     * with the user controlling where his notifications go.
     */
    def sendSystemEmail(
      recipient: Identity,
      subject: Wikitext,
      body: Wikitext
    ): Try[Unit]

    /**
     * The thinnest shell over the underlying mail-sending capability. Assumes you've already done all the prep
     * work.
     */
    def sendRaw(
      recipientEmail: EmailAddress,
      recipientName: String,
      subject: Wikitext,
      body: Wikitext,
      from: String,
      requester: Identity
    ): Future[Unit]

    /**
     * Fetch the EmailNotifier with a given ID.
     */
    def emailNotifier(id: NotifierId): EmailNotifier

    def EmailAddressProp: Property[EmailAddress, String]
  }

  /**
   * This system works with EmailNotifiers to implement Unsubscribe functionality. All EmailNotifiers should
   * implement an Unsubscribe link in their footers unless they have *very* good reason not to! This is a
   * legal requirement!
   */
  trait Unsubscribe extends EcologyInterface {
    def unsubParam: String

    def generateUnsubLink(
      notifier: EmailNotifier,
      identityId: IdentityId,
      email: EmailAddress,
      rest: String*
    ): String

    def parseUnsub(encodedUnsub: String): Option[UnsubInfo]
  }
}
