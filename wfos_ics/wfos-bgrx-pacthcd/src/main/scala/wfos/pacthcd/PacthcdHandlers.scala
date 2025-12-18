package wfos.pacthcd

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
import wfos.pacthcd.PactInfo
import csw.params.events.{SystemEvent, EventName}

class PacthcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val prefix                        = cswCtx.componentInfo.prefix
  private val publisher                     = eventService.defaultPublisher

  override def initialize(): Unit = {
    log.info(s"Initializing $prefix")
    log.info(s"PactHcd : Checking if $prefix is at its current position")

    log.info(s"Current Position - ${PactInfo.currentPosition.head}")
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    Accepted(runId);
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"PactHcd : Handling command with runId - $runId")
    controlCommand match {
      case setup: Setup => onSetup(runId, setup)
      case _            => Invalid(runId, UnsupportedCommandIssue("PactHcd : Inavlid Command received"))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    println(s"PactHcd : Received command - OnSubmit123")
    log.info(s"PactHcd : Executing the received command with runId - $runId")

    val delay: Int                        = 1000
    val targetPosition: Parameter[Double] = setup(PactInfo.targetPositionKey)

    log.info(s"PactHcd : Rod is currently at ${PactInfo.currentPosition.head}mm")

    while (PactInfo.currentPosition.head != targetPosition.head) {
      PactInfo.currentPosition = PactInfo.currentPositionKey.set(
        if (PactInfo.currentPosition.head < targetPosition.head)
          PactInfo.currentPosition.head + 50 // Move forward by 50 units
        else
          PactInfo.currentPosition.head - 50 // Move backward by 50 units
      )

      if (PactInfo.currentPosition.head % 10 == 0) {
        val message = s"PactHcd : Moving rod to ${PactInfo.currentPosition.head}mm"
        // Create and publish the event
        val event = createMovementEvent(message)
        publisher.publish(event)
      }
      Thread.sleep(delay)
    }

    val stage  = PactInfo.stageKey.set("Setup")
    val status = PactInfo.statusKey.set("Completed")
    val event  = SystemEvent(componentInfo.prefix, EventName("PactHcd_status")).madd(stage, status)
    publisher.publish(event)
    Completed(runId)
  }

  private def createMovementEvent(message: String): SystemEvent = {
    // Create a SystemEvent representing the movement of the gripper
    SystemEvent(componentInfo.prefix, EventName("PactMovementEvent"))
      .madd(PactInfo.messageKey.set(message))
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {}

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}
