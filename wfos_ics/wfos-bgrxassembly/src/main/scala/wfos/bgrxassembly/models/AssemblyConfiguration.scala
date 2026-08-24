//old
package wfos.bgrxassembly.models

import com.typesafe.config.Config

import java.time.Duration

case class AssemblyConfiguration(movementDelay: Duration)

object AssemblyConfiguration {
  def apply(config: Config): AssemblyConfiguration = {
    val movementDelay = config.getDuration("movementDelay")
    new AssemblyConfiguration(movementDelay)
  }
}
