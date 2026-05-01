package wfos.lgmhcd

import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.prefix.models.Prefix
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Await
import scala.concurrent.duration._

import csw.params.commands.{Setup, Observe, CommandName}
import csw.params.commands.CommandResponse._
import csw.params.commands.CommandIssue._
import csw.command.client.CommandServiceFactory
import csw.logging.client.scaladsl.LoggingSystemFactory
import csw.params.events.{EventKey, EventName, SystemEvent}
import csw.params.core.generics.Parameter
import wfos.lgmhcd.LgmInfo

class LgmhcdTest extends ScalaTestFrameworkTestKit(AlarmServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    spawnStandalone(com.typesafe.config.ConfigFactory.load("LgmhcdStandalone.conf"))
    LoggingSystemFactory.forTestingOnly()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  // FSD 8.5.1 – HCD should be locatable using Location Service
  test("LgmhcdTest: HCD should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgmhcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }

  // FSD 8.5.2 – HCD should not accept Observe commands
  test("LgmhcdTest: HCD should not accept Observe commands") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgmhcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgmHcdCS = CommandServiceFactory.make(pekkoLocation)

    val command: Observe = Observe(Prefix("wfos.bgrxAssembly.lgmhcd"), CommandName("move"), Some(LgmInfo.obsId))
    val response         = Await.result(lgmHcdCS.submit(command), 5000.millis)

    response.asInstanceOf[Invalid].issue shouldBe a[WrongCommandTypeIssue]
  }

  // FSD 8.5.3 – HCD should be able to validate a Setup command and return Completed type response
  test("LgmhcdTest: HCD should be able to validate a Setup command and return Completed type response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgmhcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgmHcdCS = CommandServiceFactory.make(pekkoLocation)

    // 432.5 mm aligns bgid1 slot (67.5 mm linear distance) at exchange (500 mm)
    val targetPos: Parameter[Double] = LgmInfo.targetGratingPositionKey.set(432.5)
    val command: Setup               = Setup(Prefix("wfos.bgrxAssembly.lgmhcd"), CommandName("move"), Some(LgmInfo.obsId)).madd(targetPos)

    val response = Await.result(lgmHcdCS.submit(command), 30.seconds)
    response shouldBe a[Completed]
  }

  // FSD 8.5.4 – HCD validates the targetGratingPosition parameter and returns Invalid if outside 0–500 mm
  test("LgmhcdTest: HCD validates the targetGratingPosition parameter and returns Invalid if it is outside the range 0-500 mm") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgmhcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgmHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetPos: Parameter[Double] = LgmInfo.targetGratingPositionKey.set(600.0)
    val command: Setup               = Setup(Prefix("wfos.bgrxAssembly.lgmhcd"), CommandName("move"), Some(LgmInfo.obsId)).madd(targetPos)

    val response = Await.result(lgmHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[ParameterValueOutOfRangeIssue]
  }

  // FSD 8.5.5 – HCD validates the command name and returns Invalid if it is not move
  test("LgmhcdTest: HCD validates the command name and returns Invalid if it is not move") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgmhcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgmHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetPos: Parameter[Double] = LgmInfo.targetGratingPositionKey.set(250.0)
    val command: Setup               = Setup(Prefix("wfos.bgrxAssembly.lgmhcd"), CommandName("shift"), Some(LgmInfo.obsId)).madd(targetPos)

    val response = Await.result(lgmHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[UnsupportedCommandIssue]
  }

  // FSD 8.5.6 – HCD should publish a status event on command execution
  test("LgmhcdTest: HCD should publish a status event on command execution") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgmhcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgmHcdCS = CommandServiceFactory.make(pekkoLocation)

    var eventReceived = false

    val testSubscriber = eventService.defaultSubscriber
    testSubscriber.subscribeCallback(
      Set(EventKey(Prefix("wfos.lgmhcd"), EventName("LgmHcd_status"))),
      event => {
        event shouldBe a[SystemEvent]
        eventReceived = true
      }
    )

    // 250mm is the initial state, send a valid in-range position
    val targetPos: Parameter[Double] = LgmInfo.targetGratingPositionKey.set(250.0)
    val command: Setup               = Setup(Prefix("wfos.bgrxAssembly.lgmhcd"), CommandName("move"), Some(LgmInfo.obsId)).madd(targetPos)

    val response = Await.result(lgmHcdCS.submit(command), 10.seconds)
    response shouldBe a[Completed]
    Thread.sleep(500)
    assert(eventReceived, "LgmHcd_status event should have been published on command execution")
  }
}
