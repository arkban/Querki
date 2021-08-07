package querki.display

import scala.concurrent.{Future, Promise}

import scala.scalajs.js
import org.scalajs.dom
import org.querki.jquery._
import scalatags.JsDom.all._
import autowire._
import _root_.rx._
import org.querki.gadgets._

import querki.globals._

import querki.comm.URL
import querki.identity.TOSPage
import querki.identity.UserLevel.PendingUser
import querki.pages.{MissingPageParameterException, Page, ParamMap}
import querki.session.UserFunctions
import UserFunctions._
import querki.util.Notifier

class PageManagerEcot(e: Ecology) extends ClientEcot(e) with PageManager {
  def implements = Set(classOf[PageManager])

  lazy val Client = interface[querki.client.Client]
  lazy val DataAccess = interface[querki.data.DataAccess]
  lazy val InputGadgets = interface[querki.display.input.InputGadgets]
  lazy val Pages = interface[querki.pages.Pages]
  lazy val StatusLine = interface[StatusLine]
  lazy val StatusLineInternal = interface[StatusLineInternal]
  lazy val UserAccess = interface[querki.identity.UserAccess]

  var _displayRoot: Option[dom.Element] = None
  def displayRoot = _displayRoot.get

  var _window: Option[dom.Window] = None
  def window = _window.get

  override def init() = {
    // Adds the :notUnder() pseudo-selector to jQuery, which selects only elements that are not contained
    // by the specified selector. See:
    // http://stackoverflow.com/questions/965816/what-jquery-selector-excludes-items-with-a-parent-that-matches-a-given-selector
    // TODO: where does this jQuery hack belong?
    $.expr.`:`.notUnder = { (elem: dom.Element, i: js.Any, m: js.Array[String]) =>
      $(elem).parents(m(3)).length < 1;
    }
  }

  private var _nextChangePromise: Option[Promise[Page]] = None

  def nextChangeFuture: Future[Page] = {
    if (_nextChangePromise.isEmpty)
      _nextChangePromise = Some(Promise[Page])
    _nextChangePromise.get.future
  }

  val afterPageLoads = new Notifier[Page] {}
  val beforePageLoads = new Notifier[Page] {}

  val menuHolder = new WrapperDiv

  def update(title: String) = {
    _window.foreach { w => w.document.title = title }
    menuHolder.replaceContents((new MenuBar(DataAccess.std)).render)
  }

  case class FlashContents(
    isError: Boolean,
    mods: Modifier*
  )
  val waitingFlash = Var[Option[FlashContents]](None)

  /**
   * Declare the top-level container that we are going to render the page into. This
   * is typically going to be the body itself, but we're deliberately not assuming that.
   */
  @JSExport
  def setRoot(
    windowIn: dom.Window,
    root: dom.Element
  ): Future[Page] = {
    _displayRoot = Some(root)
    _window = Some(windowIn)

    // Whenever the hash changes, update the window. This is the main mechanism for navigation
    // within the client!
    $(windowIn).on(
      "hashchange",
      { e: dom.Element =>
        // Suppress redundant hashchange events; code should use reload() to force that.
        if (window.location.hash != currentHash)
          invokeFromHash()
      }
    )

    // The system should all be booted, so let's go render. But first, check whether we should
    // show the activation reminder:
    if (DataAccess.request.userLevel == PendingUser) {
      waitingFlash() = Some(FlashContents(
        false,
        """An activation email has been sent to your address; please click on the link in that
          |email to finish activating your account. Some Querki functions will be restricted until
          |you do so. """.stripMargin,
        UserAccess.resendActivationButton
      ))
    }

    def doInitialNavigate() = {
      DataAccess.request.navigateToOpt match {
        // We've been told by the server to load a specific page at start. For example,
        // this is used for Unsubscribe:
        case Some(navigateTo) => showPage(navigateTo, Map.empty)
        // Normal case -- just load whatever page the URL says:
        case _ => invokeFromHash()
      }
    }

    // ... check if we need a fresh TOS:
    // TODO: this is conceptually a lousy place for this check, but it's the right place in the
    // pipeline to do "stuff after loading". Can we find a better factoring?
    if (DataAccess.request.user.isDefined) {
      Client[UserFunctions].checkTOS().call().flatMap {
        _ match {
          case TOSOkay => doInitialNavigate()
          case TOSOld  => TOSPage.run.flatMap { _ => doInitialNavigate() }
        }
      }
    } else
      // Anonymous access, so TOS is irrelevant:
      doInitialNavigate()
  }

  var _imagePath: Option[String] = None
  def imagePath = _imagePath.get

  @JSExport
  def setImagePath(path: String) = {
    _imagePath = Some(path)
  }

  var _currentHash = ""

  def currentHash = _currentHash

  def reload(): Future[Page] = {
    val fut = nextChangeFuture
    invokeFromHash()
    fut
  }

  def fullReload(): Unit = {
    window.location.reload(true)
  }

  /**
   * Based on the hash part of the current location, load the appropriate page.
   */
  def invokeFromHash(): Future[Page] = {
    val fullHash = window.location.hash

    if (fullHash.length == 0)
      showRoot()
    else
      // Before we "navigate", give any outstanding InputGadgets a chance to save their values:
      InputGadgets.afterAllSaved.flatMap { dummy =>
        _currentHash = fullHash
        // Slice off the hash itself:
        // We now prefer the Google-favored style, where the AJAX hash begins with "!", but we
        // still cope with the original format without that.
        val hash =
          if (fullHash.startsWith("#!"))
            fullHash.substring(2)
          else
            fullHash.substring(1)
        val hashParts = hash.split("\\?")
        if (hashParts.length == 0)
          // There is a hash, but nothing else, so it's presumptively root:
          showRoot()
        else {
          val pageName = decode(hashParts(0))
          val pageParams =
            if (hashParts.length == 1)
              None
            else
              Some(hashParts(1).split("&"))
          val paramMap = pageParams match {
            case Some(params) => {
              val pairs = params.map { param =>
                val pairArray = param.split("=")
                val key = decode(pairArray(0))
                val v =
                  if (pairArray.length == 1)
                    ""
                  else
                    decode(pairArray(1))
                (key, v)
              }
              Map(pairs: _*)
            }
            case None => Map.empty[String, String]
          }
          currentPageName = pageName

          if (pageName.length == 0)
            showRoot(paramMap)
          else
            renderPage(pageName, paramMap)
        }
      }
  }

  def encode(str: String) = js.URIUtils.encodeURIComponent(str)

  // TODO: this problem comes from the fact that the server-side SafeUrl, used in Tag links,
  // uses java.net.URLEncoder, which uses + for spaces. But decodeURIComponent does *not* do
  // that, so we have to hack it a bit. *Sigh*.
  def decode(str: String) = {
    val plusedStr = str.replaceAllLiterally("+", " ")
    js.URIUtils.decodeURIComponent(plusedStr)
  }

  def pageUrl(
    pageName: String,
    paramMap: ParamMap = Map.empty
  ): URL = {
    val paramStr =
      if (paramMap.isEmpty)
        ""
      else
        "?" + paramMap.map(pair => s"${encode(pair._1)}=${encode(pair._2)}").mkString("&")
    s"#!$pageName$paramStr"
  }

  def showPage(
    pageName: String,
    paramMap: ParamMap
  ): Future[Page] = {
    val fut = nextChangeFuture
    window.location.hash = pageUrl(pageName, paramMap)
    fut
  }

  def showRoot(paramMap: ParamMap = Map.empty): Future[Page] = {
    DataAccess.space match {
      case Some(space) => {
        // TBD: in principle, this ought to be going through a factory for the Space Thing.
        // But we don't actually *have* a factory for Thing pages. Should we?
        showPage(space.urlName.underlying, paramMap)
      }
      case None => {
        Pages.indexFactory.showPage()
      }
    }
  }

  var currentPageName: String = ""

  var currentParams: ParamMap = Map.empty
  def currentPageParams: ParamMap = currentParams

  def renderPage(
    pageName: String,
    paramMap: ParamMap
  ): Future[Page] = {
    try {
      currentParams = paramMap
      Pages.constructPage(pageName, paramMap) match {
        case Some(page) => renderPage(page)
        // This happens when we have a ThingPage's name, but we're not actually in a Space.
        // So fall back to Index:
        case None => Pages.indexFactory.showPage().andThen {
            case util.Success(p: Page) =>
              StatusLine.showBriefly(s"Either that Space doesn't exist, or you aren't allowed to read it.")
          }
      }
    } catch {
      case MissingPageParameterException(paramName) => {
        StatusLine.showUntilChange(s"Missing required page parameter '$paramName'")
        Future.failed(new Exception(s"Missing page parameter $paramName"))
      }
      case ex: Exception => {
        println(s"Exception trying to render page $pageName")
        ex.printStackTrace()
        StatusLine.showUntilChange(s"Error while trying to display page $pageName")
        Future.failed(ex)
      }
    }
  }

  lazy val defaultCtx: Ctx.Owner = Ctx.Owner.safe()
  val currentPageRx: Var[Option[Page]] = Var(None)

  def changeToPage(page: Page): Unit = {
    currentPageRx.now.foreach { _.unload }
    currentPageRx() = Some(page)
  }
  implicit def currentOwner: Ctx.Owner = currentPageRx.now.map(_.ctx).getOrElse(defaultCtx)

  /**
   * Actually display the full page.
   */
  def renderPage(page: Page): Future[Page] = {
    val fut = nextChangeFuture

    // Yes, this is icky and mutable-ish. Note sure how better to handle this requirement, though.
    waitingFlash.now.map { f => page.flash(f.isError, f.mods) }
    waitingFlash() = None

    changeToPage(page)

    for {
      std <- DataAccess.standardThings
      _ = beforePageLoads(page)
      fullPage =
        div(
          StatusLineInternal.statusGadget,
          menuHolder(new MenuBar(std)),
          page,
          new StandardFooter
        )
      _ <- page.beforeRenderInternal()
    } {
      $(displayRoot).empty()
      $(displayRoot).append(fullPage.render)
    }

    // Note that onPageRendered doesn't get called here, because most Pages involve
    // async calls to the server. When the Page is actually finished loading and
    // rendering, it will call onPageRendered().

    fut
  }

  def onPageRendered(page: Page) = {
    afterPageLoads(page)
    // Need to do the dance here, because these things can run synchronously during testing:
    val oldPromise = _nextChangePromise
    _nextChangePromise = None
    oldPromise.foreach { _.success(page) }
  }

  def instantScrollToBottom() = {
    val document = window.document
    $("html, body").scrollTop(($(document).height() - $(window).height()).toInt);
  }

  def showIndexPage() = {
    navigateTo("/")
  }

  def navigateTo(url: String) = {
    if (url.startsWith("#") && window.location.href.endsWith(url))
      // It's the same Page, and our href-based system won't pick that up
      // as a "navigate". This is relevant for reloading dynamic pages in
      // _QLButton and the like.
      reload()
    else
      window.location.href = url
  }

  def modifyPageParams(f: ParamMap => ParamMap): Unit = {
    val newParams = f(currentParams)
    val paramsHash = newParams.toSeq.map { case (k, v) => s"$k=$v" }.mkString("&")
    // Note that we set these *before* setting the window.location.hash, so that this
    // is perceived as a no-op:
    _currentHash = "#!" + currentPageName + "?" + paramsHash
    currentParams = newParams
    window.location.hash = currentHash
  }
}
