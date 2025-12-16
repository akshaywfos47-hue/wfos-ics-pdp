package wfos.rgriphcd

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.alarm.models.Key.AlarmKey
import csw.alarm.api.scaladsl.{AlarmAdminService, AlarmService, AlarmSubscription}
import csw.alarm.models.AlarmSeverity.Okay
import csw.prefix.models.Prefix
import csw.params.commands.CommandResponse._
import csw.params.core.models.{Id}
import csw.params.commands.CommandIssue.{ParameterValueOutOfRangeIssue, UnsupportedCommandIssue, WrongCommandTypeIssue}
import csw.params.commands.{ControlCommand, CommandName, Observe, Setup}

import csw.params.core.generics.{Parameter}
import csw.time.core.models.UTCTime

import scala.concurrent.{ExecutionContextExecutor, Future}
import wfos.rgriphcd.RgripInfo
import csw.params.events.{SystemEvent, EventName}

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to Rgriphcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/commons/framework.html
 */
class RgriphcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val prefix                        = cswCtx.componentInfo.prefix
  private val publisher                     = eventService.defaultPublisher
  private val clientAPI                     = cswCtx.alarmService

  // Called when the component is created
  override def initialize(): Unit = {
    log.info(s"Initializing $prefix")
    // log.info(s"RgripHcd : Checking if $prefix is at home position")

    // val ik: Key[Int]            = KeyType.IntKey.make("IKey")
    // val ikValue: Parameter[Int] = ik.set(1)
    // log.info(s"IK value : ${ikValue.value(0)}")

    log.info(
      s"RgripHcd : Exchange Position - ${RgripInfo.exchangeAngle.values.head}, Current Position - ${RgripInfo.currentAngle.values.head}"
    )
    // if (RgripInfo.currentAngle.values.head != RgripInfo.homeAngle.values.head) {
    //   log.error("RgripHcd : gripper is not at the exchange position")

    //   val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(RgripInfo.homeAngle.head)
    //   val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid3")
    //   val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)
    //   val sc1: Setup                     = Setup(prefix, CommandName("move"), Some(RgripInfo.obsId)).madd(targetAngle, gratingMode, cw)

    //   val validateResponse = validateCommand(Id(), sc1)
    //   validateResponse match {
    //     case Accepted(runId) => onSubmit(runId, sc1)
    //     case Invalid(runId, commandissue) => {
    //       log.error("RgripHcd : Validation Failure")
    //       // log.info(s"${validateResponse.commandissue}")
    //       log.info(s"$commandissue")
    //       Invalid(runId, commandissue)
    //     }
    //   }
    // }
    // else {
    // log.info("RgripHcd : Gripper is already at home position")
    // }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"RgripHcd: Command($runId) is being validated")
    controlCommand match {
      case setup: Setup => {
        val targetAngle = setup(RgripInfo.targetAngleKey)
        if (targetAngle.head != RgripInfo.currentAngle.head) {
          log.info("RgripHcd : Validation Successful")
          Accepted(runId)
        }
        else {
          log.error(s"RgripHcd: Gripper is already at target position")
          val stage  = RgripInfo.stageKey.set("Validation")
          val status = RgripInfo.statusKey.set("Failure")
          val event  = SystemEvent(componentInfo.prefix, EventName("RgripHcd_status")).madd(stage, status)
          publisher.publish(event)

          Invalid(runId, ParameterValueOutOfRangeIssue("RgripHcd: Gripper is already at target angle"))
        }
      }
      case _: Observe => Invalid(runId, WrongCommandTypeIssue("RgripHcd accepts only setup commands"))
      case other =>
        log.warn(s"Invalid command received: $other")
        Invalid(runId, UnsupportedCommandIssue("Unsupported command"))

    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"RgripHcd: handling command: runid - $runId")
    controlCommand match {
      case setup: Setup => onSetup(runId, setup)
      case _            => Invalid(runId, UnsupportedCommandIssue("RgripHcd: Inavlid Command received"))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    val alarmKey                    = AlarmKey(Prefix("wfos.bgrxAssembly.rgriphcd"), "alarmTriggeredOnRgrip")
    val resultF: Future[Done]       = clientAPI.setSeverity(alarmKey, Okay)
    val targetAngle: Parameter[Int] = setup(RgripInfo.targetAngleKey)
    if (RgripInfo.currentAngle.head == targetAngle.head) {
      Completed(runId)
    }
    else {
      log.info(s"RgripHcd: Executing the received command: runid - $runId")
      val delay: Int = 500
      log.info(s"RgripHcd: Gripper is at ${RgripInfo.currentAngle.head} degrees")

      // Started(runId)

      if (RgripInfo.currentAngle.head > targetAngle.head) {
        var timeElapsed = 0L // Variable to track elapsed time
        while (RgripInfo.currentAngle.head != targetAngle.head) {
          RgripInfo.currentAngle = RgripInfo.currentAngleKey.set(RgripInfo.currentAngle.head - 1)
          log.info(s"RgripHcd: Rotating gripper to ${RgripInfo.currentAngle.head}")
          // Publish the rotation event
          onRotation(runId, RgripInfo.currentAngle.head)

          // Check if 9 seconds have elapsed since loop start
          val currentTime = System.currentTimeMillis()
          if (currentTime - timeElapsed >= 9000) {
            clientAPI.setSeverity(alarmKey, Okay)
            timeElapsed = currentTime // Reset timer for next interval
          }
          Thread.sleep(delay)
        }
      }
      else if (RgripInfo.currentAngle.head < targetAngle.head) {
        var timeElapsed = 0L // Variable to track elapsed time
        while (RgripInfo.currentAngle.head != targetAngle.head) {
          RgripInfo.currentAngle = RgripInfo.currentAngleKey.set(RgripInfo.currentAngle.head + 1)
          // log.info(s"RgripHcd: Rotating gripper to ${RgripInfo.currentAngle.head}")
          // Publish the rotation event
          onRotation(runId, RgripInfo.currentAngle.head)

          // Check if 9 seconds have elapsed since loop start
          val currentTime = System.currentTimeMillis()
          if (currentTime - timeElapsed >= 9000) {
            clientAPI.setSeverity(alarmKey, Okay)
            timeElapsed = currentTime // Reset timer for next interval
          }

          Thread.sleep(delay)
        }
      }

      val stage  = RgripInfo.stageKey.set("Setup")
      val status = RgripInfo.statusKey.set("Completed")
      val event  = SystemEvent(componentInfo.prefix, EventName("RgripHcd_status")).madd(stage, status)
      publisher.publish(event)

      Completed(runId)
    }
  }

  private def createRotationEvent(angle: Int): SystemEvent = {
    // Create a SystemEvent representing the rotation of the gripper
    SystemEvent(componentInfo.prefix, EventName("RgripRotationEvent"))
      .madd(RgripInfo.angleKey.set(angle))
  }

  private def onRotation(runId: Id, angle: Int): Unit = {
    // Publish the rotation event
    val event = createRotationEvent(angle)
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
