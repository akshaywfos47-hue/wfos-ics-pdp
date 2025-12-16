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

class WfosContainerTest extends ScalaTestFrameworkTestKit(LocationServer, EventServer) with AnyFunSuiteLike {
  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    // uncomment if you want one Assembly run for all tests
    spawnContainer(com.typesafe.config.ConfigFactory.load("wfosContainer.conf"))
    LoggingSystemFactory.forTestingOnly(): Unit
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  test("All components in the container should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly"), ComponentType.Assembly))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val rConnection    = PekkoConnection(ComponentId(Prefix("wfos.rgriphcd"), ComponentType.HCD))
    val rPekkoLocation = Await.result(locationService.resolve(rConnection, 10.seconds), 10.seconds).get

    val lConnection    = PekkoConnection(ComponentId(Prefix("wfos.lgriphcd"), ComponentType.HCD))
    val lPekkoLocation = Await.result(locationService.resolve(lConnection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
    rPekkoLocation.connection shouldBe rConnection
    lPekkoLocation.connection shouldBe lConnection
  }

  test("Assembly should be able to accept Setup commands of move type and execute them successfully") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly"), ComponentType.Assembly))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(30)
    val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid3")
    val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)

    val bgrxCS         = CommandServiceFactory.make(pekkoLocation)
    val command: Setup = Setup(Prefix("wfos.bgrxAssembly"), CommandName("move"), Some(RgripInfo.obsId)).madd(targetAngle, gratingMode, cw)

    // val response = bgrxCS.submit(command)
    // Thread.sleep(5000)
    // response shouldBe a[Started]

    val response = Await.result(bgrxCS.submit(command), 5000.millis)
    response shouldBe a[Started]
  }
}
