package querki.pages

import models.Wikitext

sealed trait PageDetails

case class ThingPageDetails(customHeader:Option[String]) extends PageDetails
