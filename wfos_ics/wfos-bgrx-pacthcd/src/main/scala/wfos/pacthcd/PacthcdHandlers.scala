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

  private var pactState = PactState(
    currentPosition = 500.0
  )

  override def initialize(): Unit = {
    log.info(s"Initializing $prefix")
    log.info(s"PactHcd : Checking if $prefix is at its current position")

    log.info(s"Current Position - ${pactState.currentPosition}")
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

    val operation = setup(PactInfo.operationKey).head // PUSH / PULL

    executeOperation(operation, runId)
    Completed(runId)
  }

  private def executeOperation(operation: String, runId: Id): Unit = {
    //  decision point
    operation match {
      case "PUSH" =>
        // Step 1 → move OUT → IN (500 → 0)
        moveToPosition(0.0, "IN")
        // call assembly event
        publishPactPushPullEvent("PUSH")
        publishPactStatus("PUSH", "At-IN")
        moveToPosition(500.0, "OUT")
        publishPactStatus("PUSH", "At-OUT")

      case "PULL" =>
        moveToPosition(0.0, "IN")
        publishPactStatus("PULL", "At-IN")
        moveToPosition(500.0, "OUT")
        // call assembly event
        publishPactPushPullEvent("PULL")
        publishPactStatus("Pull", "At-OUT")
      case _ =>
        log.error(" operation is not supported ,operation should be PUSH or PULL only  ")
        Invalid(runId, ParameterValueOutOfRangeIssue("Target out of range"))
    }
    // final completion
    publishPactStatus(operation, "Completed")
  }

  private def moveToPosition(target: Double, stage: String): Unit = {

    while (pactState.currentPosition != target) {

      val next =
        if (pactState.currentPosition < target) {
          val temp = pactState.currentPosition + 50
          if (temp > target) target else temp
        }
        else {
          val temp = pactState.currentPosition - 50
          if (temp < target) target else temp
        }

      pactState = pactState.copy(currentPosition = next)

      if (pactState.currentPosition % 10 == 0) {
        publisher.publish(createMovementEvent(s"$stage → ${pactState.currentPosition}"))
      }

      Thread.sleep(50)
    }
  }

  private def createMovementEvent(message: String): SystemEvent = {
    // Create a SystemEvent representing the movement of the gripper
    SystemEvent(componentInfo.prefix, EventName("PactMovementEvent"))
      .madd(PactInfo.messageKey.set(message))
  }

  private def publishPactStatus(stage: String, status: String): Unit = {

    val event =
      SystemEvent(prefix, EventName("PactStatusEventone"))
        .madd(
          PactInfo.stageKey.set(stage),   // PUSH / PULL
          PactInfo.statusKey.set(status), // At-IN / Completed
          PactInfo.currentPositionKey.set(pactState.currentPosition)
        )

    publisher.publish(event)
  }
  private def publishPactPushPullEvent(operation: String): Unit = {
    log.info(" publishPactPushPullEvent CALLED")
    val event = SystemEvent(prefix, EventName("pactPushPullEventone")).madd(
      PactInfo.currentPositionKey.set(pactState.currentPosition),
      PactInfo.operationKey.set(operation)
    )

    publisher.publish(event)

  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {}

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}
