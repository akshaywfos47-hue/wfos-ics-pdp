package wfos.lgriphcd

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import csw.config.api.scaladsl.{ConfigClientService, ConfigService}
import csw.config.client.scaladsl.ConfigClientFactory
import csw.config.api.{ConfigData, TokenFactory}
import com.typesafe.config.{Config, ConfigFactory}
import java.nio.file.Paths
import scala.concurrent.{ExecutionContextExecutor, Future}

object LgripConfigService {
  // ActorSystem and ExecutionContext
  implicit val actorSystem: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "LgripConfigSystem")
  implicit val ec: ExecutionContextExecutor      = actorSystem.executionContext

  // LocationService
  private val locationService = csw.location.client.scaladsl.HttpLocationServiceFactory.makeLocalClient(actorSystem)

  // Create TokenFactory instance
  private val tokenFactory = TokenFactory()

  // Client API and Admin API
  private val clientApi: ConfigClientService = ConfigClientFactory.clientApi(actorSystem, locationService)
  private val adminApi: ConfigService        = ConfigClientFactory.adminApi(actorSystem, locationService, tokenFactory)

  // File path and default config
  private val filePath = Paths.get("/wfos/lgriphcd/lgrip-config.conf")
  private val defaultConfig: String =
    """
      |exchangePosition = 100
      |homePosition = 0
      |currentPosition = 0
      |minTargetPosition = 0
      |maxTargetPosition = 100
      |""".stripMargin

  // Create configuration file
  def createConfig(): Future[Unit] = {
    val configContent = ConfigFactory.parseString(defaultConfig).root().render()
    val configData    = ConfigData.fromString(configContent)
    adminApi
      .create(
        filePath,
        configData,
        annex = false,
        comment = "Initial configuration"
      )
      .map { _ =>
        println(s"Config file created at: $filePath")
      }
  }

  // Check if the configuration file exists
  def checkConfigExists(): Future[Boolean] = {
    clientApi.exists(filePath).map { exists =>
      println(s"Config file exists: $exists")
      exists
    }
  }

  // Retrieve the configuration file content
  def getConfig(): Future[String] = {
    clientApi
      .getActive(filePath)
      .flatMap {
        case Some(configData) => configData.toStringF
        case None             => Future.failed(new Exception("Config file not found"))
      }
      .map { config =>
        println(s"Retrieved config:\n$config")
        config
      }
  }

  // Update configuration file
  def updateConfig(newConfig: String): Future[Unit] = {
    val configData = ConfigData.fromString(newConfig)
    adminApi
      .update(
        filePath,
        configData,
        comment = "Updated configuration"
      )
      .map { _ =>
        println("Config file updated successfully")
      }
  }
}