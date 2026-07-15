package wfos.bgrxassembly.api

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class BgrxHttpServer(
    system: ActorSystem[?],
    routes: BgrxRoutes
) {
  private implicit val ec: ExecutionContext =
    system.executionContext
  def start(): Unit = {

    implicit val actorSystem: ActorSystem[?] = system
    Http()
      .newServerAt(
        BgrxHttpConfig.host,
        BgrxHttpConfig.port
      )
      .bind(routes.routes)
      .onComplete {
        case Success(binding) =>
          println(s"HTTP Server started at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}")

        case Failure(ex) =>
          println(s"Failed to start HTTP Server: ${ex.getMessage}")
      }

  }
}
