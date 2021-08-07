package querki.test.functional

import play.api.{Application, ApplicationLoader, Environment}

import org.scalatest._
import org.scalatest.tags.Slow
import org.scalatest.selenium._
import play.api.test._
import play.api.test.Helpers._
import org.scalatestplus.play._

import querki.globals._
import querki.system.{QuerkiApplicationLoader, QuerkiRoot}

/**
 * Self-trait that the elements of the cake should typically use to access all the utilities.
 *
 * Note that we mix this in, *not* QuerkiFuncTests itself, to avoid circular dependency hell.
 * This keep recompiles down to a manageable level.
 */
trait FuncMixin
  extends WordSpec
     with Matchers
     with org.scalatest.concurrent.Eventually
     with WebBrowser
     with OneBrowserPerTest
     with OneServerPerTest
     with FuncDB
     with FuncInterfaces
     with FuncUtil
     with FuncInvites
     with EcologyMember

/**
 * The actual test runner. This defines the functional-test "cake", and the tests to run.
 *
 * See package.scala for lots more details.
 *
 * Disabled for now, since it isn't working.
 * TODO: replace this with a new suite that works better!
 */
//@Slow
@Ignore
class QuerkiFuncTests
// Infrastructure mix-ins, from ScalaTest and Play:
  extends WordSpec
     with Matchers
     with BeforeAndAfterAll
     with OneServerPerTest
     with OneBrowserPerTest
     // For now, we're just going to target Chrome. Eventually, obviously, we should
     // test this stuff cross-browser:
     with ChromeFactory
     with WebBrowser
     with concurrent.IntegrationPatience

     // Structural mix-ins for the tests:
     with FuncDB
     with FuncUtil
     with FuncMixin
     with EcologyMember

     // And the tests themselves:
     with BuildCommonSpace
     with Search
     with Security
     with Persistence
     with RegressionTests1 {

  override def beforeAll() {
    setupCassandra()
  }

  override def afterAll() {
    teardownCassandra()
  }

  /**
   * This is where we override the standard Application settings.
   */
  override implicit def newAppForTest(td: TestData): Application = {
    val context = ApplicationLoader.createContext(
      Environment.simple(),
      Map(
        // For the moment, the names of the test DBs are hardcoded. That will probably have to
        // change eventually.
        "db.system.url" -> "jdbc:mysql://localhost/test_system?characterEncoding=UTF-8",
        "db.user.url" -> "jdbc:mysql://localhost/test_user?characterEncoding=UTF-8",
        // Note that 19001 is the default port for OneServerPerTest, per:
        //   https://www.playframework.com/documentation/2.5.x/ScalaFunctionalTestingWithScalaTest
        "querki.app.urlRoot" -> "http://localhost:19001/",
        // Tell the Email Ecot to use the test version of the sender, which doesn't actually send
        // mail, but instead lets us inspect what has been "sent":
        "querki.mail.test" -> "true"
      )
    )

    // We run setupDatabase *during* load -- after Play initializes but before the Ecology:
    QuerkiApplicationLoader._preEcologyFunc = setupDatabase
    val app = new QuerkiApplicationLoader().load(context)

    app
  }

  var ecology: Ecology = null

  "Run the main functional tests" in {
    // Fetch the actual running Ecology, so that tests can introspect into the system.
    ecology = QuerkiRoot.ecology

    // Starting point: go to the Querki root page, not yet logged in.
    // 19001 is the default port used for Play Functional Testing. We can and probably
    // should change at at some point, but it's fine for now:
    go to "http://localhost:19001/"

    runTests(
      buildCommonSpace,
      runSearchTests,
      runSecurityTests,
      runPersistenceTests,
      // Regression Tests
      regression1
    )(InitialState)
  }
}

// Uncomment this and use it if ScalaTest is claiming that it "was unable to create a
// Selenium ChromeDriver on this platform". Unfortunately, ScalaTest turns *all* exceptions
// into that error, suppressing ChromeDriver's often-helpful exceptions.
//
//class DriverTests
//  extends WordSpec
//  with Matchers
//  with ChromeFactory
//{
//  "I should be able to get a decent error" in {
//    import org.openqa.selenium.chrome.ChromeDriver
//
//    System.setProperty("webdriver.chrome.driver", "/usr/bin/chromedriver")
//    new ChromeDriver()
//  }
//}
