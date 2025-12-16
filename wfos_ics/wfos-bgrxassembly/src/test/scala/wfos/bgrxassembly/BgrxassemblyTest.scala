package wfos.bgrxassembly

//import akka.actor.Status.Success
import csw.command.client.CommandServiceFactory
import csw.location.api.models.Connection.PekkoConnection
import csw.prefix.models.Prefix
import csw.location.api.models.{ComponentId, ComponentType}

import csw.params.commands.Setup
//import csw.prefix.models.Subsystem.WFOS
import csw.testkit.scaladsl.CSWService.{LocationServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Await
import scala.concurrent.duration._

import csw.params.core.generics.{Parameter}
import csw.params.commands.{Observe, CommandName}
import csw.params.commands.CommandResponse._
import csw.params.commands.CommandIssue._
import csw.logging.client.scaladsl.LoggingSystemFactory
import wfos.rgriphcd.RgripInfo

class BgrxassemblyTest extends ScalaTestFrameworkTestKit(LocationServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    // uncomment if you want one Assembly run for all tests
    spawnStandalone(com.typesafe.config.ConfigFactory.load("bgrxassemblyStandalone.conf"))
    LoggingSystemFactory.forTestingOnly(): Unit
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  test("Assembly should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly"), ComponentType.Assembly))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe (connection)
  }

  test("Assembly should not accept Observe commands and send Invalid Response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly"), ComponentType.Assembly))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val bgrxCS           = CommandServiceFactory.make(pekkoLocation)
    val command: Observe = Observe(Prefix("wfos.bgrxAssembly"), CommandName("move"), Some(RgripInfo.obsId))

    val response = Await.result(bgrxCS.submit(command), 5000.millis)
    response.asInstanceOf[Invalid].issue shouldBe a[WrongCommandTypeIssue]
  }

  test("Assembly should be able to validate Setup commands and send Invalid Response") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly"), ComponentType.Assembly))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(30)
    val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid6")
    val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)

    val command: Setup = Setup(Prefix("wfos.bgrxAssembly"), CommandName("move"), Some(RgripInfo.obsId)).madd(targetAngle, gratingMode, cw)

    val bgrxCS   = CommandServiceFactory.make(pekkoLocation)
    val response = Await.result(bgrxCS.submit(command), 5000.millis)

    response.asInstanceOf[Invalid].issue shouldBe a[WrongParameterTypeIssue]
  }
}
