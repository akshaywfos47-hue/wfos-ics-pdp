package wfos.bgrxassembly

import csw.params.events.Event
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object TelemetryEventActor {

  def apply(telemetryManager: TelemetryManager): Behavior[Event] =
    Behaviors.receive { (_, event) =>

      telemetryManager.eventServiceHelper(event)

      Behaviors.same
    }
}
