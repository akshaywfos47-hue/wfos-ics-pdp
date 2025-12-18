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

  test("HCD should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }

  test("HCD should not accept Observe commands") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val command: Observe = Observe(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("move"), Some(LgripInfo.obsId))
    val response         = Await.result(lgripHcdCS.submit(command), 5000.millis)

    response.asInstanceOf[Invalid].issue shouldBe a[WrongCommandTypeIssue]
  }

  test("HCD should be able to validate a command and return Invalid type response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val expectedStageKey: Key[String]    = KeyType.StringKey.make("expectedStage")
    val expectedStage: Parameter[String] = expectedStageKey.set("Validation")

    val expectedStatusKey: Key[String]    = KeyType.StringKey.make("expectedStatus")
    val expectedStatus: Parameter[String] = expectedStatusKey.set("Failure")

    val params: Set[Parameter[_]] = Set(expectedStage, expectedStatus)

    val testSubscriber = eventService.defaultSubscriber
    testSubscriber.subscribeCallback(
      Set(EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("LgripHcd_status"))),
      event => {
        event shouldBe a[SystemEvent]
        event.paramSet shouldBe params
      }
    )

    val targetPosition: Parameter[Int] = LgripInfo.targetPositionKey.set(0)
    val command: Setup = Setup(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("move"), Some(LgripInfo.obsId)).madd(targetPosition)

    val response = Await.result(lgripHcdCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[ParameterValueOutOfRangeIssue]
  }

  test("HCD should be able to execute a command and return Completed response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val lgripHcdCS = CommandServiceFactory.make(pekkoLocation)

    val expectedStageKey: Key[String]    = KeyType.StringKey.make("expectedStage")
    val expectedStage: Parameter[String] = expectedStageKey.set("Setup")

    val expectedStatusKey: Key[String]    = KeyType.StringKey.make("expectedStatus")
    val expectedStatus: Parameter[String] = expectedStatusKey.set("Completion")

    val params: Set[Parameter[_]] = Set(expectedStage, expectedStatus)

    val testSubscriber = eventService.defaultSubscriber
    testSubscriber.subscribeCallback(
      Set(EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("LgripHcd_status"))),
      event => {
        event shouldBe a[SystemEvent]
        event.paramSet shouldBe params
      }
    )

    val targetPosition = LgripInfo.targetPositionKey.set(50)
    val command: Setup = Setup(Prefix("wfos.bgrxAssembly.lgriphcd"), CommandName("move"), Some(LgripInfo.obsId)).madd(targetPosition)

    val response = Await.result(lgripHcdCS.submit(command), 5000.millis)
    response shouldBe a[Completed]
  }

}
