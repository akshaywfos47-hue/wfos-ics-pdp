package wfos.bgrxassembly.api

import com.typesafe.config.ConfigFactory

object BgrxHttpConfig {

  private val config = ConfigFactory.load()

  val host: String =
    config.getString("bgrx-api.host")

  val port: Int =
    config.getInt("bgrx-api.port")
}
