package wfos.bgrxassembly

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.framework.models.CswContext
import csw.params.commands.CommandIssue.AssemblyBusyIssue
import csw.params.commands.CommandResponse.{Accepted, Completed, Invalid}
import csw.params.core.models.Id
import csw.time.core.models.UTCTime
import wfos.bgrxassembly.models.{AssemblyConfiguration, GripperCommand}
import wfos.bgrxassembly.models.GripperCommand.MoveStep
import wfos.bgrxassembly.models.{BluegrxPosition, GripperPosition}
import wfos.bgrxassembly.events.{BgrxPositionEvent, BlueGrxPositionEvent}

import scala.concurrent.Future

abstract class GripperActor(cswContext: CswContext, configuration: AssemblyConfiguration) {

  private val timeServiceScheduler = cswContext.timeServiceScheduler
  private val crm                  = cswContext.commandResponseManager

  private def unhandledMessage(state: String) = s"$name: Cannot accept command: move in [$state] state"

  final def idle(current: GripperPosition): Behavior[GripperCommand] =
    Behaviors.receive { (ctx, msg) =>
      val log = getLogger(ctx)
      msg match {
        case GripperCommand.Move(target, id) => moving(id, current, target)(ctx)
        case GripperCommand.IsValidMove(runId, replyTo) =>
          replyTo ! Accepted(runId)
          Behaviors.same
        case GripperCommand.MoveStep =>
          log.error(unhandledMessage("idle"))
          Behaviors.unhandled
      }
    }

  final def moving(runId: Id, current: GripperPosition, target: GripperPosition)(
      ctx: ActorContext[GripperCommand]
  ): Behavior[GripperCommand] = {
    val log = getLogger(ctx)
    log.info(s"$name: current position is: $current")

    if (current == target) {
      log.info(s"$name: target position: $current reached")
      publishPosition(current, target, dark = false)
      crm.updateCommand(Completed(runId))
      idle(current)
    }
    else {
      scheduleMoveStep(ctx.self)
      moveBehavior(runId, current, target)
    }
  }

  private def moveBehavior(runId: Id, currentPos: GripperPosition, targetPos: GripperPosition): Behavior[GripperCommand] =
    Behaviors.receive { (ctx, msg) =>
      val log = getLogger(ctx)
      msg match {
        case GripperCommand.IsValidMove(runId, replyTo) =>
          val errMsg = unhandledMessage("moving")
          log.error(errMsg)
          val issue = AssemblyBusyIssue(errMsg)
          replyTo ! Invalid(runId, issue)
          Behaviors.same
        case GripperCommand.MoveStep =>
          val nextPosition = currentPos.nextPosition(targetPos)
          if (nextPosition != targetPos) publishPosition(nextPosition, targetPos, dark = true)
          moving(runId, nextPosition, targetPos)(ctx)
        case GripperCommand.Move(_, runId) =>
          val errMsg = unhandledMessage("moving")
          log.error(errMsg)
          crm.updateCommand(Invalid(runId, AssemblyBusyIssue(errMsg)))
          Behaviors.unhandled
      }
    }

  private def scheduleMoveStep(self: ActorRef[GripperCommand]) =
    timeServiceScheduler.scheduleOnce(UTCTime(UTCTime.now().value.plus(configuration.movementDelay))) {
      self ! MoveStep
    }

  private def getLogger(ctx: ActorContext[GripperCommand]) = cswContext.loggerFactory.getLogger(ctx)

  private lazy val eventPublisher = cswContext.eventService.defaultPublisher

  val gripperPositionEvent: BgrxPositionEvent
  val name: String
//
  def publishPosition(current: GripperPosition, target: GripperPosition, dark: Boolean): Future[Done] = {
    eventPublisher.publish(gripperPositionEvent.make(current, target, dark))
  }
}

class BlueGripperActor(cswContext: CswContext, configuration: AssemblyConfiguration) extends GripperActor(cswContext, configuration) {

  override val gripperPositionEvent = new BlueGrxPositionEvent(cswContext.componentInfo.prefix)
  override val name: String         = "Blue Gripper"

  def behavior: Behavior[GripperCommand] =
    new BlueGripperActor(cswContext, configuration).idle(BluegrxPosition.left_edge)

}
