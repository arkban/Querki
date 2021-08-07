package querki.pages

import org.scalajs.dom
import querki.globals._
import querki.comm.URL
import querki.data.SpaceInfo
import org.querki.gadgets.core.GadgetLookup
import querki.search.SearchResultsPage
import org.querki.gadgets.core.ManagedFrag

class PagesEcot(e: Ecology) extends ClientEcot(e) with Pages {

  def implements = Set(classOf[Pages])

  lazy val DataAccess = interface[querki.data.DataAccess]
  lazy val PageManager = interface[querki.display.PageManager]

  // Factories for some pages with no obvious homes:
  lazy val exploreFactory = registerThingPageFactory("_explore", { (params) => new ExplorePage(params) }, "thingId")
  lazy val viewFactory = registerThingPageFactory("_view", { (params) => new ViewPage(params) }, "thingId")

  lazy val createAndEditFactory =
    registerThingPageFactory("_createAndEdit", { (params) => new CreateAndEditPage(params) }, "model")
  lazy val sharingFactory = registerStandardFactory("_sharing", { (params) => new querki.security.SharingPage(params) })
  lazy val advancedFactory = registerThingPageFactory("_advanced", { (params) => new AdvancedPage(params) }, "thingId")
  lazy val indexFactory = registerStandardFactory("_index", { (params) => new IndexPage(params) })
  lazy val accountFactory = registerStandardFactory("_account", { (params) => new AccountPage(params) })
  lazy val createSpaceFactory = registerStandardFactory("_createSpace", { (params) => new CreateSpacePage(params) })
  lazy val importSpaceFactory = registerStandardFactory("_importSpace", { (params) => new ImportSpacePage(params) })

  lazy val securityFactory =
    registerThingPageFactory("_security", { (params) => new querki.security.SecurityPage(params) }, "thingId")
  lazy val infoFactory = registerStandardFactory("_spaceInfo", { (params) => new InfoPage(params) })
  lazy val undeleteFactory = registerStandardFactory("_undelete", { (params) => new UndeletePage(params) })
  lazy val unsubFactory = registerStandardFactory("_doUnsub", { (params) => new querki.email.UnsubscribePage(params) })

  lazy val thingPageFactory = new RawThingPageFactory

  override def postInit() = {
    exploreFactory
    viewFactory
    createAndEditFactory
    sharingFactory
    advancedFactory
    indexFactory
    accountFactory
    createSpaceFactory
    importSpaceFactory
    thingPageFactory
    securityFactory
    infoFactory
    undeleteFactory
    unsubFactory
  }

  private var factories = Seq.empty[PageFactory]

  def registerFactory[T <: PageFactory](factory: T): T = {
    factories :+= factory
    factory
  }

  def registerStandardFactory(
    registeredName: String,
    const: ParamMap => Page
  ): PageFactory = {
    registerFactory(new PageFactoryBase(registeredName, const))
  }

  def registerThingPageFactory(
    registeredName: String,
    const: ParamMap => Page,
    paramName: String
  ): ThingPageFactory = {
    registerFactory(new ThingPageFactoryBase(registeredName, const, paramName))
  }

  // TODO: this doesn't yet work correctly to navigate cross-Spaces:
  def showSpacePage(space: SpaceInfo) = thingPageFactory.showPage(space)
  def spaceUrl(space: SpaceInfo): URL = thingPageFactory.pageUrl(space)

  /**
   * Construct the correct Page, based on the passed-in page name.
   *
   * This basically goes through the registered factories, and the first one that actually
   * constructs a Page based on this name wins.
   *
   * TODO: this is arguably a stupid way for this to work, and should probably be
   * restructured to have a Map of factories by name instead. The current approach is
   * mostly a historical artifact.
   */
  def constructPage(
    name: String,
    params: ParamMap
  ): Option[Page] = {
    val pageOpt = (Option.empty[Page] /: factories) { (opt, factory) =>
      opt match {
        case Some(page) => opt
        case None       => factory.constructPageOpt(name, params)
      }
    }

    // If we haven't found a Page with that name, then it's naming a Thing. Go to that
    // Thing's Page *if* we're in a legit Space; otherwise, fall back to the Index.
    pageOpt.orElse {
      if (DataAccess.space.isDefined)
        Some(new ThingPage(TID(name), params))
      else
        None
    }
  }

  def findPageFor[N <: dom.Node](node: ManagedFrag[N]): Option[Page] = {
    GadgetLookup.findParentGadget(node, _.isInstanceOf[Page]).map(_.asInstanceOf[Page])
  }

  def updatePage[N <: dom.Node](node: ManagedFrag[N]) = {
    findPageFor(node).map(_.reindex())
  }
}
