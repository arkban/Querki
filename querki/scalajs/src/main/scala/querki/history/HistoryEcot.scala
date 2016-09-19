package querki.history

import autowire.Core.Request

import querki.globals._
import querki.history.HistoryFunctions._
import querki.pages.{Page, PageFactory, ParamMap}

class HistoryEcot(e:Ecology) extends ClientEcot(e) with History {
  def implements = Set(classOf[History])
  
  lazy val Pages = interface[querki.pages.Pages]
  
  lazy val historySummaryFactory = Pages.registerStandardFactory("_historySummary", { (params) => new HistorySummaryPage(params) })
  
  /**
   * If we are in View History mode, which version are we viewing?
   */
  var currentHistoryVersion:Option[HistoryVersion] = None
  def setHistoryVersion(v:HistoryVersion) = currentHistoryVersion = Some(v)
  def viewingHistory = currentHistoryVersion.isDefined
  
  override def postInit() = {
    historySummaryFactory
  }
  
  def isLegalDuringHistory(req:Request[String]):Boolean = {
    // TODO: this implementation is grotesque. Can we come up with something better that lets us
    // set attributes or market traits on the API traits? 
    req.path match {
      // Allow *nearly* the entire ThingFunctions API
      case "querki" :: "api" :: "ThingFunctions" :: "deleteThing" :: rest => false
      case "querki" :: "api" :: "ThingFunctions" :: rest => true
      case "querki" :: "search" :: "SearchFunctions" :: rest => true 
      case _ => false
    }
  }
}
