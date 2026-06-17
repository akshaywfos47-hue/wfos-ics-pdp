package wfos.lgriphcd

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.alarm.models.Key.AlarmKey
import csw.alarm.models.AlarmSeverity.Okay
import csw.alarm.api.scaladsl.{AlarmAdminService, AlarmService, AlarmSubscription}
import csw.prefix.models.Prefix
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse._
import csw.params.core.models.{Id}
import csw.params.commands.CommandIssue.{ParameterValueOutOfRangeIssue, WrongCommandTypeIssue, UnsupportedCommandIssue}
import csw.params.commands.{ControlCommand, CommandName, Observe, Setup}
// import csw.params.core.generics.{Parameter, Key, KeyType}
import csw.params.core.generics.Parameter
import csw.time.core.models.UTCTime

import scala.concurrent.{ExecutionContextExecutor, Future}
import wfos.lgriphcd.LgripInfo
import csw.params.events.{SystemEvent, EventName}

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to Lgriphcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/commons/framework.html
 */
class LgriphcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val prefix                        = cswCtx.componentInfo.prefix
  private val publisher                     = eventService.defaultPublisher
  private val clientAPI                     = cswCtx.alarmService

  // val rgripHcd = new RgripHcd()

// creating object of case class state variable value
  private var lstate = LgripState(
    currentPosition = 0,
    targetPosition = 0
  )
  // Called when the component is created
  override def initialize(): Unit = {
    log.info(s"Initializing $prefix")
//    log.info(s"LgripHcd : Checking if $prefix is at home position")

//    log.info(s"Home Position - ${LgripInfo.homePosition.head}, Current Position - ${lstate.currentPosition}")
//    if (lstate.currentPosition != LgripInfo.homePosition.head) {
//      log.error("LgripHcd : gripper is not at the home position")

    // val targetPosition: Parameter[Int] = LgripInfo.targetPositionKey.set(LgripInfo.homePosition.head)
    // val sc1: Setup                     = Setup(prefix, CommandName("move"), Some(LgripInfo.obsId)).madd(targetPosition)

    // val validateResponse = validateCommand(Id(), sc1)
    // validateResponse match {
    //   case Accepted(runId) => onSubmit(runId, sc1)
    //   case Invalid(runId, commandissue) => {
    //     log.error("LgripHcd : Validation Failure")
    //     // log.info(s"${validateResponse.commandissue}")
    //     log.error(s"$commandissue")
    //     Invalid(runId, commandissue)
    //   }
    // }
//    }
//    else {
//      log.info("LgripHcd : Gripper is at home position")
//    }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"LgripHcd : Command - $runId is being validated")
    controlCommand match {
      case setup: Setup => {

        val targetPosition = setup(LgripInfo.targetPositionKey).head

        if (
          targetPosition >= LgripInfo.minTargetPosition.head &&
          targetPosition <= LgripInfo.maxTargetPosition.head
        )
          Accepted(runId)
        else {
          val stage                = LgripInfo.stageKey.set("Validation")
          val status               = LgripInfo.statusKey.set("Failure")
          val lgripcurrentPosition = LgripInfo.currentPositionKey.set(lstate.currentPosition)
          publishLgripStatus(stage, status, lgripcurrentPosition)

          Invalid(runId, ParameterValueOutOfRangeIssue("LgripHcd : taget position is out of range  "))
        }
      }
      case _: Observe => Invalid(runId, WrongCommandTypeIssue("LgripHcd accepts only setup commands"))
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("LgripHcd: Invalid command type"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"LgripHcd : Handling command with runId - $runId")
    controlCommand match {
      case setup: Setup => onSetup(runId, setup)
      case _            => Invalid(runId, UnsupportedCommandIssue("LgripHcd : Inavlid Command received"))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    // updating state variable
    val targetPosition = setup(LgripInfo.targetPositionKey).head
    lstate = lstate.copy(targetPosition = targetPosition)

    val alarmKey              = AlarmKey(Prefix("wfos.bgrxAssembly.lgriphcd"), "alarmTriggeredOnLgrip")
    val resultF: Future[Done] = clientAPI.setSeverity(alarmKey, Okay)
    log.info(s"LgripHcd : Executing the received command with runId - $runId")
    // val targetPosition: Parameter[Int] = setup(LgripInfo.targetPositionKey)
    val delay: Int = 10
    log.info(s"LgripHcd : Gripper is at ${lstate.currentPosition}cm")
    var timeElapsed = 0L // Variable to track elapsed time

    if (lstate.currentPosition == targetPosition) {
      log.info("LgripHcd : Lgrip is already at target position")
      val stage  = LgripInfo.stageKey.set("Setup")
      val status = LgripInfo.statusKey.set("Completed")
      // val message = LgripInfo.messageKey.set("Lgrip already at target position")
      val lgripcurrentPosition = LgripInfo.currentPositionKey.set(lstate.currentPosition)
      publishLgripStatus(stage, status, lgripcurrentPosition)
      Completed(runId)
    }
    else {
      while (lstate.currentPosition != targetPosition) {
        val nextPosition =
          if (lstate.currentPosition < targetPosition)
            lstate.currentPosition + 1
          else
            lstate.currentPosition - 1

        lstate = lstate.copy(currentPosition = nextPosition)

        if (lstate.currentPosition % 10 == 0) {
          val message = s"LgripHcd : Moving gripper to ${lstate.currentPosition}"
          // log.info(message)
          // Create and publish the event
          val lPosition = LgripInfo.currentPositionKey.set(lstate.currentPosition)
          val event     = createMovementEvent(lPosition)
          publisher.publish(event)
        }

        // Check if 9 seconds have elapsed since loop start
        val currentTime = System.currentTimeMillis()
        if (currentTime - timeElapsed >= 9000) {
          clientAPI.setSeverity(alarmKey, Okay)
          timeElapsed = currentTime // Reset timer for next interval
        }
        Thread.sleep(delay)
      }

      val stage                = LgripInfo.stageKey.set("Setup")
      val status               = LgripInfo.statusKey.set("Completed")
      val lgripcurrentPosition = LgripInfo.currentPositionKey.set(lstate.currentPosition)
      publishLgripStatus(stage, status, lgripcurrentPosition)
      Completed(runId)
    }
  }

  private def createMovementEvent(position: Parameter[Int]): SystemEvent = {

    val eventName =
      (
        if (lstate.targetPosition == LgripInfo.exchangePosition.head)
          "lgripMoveToExchangePositionEvent"
        else
          "lgripMoveToHomePositionEvent"
      )
    // Create a SystemEvent representing the movement of the gripper
    SystemEvent(componentInfo.prefix, EventName(eventName))
      .madd(position)
  }
  // lgrip status event
  private def publishLgripStatus(stage: Parameter[String], status: Parameter[String], position: Parameter[Int]): Unit = {
    val event =
      SystemEvent(prefix, EventName("LgripStatusEvent"))
        .madd(
          stage,
          status,
          position
        )
    publisher.publish(event)
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
