package wfos.lgriphcd

import scala.util.{Failure, Success}

object LgripConfigServiceApp extends App {
  println("Starting LGRIP Configuration Service...")
  import wfos.lgriphcd.LgripConfigService._

  // Create configuration
  createConfig().onComplete {
    case Success(_) =>
      // Check if the configuration file exists
      checkConfigExists().onComplete {
        case Success(true) =>
          // Retrieve the configuration
          getConfig().onComplete {
            case Success(config) =>
              println(s"Initial Config:\n$config")

              // Update the configuration
              val updatedConfig =
                """
                  |exchangePosition = 200
                  |homePosition = 0
                  |currentPosition = 50
                  |minTargetPosition = 0
                  |maxTargetPosition = 100
                  |""".stripMargin

              updateConfig(updatedConfig).onComplete {
                case Success(_) => println("Config updated successfully")
                case Failure(e) => println(s"Failed to update config: ${e.getMessage}")
              }

            case Failure(e) => println(s"Failed to retrieve config: ${e.getMessage}")
          }

        case Success(false) => println("Config file does not exist")
        case Failure(e)     => println(s"Failed to check config existence: ${e.getMessage}")
      }

    case Failure(e) => println(s"Failed to create config: ${e.getMessage}")
  }
}