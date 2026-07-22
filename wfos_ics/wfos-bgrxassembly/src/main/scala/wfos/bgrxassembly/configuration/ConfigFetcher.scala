package wfos.bgrxassembly.configuration

import cps.compat.FutureAsync.*
import csw.config.api.scaladsl.ConfigClientService
import csw.config.client.scaladsl.ConfigClientFactory
import csw.location.api.scaladsl.LocationService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.StreamConverters

import java.io.InputStream
import java.nio.file.Path
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

object ConfigFetcher {

  def fetch(
      actorSystem: ActorSystem[?],
      locationService: LocationService,
      path: Path
  ): InputStream = {

    given ActorSystem[?]   = actorSystem
    given Materializer     = Materializer(actorSystem)
    given ExecutionContext = actorSystem.executionContext

    val clientApi: ConfigClientService =
      ConfigClientFactory.clientApi(actorSystem, locationService)

    val future = async {

      val maybeConfig =
        await(clientApi.getActive(path))

      maybeConfig match {

        case Some(configData) =>
          configData.source.runWith(StreamConverters.asInputStream())

        case None =>
          throw new RuntimeException(
            s"Configuration file not found: $path"
          )
      }
    }

    Await.result(future, 30.seconds)
  }
}
