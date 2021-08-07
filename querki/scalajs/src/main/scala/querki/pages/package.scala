package querki

import org.scalajs.dom
import querki.globals._
import querki.comm.URL
import querki.data.{BasicThingInfo, SpaceInfo}
import org.querki.gadgets.core.ManagedFrag

package object pages {

  /**
   * The factory for a particular kind of page.
   */
  trait PageFactory {

    /**
     * If this name fits this PageFactory, construct the Page; otherwise, decline and it'll go to the next.
     */
    def constructPageOpt(
      pageName: String,
      params: ParamMap
    ): Option[Page]

    /**
     * Returns the URL for this Page with these Params.
     */
    def pageUrl(params: (String, String)*): URL

    /**
     * Actually navigate to this page.
     */
    def showPage(params: (String, String)*): Future[Page]
  }

  /**
   * Standard signatures for pages that take a Thing as their one parameter.
   */
  trait ThingPageFactory extends PageFactory {

    /**
     * Pass in a Thing to get the URL for this page. Prefer to use this instead of the lower-level version
     * of pageUrl() when possible -- it's much more safe, and introduces less coupling.
     */
    def pageUrl(
      thing: BasicThingInfo,
      addlParams: (String, String)*
    ): URL

    /**
     * Actually navigate to this page for this Thing.
     */
    def showPage(thing: BasicThingInfo): Future[Page]

    /**
     * Actually navigate to this page for this Thing.
     */
    def showPage(tid: TID): Future[Page]
  }

  trait Pages extends EcologyInterface {

    /**
     * Convenience wrapper around registerFactory, for the most common case: simply
     * pass in the name of the page and a constructor lambda, and it builds the factory
     * for you.
     */
    def registerStandardFactory(
      pageName: String,
      const: ParamMap => Page
    ): PageFactory

    /**
     * Convenience wrapper for creating ThingPageFactories. These are common enough (pages with one parameter, a
     * TID) that we give them their own entry point.
     *
     * TODO: the fact that we are passing paramName in here is a bad smell. This really ought to just be
     * standardized as thingId.
     */
    def registerThingPageFactory(
      registeredName: String,
      const: ParamMap => Page,
      paramName: String
    ): ThingPageFactory

    /**
     * Given the name and parameters to a Page, build a new instance.
     */
    def constructPage(
      name: String,
      params: ParamMap
    ): Option[Page]

    def exploreFactory: ThingPageFactory
    def viewFactory: ThingPageFactory
    def createAndEditFactory: ThingPageFactory
    def sharingFactory: PageFactory
    def advancedFactory: ThingPageFactory
    def indexFactory: PageFactory
    def accountFactory: PageFactory
    def createSpaceFactory: PageFactory
    def importSpaceFactory: PageFactory
    def thingPageFactory: ThingPageFactory
    def securityFactory: ThingPageFactory
    def infoFactory: PageFactory
    def undeleteFactory: PageFactory

    /**
     * The URL of the given Space.
     */
    def spaceUrl(space: SpaceInfo): URL

    /**
     * Navigate to the given Space.
     */
    def showSpacePage(space: SpaceInfo): Future[Page]

    /**
     * Returns the Page that contains the given Frag.
     */
    def findPageFor[N <: dom.Node](node: ManagedFrag[N]): Option[Page]

    /**
     * Indicates that something has changed significantly to alter the page layout, so the
     * Page should be recalculated as necessary. Note that this doesn't actually change
     * anything visible; rather, this does the needed adjustments after visible changes
     * have happened.
     *
     * By and large, it is appropriate to call this after any significant alterations to
     * the page's structure, especially asynchronous changes. (It should not be called
     * during normal page setup; that's just extra work.)
     */
    def updatePage[N <: dom.Node](node: ManagedFrag[N]): Unit
  }

  /**
   * Page parameters.
   */
  type ParamMap = Map[String, String]

  implicit class PageParamOps(params: ParamMap) {

    /**
     * Fetch a page parameter that must exist, otherwise it is an error.
     *
     * This should specifically be used in val's in your Page class; that way, if the param is missing, it
     * will throw an exception during page construction.
     */
    def requiredParam(name: String) = params.get(name).getOrElse(throw new MissingPageParameterException(name))
  }
}
