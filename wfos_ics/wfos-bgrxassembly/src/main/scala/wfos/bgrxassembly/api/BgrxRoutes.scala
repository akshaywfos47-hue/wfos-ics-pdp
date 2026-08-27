package wfos.bgrxassembly.api

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.cors.scaladsl.CorsDirectives._
import wfos.bgrxassembly.SequenceType
import wfos.bgrxassembly.models.SequenceCommandRequest

class BgrxRoutes(service: BgrxApiService) {

  val routes: Route =
    cors() {
      pathPrefix("bgrx") {

        concat(
          path("hello") {
            get {
              complete("Hello from Bgrx Assembly!")
            }
          },
          path("homing") {
            post {
              val request = SequenceCommandRequest(
                sequence = SequenceType.HOME,
                requestedBgid = None,
                observationAngle = None,
                cw = None
              )
              service.executeSequence(request)
              complete("HOME command accepted")
            }
          },
          path("return") {
            post {
              val request = SequenceCommandRequest(
                sequence = SequenceType.RETURN,
                requestedBgid = None,
                observationAngle = None,
                cw = None
              )
              service.executeSequence(request)
              complete("RETURN command accepted")
            }
          },
          path("park") {
            post {

              complete("PARK command accepted")
            }
          }
        )
      }
    }
}
