package wfos.lgriphcd

import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.prefix.models.Prefix
import csw.testkit.scaladsl.CSWService.{LocationServer, EventServer}
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
import csw.params.core.generics.{Parameter, Key, KeyType}
import wfos.lgriphcd.LgripInfo

class LgripHcdTest extends ScalaTestFrameworkTestKit(LocationServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    // uncomment if you want one HCD run for all tests
    spawnStandalone(com.typesafe.config.ConfigFactory.load("LgripHcdStandalone.conf"))
    LoggingSystemFactory.forTestingOnly()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  test("LgripHcdTest: HCD should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }

  test("LgripHcdTest: HCD should not accept Observe commands") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val command: Observe = Observe(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("move"), Some(LgripInfo.obsId))
    val response         = Await.result(lgripHcdCS.submit(command), 5000.millis)

    response.asInstanceOf[Invalid].issue shouldBe a[WrongCommandTypeIssue]
  }

  // FSD 8.4.3 – HCD should be able to validate a Setup command and return Completed type response
  test("LgripHcdTest: HCD should be able to validate a Setup command and return Completed type response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetPosition: Parameter[Int] = LgripInfo.targetPositionKey.set(1000)
    val command: Setup = Setup(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("move"), Some(LgripInfo.obsId)).madd(targetPosition)

    val response = Await.result(lgripHcdCS.submit(command), 30.seconds)
    response shouldBe a[Completed]
  }

  // FSD 8.4.4 – HCD validates the targetPosition parameter and returns Invalid if outside 0–1000 mm
  test("LgripHcdTest: HCD validates the targetPosition parameter and returns Invalid if it is outside the range 0-1000 mm") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetPosition: Parameter[Int] = LgripInfo.targetPositionKey.set(1500)
    val command: Setup = Setup(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("move"), Some(LgripInfo.obsId)).madd(targetPosition)

    val response = Await.result(lgripHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[ParameterValueOutOfRangeIssue]
  }

  // FSD 8.4.5 – HCD validates the command name and returns Invalid if it is not move
  test("LgripHcdTest: HCD validates the command name and returns Invalid if it is not move") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val targetPosition: Parameter[Int] = LgripInfo.targetPositionKey.set(500)
    val command: Setup = Setup(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("extend"), Some(LgripInfo.obsId)).madd(targetPosition)

    val response = Await.result(lgripHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[UnsupportedCommandIssue]
  }

  // FSD 8.4.6 – HCD should publish a status event on command execution
  test("LgripHcdTest: HCD should publish a status event on command execution") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    var eventReceived = false

    val testSubscriber = eventService.defaultSubscriber
    testSubscriber.subscribeCallback(
      Set(EventKey(Prefix("wfos.lgriphcd"), EventName("lgripStateEvent"))),
      event => {
        event shouldBe a[SystemEvent]
        eventReceived = true
      }
    )

    val targetPosition: Parameter[Int] = LgripInfo.targetPositionKey.set(0)
    val command: Setup = Setup(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("move"), Some(LgripInfo.obsId)).madd(targetPosition)

    val response = Await.result(lgripHcdCS.submit(command), 10.seconds)
    response shouldBe a[Completed]
    Thread.sleep(500)
    assert(eventReceived, "lgripStateEvent should have been published on command execution")
  }

}
