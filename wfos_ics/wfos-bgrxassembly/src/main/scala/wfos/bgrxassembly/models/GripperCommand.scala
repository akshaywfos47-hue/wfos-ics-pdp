//old
package wfos.bgrxassembly.models

import org.apache.pekko.actor.typed.ActorRef
import csw.params.commands.CommandResponse.ValidateCommandResponse
import csw.params.core.models.Id

sealed trait GripperCommand

object GripperCommand {
  case class Move(target: GripperPosition, runId: Id)                           extends GripperCommand
  case class IsValidMove(runId: Id, replyTo: ActorRef[ValidateCommandResponse]) extends GripperCommand
  case object MoveStep                                                          extends GripperCommand
}
