package wfos.lgmhcd

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse._
import csw.params.core.models.{Id}
import csw.params.commands.CommandIssue.{ParameterValueOutOfRangeIssue, WrongCommandTypeIssue, UnsupportedCommandIssue}
import csw.params.commands.{ControlCommand, CommandName, Observe, Setup}

import csw.time.core.models.UTCTime
import csw.params.core.generics.Parameter

import scala.concurrent.{ExecutionContextExecutor}
import wfos.lgmhcd.LgmInfo
import csw.params.events.{SystemEvent, EventName}

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to Lgmhcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/commons/framework.html
 */
class LgmhcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val prefix                        = cswCtx.componentInfo.prefix
  private val publisher                     = eventService.defaultPublisher

  override def initialize(): Unit = {
    log.info(s"Initializing $prefix")
    log.info(s"LgmHcd : Checking if $prefix is at home position")

    log.info(s"Home Position - ${LgmInfo.homePosition.head}, Current Position - ${LgmInfo.currentPosition.head}")
    if (LgmInfo.currentPosition.head != LgmInfo.homePosition.head) {
      log.error("LgmHcd : Grating magazine is not at the home position")
    }
    else {
      log.info("LgmHcd : Grating Magazine is at home position")
    }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"LgmHcd : Command - $runId is being validated")
    controlCommand match {
      case setup: Setup => {
        val targetGratingPosition = setup(LgmInfo.targetGratingPositionKey)
        if (targetGratingPosition.head != LgmInfo.currentPosition.head) {
          log.info("LgmHcd : Validation Successful")
          Accepted(runId)
        }
        else {
          log.error("LgmHcd : Gripper is already at target position")

          val stage  = LgmInfo.stageKey.set("Validation")
          val status = LgmInfo.statusKey.set("Failure")
          val event  = SystemEvent(componentInfo.prefix, EventName("LgmHcd_status")).madd(stage, status)
          publisher.publish(event)

          Invalid(runId, ParameterValueOutOfRangeIssue("LgmHcd : Hcd is already at target postion"))
        }

      }
      case _: Observe => Invalid(runId, WrongCommandTypeIssue("LgmHcd accepts only setup commands"))
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("LgmHcd: Invalid command type"))

    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"LgmHcd : Handling command with runId - $runId")
    controlCommand match {
      case setup: Setup => onSetup(runId, setup)
      case _            => Invalid(runId, UnsupportedCommandIssue("LgmHcd : Inavlid Command received"))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {

    log.info(s"LgmHcd : Executing the received command with runId - $runId")
    // val targetGratingId = setup(LgmInfo.targetGratingIdKey).head

    val delay: Int                               = 10
    val targetGratingPosition: Parameter[Double] = setup(LgmInfo.targetGratingPositionKey)

    log.info(s"LgmHcd : Grating Magazine is at ${LgmInfo.currentPosition.head}cm")

    while (LgmInfo.currentPosition.head != LgmInfo.gratingExchangePosition.head - targetGratingPosition.head) {
      LgmInfo.currentPosition = LgmInfo.currentPositionKey.set(
        if (LgmInfo.currentPosition.head < LgmInfo.gratingExchangePosition.head - targetGratingPosition.head)
          LgmInfo.currentPosition.head + 0.5 // Move forward by 0.5 units
        else
          LgmInfo.currentPosition.head - 0.5 // Move backward by 0.5 units
      )

      if (LgmInfo.currentPosition.head % 10 == 0) {
        val message = s"LgmHcd : Moving gripper to ${LgmInfo.currentPosition.head}"
        // Create and publish the event
        val event = createMovementEvent(message)
        publisher.publish(event)
      }
      Thread.sleep(delay)
    }

    // Update the gratingMap corresponding to the target grating position (denoting the grating is taken out)
    val gratingIndex = LgmInfo.gratingLinearDistance.indexOf(targetGratingPosition.head)
    if (gratingIndex >= 0) {
      LgmInfo.gratingMap(gratingIndex) = 0 // Mark the grating as taken out
      println(s"Grating at index $gratingIndex has been taken out.")
    }

    val stage  = LgmInfo.stageKey.set("Setup")
    val status = LgmInfo.statusKey.set("Completed")
    val event  = SystemEvent(componentInfo.prefix, EventName("LgmHcd_status")).madd(stage, status)
    publisher.publish(event)
    Completed(runId)
  }

  private def createMovementEvent(message: String): SystemEvent = {
    // Create a SystemEvent representing the movement of the gripper
    SystemEvent(componentInfo.prefix, EventName("LgmMovementEvent"))
      .madd(LgmInfo.messageKey.set(message))
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {
    log.info("shutting Down")
  }

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}

}
