package wfos.rgripHcd

import org.slf4j.LoggerFactory
import csw.params.core.generics.{Key, KeyType}
import csw.params.events.{EventKey, EventName, SystemEvent}
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.prefix.models.Prefix
import csw.testkit.scaladsl.CSWService.{LocationServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Await
import scala.concurrent.duration._

import csw.params.core.generics.{Parameter}
import csw.params.commands.{Setup, Observe, CommandName}
import csw.params.commands.CommandResponse._
import csw.params.commands.CommandIssue._
import csw.command.client.CommandServiceFactory
import csw.logging.client.scaladsl.LoggingSystemFactory
import wfos.rgriphcd.RgripInfo

// Since our current code uses only location service and event service we will start only those services.
// This is done By passing in the needed services in the constructor,
// those services are started in the superclass’s(i.e., ScalaTestFrameworkTestKit) beforeAll method
class RgripHcdTest extends ScalaTestFrameworkTestKit(LocationServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    // uncomment if you want one HCD run for all tests
    spawnStandalone(com.typesafe.config.ConfigFactory.load("RgriphcdStandalone.conf"))

    LoggingSystemFactory.forTestingOnly()
  }

  test("RgripHcdTest: HCD should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.rgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }

  test("RgripHcdTest: HCD should not accept Observe commands") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.rgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val rgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val command: Observe = Observe(Prefix("wfos.bgrxAssembly.rgriphcd"), CommandName("move"), Some(RgripInfo.obsId))
    val response         = Await.result(rgripHcdCS.submit(command), 5000.millis)

    response.asInstanceOf[Invalid].issue shouldBe a[WrongCommandTypeIssue]
  }

  test("RgripHcdTest: HCD should be able to validate a Setup command and return Completed type response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.rgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val rgripHcdCS = CommandServiceFactory.make(pekkoLocation)
    val log        = LoggerFactory.getLogger(getClass)

    val expectedStageKey: Key[String]    = KeyType.StringKey.make("expectedStage")
    val expectedStage: Parameter[String] = expectedStageKey.set("Validation")

    val expectedStatusKey: Key[String]    = KeyType.StringKey.make("expectedStatus")
    val expectedStatus: Parameter[String] = expectedStatusKey.set("Failure")

    val params: Set[Parameter[_]] = Set(expectedStage, expectedStatus)

    val testSubscriber = eventService.defaultSubscriber
    testSubscriber.subscribeCallback(
      Set(EventKey(Prefix("wfos.bgrxAssembly.rgriphcd"), EventName("RgripHcd_status"))),
      event => {
        log.info("Rgrip test case 3 Event is Trigerred")
        event shouldBe a[SystemEvent]
        event.paramSet shouldBe params
      }
    )

    val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(28)
    val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid3")
    val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)

    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.rgriphcd"), CommandName("move"), Some(RgripInfo.obsId)).madd(targetAngle, gratingMode, cw)

    val response = Await.result(rgripHcdCS.submit(command), 5000.millis)
    response shouldBe a[Completed]
  }

  // FSD 8.3.4 – HCD validates the targetAngle parameter and returns Invalid if outside 0–55 degrees
  test("RgripHcdTest: HCD validates the targetAngle parameter and returns Invalid if it is outside the range 0-55 degrees") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.rgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val rgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetAngle: Parameter[Int] = RgripInfo.targetAngleKey.set(60)

    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.rgriphcd"), CommandName("move"), Some(RgripInfo.obsId)).madd(targetAngle)

    val response = Await.result(rgripHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[ParameterValueOutOfRangeIssue]
  }

  // FSD 8.3.5 – HCD validates the command name and returns Invalid if it is not move
  test("RgripHcdTest: HCD validates the command name and returns Invalid if it is not move") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.rgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val rgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetAngle: Parameter[Int] = RgripInfo.targetAngleKey.set(30)

    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.rgriphcd"), CommandName("rotate"), Some(RgripInfo.obsId)).madd(targetAngle)

    val response = Await.result(rgripHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[UnsupportedCommandIssue]
  }

  // FSD 8.3.6 – HCD should publish a status event on command execution
  test("RgripHcdTest: HCD should publish a status event on command execution") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.rgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val rgripHcdCS = CommandServiceFactory.make(pekkoLocation)
    val log        = LoggerFactory.getLogger(getClass)

    var eventReceived = false

    val testSubscriber = eventService.defaultSubscriber
    testSubscriber.subscribeCallback(
      Set(EventKey(Prefix("wfos.rgriphcd"), EventName("rgripStateEvent"))),
      event => {
        log.info("RgripHcd rgripStateEvent received")
        event shouldBe a[SystemEvent]
        eventReceived = true
      }
    )

    val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(28)
    val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid3")
    val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)

    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.rgriphcd"), CommandName("move"), Some(RgripInfo.obsId)).madd(targetAngle, gratingMode, cw)

    val response = Await.result(rgripHcdCS.submit(command), 10.seconds)
    response shouldBe a[Completed]
    Thread.sleep(500)
    assert(eventReceived, "rgripStateEvent should have been published on command execution")
  }

}
