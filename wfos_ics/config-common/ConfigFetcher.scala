package config_common

import csw.config.api.scaladsl.ConfigClientService
import csw.location.api.scaladsl.LocationService
import csw.config.client.scaladsl.ConfigClientFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.StreamConverters

import java.io.InputStream
import java.nio.file.Path
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import cps.compat.FutureAsync.*

object ConfigFetcher {

  /**
   * Fetches a configuration file from Configuration Service
   * and returns it as an InputStream.
   */
  def fetch(
             path: Path,
             actorSystem: ActorSystem[?],
             locationService: LocationService
           ): InputStream = {

    println(s"[ConfigFetcher] Fetching file: $path")

    given ActorSystem[?] = actorSystem
    given Materializer   = Materializer(actorSystem)
    given ExecutionContext = actorSystem.executionContext

    val clientApi: ConfigClientService =
      ConfigClientFactory.clientApi(actorSystem, locationService)

    val future = async {

      println("[ConfigFetcher] Calling Configuration Service...")

      val maybeConfig =
        await(clientApi.getActive(path))

      maybeConfig match {

        case Some(configData) =>
          println(s"[ConfigFetcher] File found. Size = ${configData.length} bytes")
          println("[ConfigFetcher] Returning InputStream")

          configData.source.runWith(StreamConverters.asInputStream())

        case None =>
          println(s"[ConfigFetcher] File not found: $path")

          throw new RuntimeException(
            s"Configuration file not found: $path"
          )
      }
    }

    val inputStream =
      Await.result(future, 30.seconds)

    println("[ConfigFetcher] Fetch completed successfully")

    inputStream
  }

}