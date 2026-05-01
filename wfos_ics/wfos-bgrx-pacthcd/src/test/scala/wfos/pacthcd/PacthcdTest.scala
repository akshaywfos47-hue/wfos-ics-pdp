package wfos.pacthcd

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
import wfos.pacthcd.PactInfo

class PacthcdTest extends ScalaTestFrameworkTestKit(AlarmServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    spawnStandalone(com.typesafe.config.ConfigFactory.load("PacthcdStandalone.conf"))
    LoggingSystemFactory.forTestingOnly()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  // FSD 8.6.1 – HCD should be locatable using Location Service
  test("PacthcdTest: HCD should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }

  // FSD 8.6.2 – HCD should not accept Observe commands
  test("PacthcdTest: HCD should not accept Observe commands") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val pactHcdCS = CommandServiceFactory.make(pekkoLocation)

    val command: Observe = Observe(Prefix("wfos.bgrxAssembly.pacthcd"), CommandName("move"), Some(PactInfo.obsId))
    val response         = Await.result(pactHcdCS.submit(command), 5000.millis)

    response.asInstanceOf[Invalid].issue shouldBe a[WrongCommandTypeIssue]
  }

  // FSD 8.6.3 – HCD should be able to validate a Setup command and return Completed type response
  test("PacthcdTest: HCD should be able to validate a Setup command and return Completed type response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val pactHcdCS = CommandServiceFactory.make(pekkoLocation)

    // Initial state is OUT (500.0 mm); move to IN (0.0 mm) – valid target
    val targetPos: Parameter[Double] = PactInfo.targetPositionKey.set(0.0)
    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.pacthcd"), CommandName("move"), Some(PactInfo.obsId)).madd(targetPos)

    val response = Await.result(pactHcdCS.submit(command), 30.seconds)
    response shouldBe a[Completed]
  }

  // FSD 8.6.4 – HCD validates the targetPosition parameter and returns Invalid if outside 0–500 mm
  test("PacthcdTest: HCD validates the targetPosition parameter and returns Invalid if it is outside the range 0-500 mm") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val pactHcdCS = CommandServiceFactory.make(pekkoLocation)

    // 250.0 mm is neither IN (0.0) nor OUT (500.0) – must be rejected
    val targetPos: Parameter[Double] = PactInfo.targetPositionKey.set(750.0)
    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.pacthcd"), CommandName("move"), Some(PactInfo.obsId)).madd(targetPos)

    val response = Await.result(pactHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[ParameterValueOutOfRangeIssue]
  }

  // FSD 8.6.5 – HCD validates the command name and returns Invalid if it is not move
  test("PacthcdTest: HCD validates the command name and returns Invalid if it is not move") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val pactHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetPos: Parameter[Double] = PactInfo.targetPositionKey.set(0.0)
    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.pacthcd"), CommandName("push"), Some(PactInfo.obsId)).madd(targetPos)

    val response = Await.result(pactHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[UnsupportedCommandIssue]
  }

  // FSD 8.6.6 – HCD should publish a status event on command execution
  test("PacthcdTest: HCD should publish a status event on command execution") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val pactHcdCS = CommandServiceFactory.make(pekkoLocation)

    var eventReceived = false

    val testSubscriber = eventService.defaultSubscriber
    testSubscriber.subscribeCallback(
      Set(EventKey(Prefix("wfos.pacthcd"), EventName("PactStatusEvent"))),
      event => {
        event shouldBe a[SystemEvent]
        eventReceived = true
      }
    )

    // Move to OUT (500.0 mm) – valid target; pact may already be at OUT after test 3 moved it to IN
    val targetPos: Parameter[Double] = PactInfo.targetPositionKey.set(500.0)
    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.pacthcd"), CommandName("move"), Some(PactInfo.obsId)).madd(targetPos)

    val response = Await.result(pactHcdCS.submit(command), 30.seconds)
    response shouldBe a[Completed]
    Thread.sleep(500)
    assert(eventReceived, "PactStatusEvent should have been published on command execution")
  }

  // FSD 8.6.7 – HCD should validate the operation parameter and return Invalid if it is not PUSH or PULL
  // NOTE: This test documents a FSD requirement. The handler must be updated to validate the
  // operation key (PactInfo.operationKey) and reject values other than "push" / "pull".
  test("PacthcdTest: HCD should validate the operation parameter and return Invalid if it is not PUSH or PULL") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val pactHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetPos: Parameter[Double] = PactInfo.targetPositionKey.set(0.0)
    val operation: Parameter[String] = PactInfo.operationKey.set("RANDOM")
    val command: Setup =
      Setup(Prefix("wfos.bgrxAssembly.pacthcd"), CommandName("move"), Some(PactInfo.obsId))
        .madd(targetPos, operation)

    val response = Await.result(pactHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[WrongParameterTypeIssue]
  }
}
