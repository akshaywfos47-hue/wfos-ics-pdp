package wfos.bgrxassembly

//import akka.actor.Status.Success
import csw.command.client.CommandServiceFactory
import csw.location.api.models.Connection.PekkoConnection
import csw.prefix.models.Prefix
import csw.location.api.models.{ComponentId, ComponentType}

import csw.params.commands.Setup
//import csw.prefix.models.Subsystem.WFOS
import csw.testkit.scaladsl.CSWService.{AlarmServer, LocationServer, EventServer}
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

class WfosContainerTest extends ScalaTestFrameworkTestKit(AlarmServer, LocationServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    System.setProperty("wfos.test.mode", "true")
    // uncomment if you want one Assembly run for all tests
    spawnContainer(com.typesafe.config.ConfigFactory.load("wfosContainer.conf"))
    LoggingSystemFactory.forTestingOnly()
  }

  override def afterAll(): Unit = {
    System.clearProperty("wfos.test.mode")
    super.afterAll()
  }

  // FSD 8.1.1 – All components in the container should be locatable using Location Service
  test("WfosContainerTest: All components in the container should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly"), ComponentType.Assembly))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    val rConnection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.rgriphcd"), ComponentType.HCD))
    val rPekkoLocation = Await.result(locationService.resolve(rConnection, 10.seconds), 10.seconds).get

    val lConnection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.lgriphcd"), ComponentType.HCD))
    val lPekkoLocation = Await.result(locationService.resolve(lConnection, 10.seconds), 10.seconds).get

    val lgmConnection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.lgmhcd"), ComponentType.HCD))
    val lgmPekkoLocation = Await.result(locationService.resolve(lgmConnection, 10.seconds), 10.seconds).get

    val pactConnection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.pacthcd"), ComponentType.HCD))
    val pactPekkoLocation = Await.result(locationService.resolve(pactConnection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
    rPekkoLocation.connection shouldBe rConnection
    lPekkoLocation.connection shouldBe lConnection
    lgmPekkoLocation.connection shouldBe lgmConnection
    pactPekkoLocation.connection shouldBe pactConnection
  }
}
