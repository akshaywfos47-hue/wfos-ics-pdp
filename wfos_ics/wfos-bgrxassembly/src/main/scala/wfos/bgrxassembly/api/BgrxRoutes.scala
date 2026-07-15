package wfos.bgrxassembly.api

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.cors.scaladsl.CorsDirectives._

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
              service.home()
              complete("HOME command accepted")
            }
          },
          path("park") {
            post {
              service.park()
              complete("PARK command accepted")
            }
          }
        )
      }
    }
}
