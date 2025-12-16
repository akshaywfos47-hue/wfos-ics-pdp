package wfos.lgriphcd

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import cps.compat.FutureAsync.*
import wfos.lgriphcd.LgripConfigService._

@main
def LgripConfigServiceApp(): Unit = {
  println("Starting LGRIP Configuration Service...")

  createConfig().onComplete {
    case Success(_) =>
      checkConfigExists().onComplete {
        case Success(true) =>
          getConfig().onComplete {
            case Success(config) =>
              println(s"Initial Config:\n$config")

              val updatedConfig =
                """
                  |exchangePosition = 200
                  |homePosition = 0
                  |currentPosition = 50
                  |minTargetPosition = 0
                  |maxTargetPosition = 100
                  |""".stripMargin

              updateConfig(updatedConfig).onComplete {
                case Success(_) =>
                  println("Config updated successfully")
                case Failure(e) =>
                  println(s"Failed to update config: ${e.getMessage}")
              }

            case Failure(e) =>
              println(s"Failed to retrieve config: ${e.getMessage}")
          }

        case Success(false) =>
          println("Config file does not exist")

        case Failure(e) =>
          println(s"Failed to check config existence: ${e.getMessage}")
      }

    case Failure(e) =>
      println(s"Failed to create config: ${e.getMessage}")
  }
}
