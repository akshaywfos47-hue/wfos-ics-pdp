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
import csw.params.commands.CommandResponse.*
import csw.params.core.models.Id
import csw.params.commands.CommandIssue.{ParameterValueOutOfRangeIssue, UnsupportedCommandIssue, WrongCommandTypeIssue}
import csw.params.commands.{CommandName, ControlCommand, Observe, Setup}
import csw.params.core.generics.Parameter
import csw.time.core.models.UTCTime

import scala.concurrent.{ExecutionContextExecutor, Future}
import wfos.rgriphcd.RgripInfo

import csw.params.events.{Event, EventKey, EventName, SystemEvent}
import wfos.rgriphcd.RgripState

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

  // rgrip state variables
  private var rstate = RgripState(
    currentAngle = 28,
    hasGrating = true,
    gratingId = Some("bgid2")
  )

  private def handlePactEvent(event: Event): Unit = {
    val operation = "push" // PUSH / PULL
    operation match {
      case "PUSH" =>
        log.info("============= rgrip recived pact push event and rgrip is updating its state ")
      // update LGM → slot empty and send command to rgrip and lgmhcd
      // sendPushPullStatusToHcds("PUSH")
      // update RGRIP → has grating

      case "PULL" =>
        log.info("==================== Rgrip recived pact pull event and rgrip updating its stste ")
      // update LGM → slot filled and send command to lgmhcd and rgrip
      // sendPushPullStatusToHcds("PULL")
      // update RGRIP → empty
    }
  }

  // Called when the component is created
  override def initialize(): Unit = {
    log.info(s"Initializing $prefix")
    val pactPushPullEventKey = EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("pactPushPullEventone"))

    val subscriber = eventService.defaultSubscriber
    subscriber.subscribeCallback(
      Set(pactPushPullEventKey),
      event => {
        log.info("pact sent push pull event ")
        // handlePactEvent(event)
      }
    )
    // log.info(s"RgripHcd : Checking if $prefix is at home position")

    // val ik: Key[Int]            = KeyType.IntKey.make("IKey")
    // val ikValue: Parameter[Int] = ik.set(1)
    // log.info(s"IK value : ${ikValue.value(0)}")

    log.info(
      s"RgripHcd : Exchange Position - ${RgripInfo.exchangeAngle.head}, Current Position - ${rstate.currentAngle}"
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
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move") =>
            val targetAngle = setup(RgripInfo.targetAngleKey).head
            if (targetAngle >= 0 && targetAngle <= 55)
              Accepted(runId)
            else {
              log.error("rgrip validation failed target angle is not in valid range")
              val stage  = RgripInfo.stageKey.set("Validation")
              val status = RgripInfo.statusKey.set("Failure")
              val event  = SystemEvent(prefix, EventName("RgripHcd_status")).madd(stage, status)
              publisher.publish(event)
              Invalid(runId, ParameterValueOutOfRangeIssue("Invalid angle"))
            }
          case CommandName("update") =>
            val op = setup(RgripInfo.operationKey).head
            if (op == "PUSH" || op == "PULL")
              Accepted(runId)
            else
              Invalid(runId, UnsupportedCommandIssue("Invalid operation"))
          case _ => Invalid(runId, UnsupportedCommandIssue("Invalid operation"))
        }
      case _: Observe => Invalid(runId, WrongCommandTypeIssue("RgripHcd accepts only setup commands"))
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("Invalid command"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"RgripHcd: handling command: runid - $runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move")   => onSetup(runId, setup)
          case CommandName("update") => onUpdate(runId, setup)
          case _                     => Invalid(runId, UnsupportedCommandIssue("Unsupported command"))
        }
      case _ => Invalid(runId, UnsupportedCommandIssue("unsupported command "))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    val alarmKey                    = AlarmKey(Prefix("wfos.bgrxAssembly.rgriphcd"), "alarmTriggeredOnRgrip")
    val resultF: Future[Done]       = clientAPI.setSeverity(alarmKey, Okay)
    val targetAngle: Parameter[Int] = setup(RgripInfo.targetAngleKey)
    if (rstate.currentAngle == targetAngle.head) {
      Completed(runId)
    }
    else {
      log.info(s"RgripHcd: Executing the received command: runid - $runId")
      val delay: Int = 500
      log.info(s"RgripHcd: Gripper is at ${rstate.currentAngle} degrees")

      // Started(runId)

      if (rstate.currentAngle > targetAngle.head) {
        var timeElapsed = 0L // Variable to track elapsed time
        while (rstate.currentAngle != targetAngle.head) {
          rstate = rstate.copy(currentAngle = rstate.currentAngle - 1)
          log.info(s"RgripHcd: Rotating gripper to ${rstate.currentAngle}")
          // Publish the rotation event
          onRotation(runId, rstate.currentAngle)

          // Check if 9 seconds have elapsed since loop start
          val currentTime = System.currentTimeMillis()
          if (currentTime - timeElapsed >= 9000) {
            clientAPI.setSeverity(alarmKey, Okay)
            timeElapsed = currentTime // Reset timer for next interval
          }
          Thread.sleep(delay)
        }
      }
      else if (rstate.currentAngle < targetAngle.head) {
        var timeElapsed = 0L // Variable to track elapsed time
        while (rstate.currentAngle != targetAngle.head) {
          rstate = rstate.copy(currentAngle = rstate.currentAngle + 1)
          // log.info(s"RgripHcd: Rotating gripper to ${RgripInfo.currentAngle.head}")
          // Publish the rotation event
          onRotation(runId, rstate.currentAngle)

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

      // calling rgrip status event
      publishRgripStatus("Setup", "Completed")

      Completed(runId)
    }
  }

  private def createRotationEvent(angle: Int): SystemEvent = {
    // Create a SystemEvent representing the rotation of the gripper
    SystemEvent(componentInfo.prefix, EventName("RgripRotationEvent"))
      .add(RgripInfo.currentAngleKey.set(angle))
  }

  private def onRotation(runId: Id, angle: Int): Unit = {
    // Publish the rotation event
    val event = createRotationEvent(angle)
    publisher.publish(event)
  }

  // rgrip status event
  private def publishRgripStatus(stage: String, status: String): Unit = {

    var event =
      SystemEvent(prefix, EventName("RgripStatusEvent"))
        .add(RgripInfo.stageKey.set(stage))
        .add(RgripInfo.statusKey.set(status))
        .add(RgripInfo.currentAngleKey.set(rstate.currentAngle))
        .add(RgripInfo.rgripHasGratingKey.set(rstate.hasGrating))

    // ✅ add only if present
    if (rstate.hasGrating) {
      rstate.gratingId.foreach { id =>
        event = event.add(RgripInfo.rgripGratingIdKey.set(id))
      }
    }

    publisher.publish(event)
  }

  private def onUpdate(runId: Id, setup: Setup): SubmitResponse = {
    val operation = setup(RgripInfo.operationKey).head
    val gratingIdOpt =
      if (setup.exists(RgripInfo.gratingModeKey))
        Some(setup(RgripInfo.gratingModeKey).head)
      else None
    operation match {
      case "PUSH" =>
        // 🔹 grating comes INTO RGRIP
        rstate = rstate.copy(
          hasGrating = true,
          gratingId = gratingIdOpt // later from assembly
        )
        log.info(s"RGRIP PUSH → holding grating ${rstate.gratingId}")
        publishRgripStatus("PUSH", "Completed")
      case "PULL" =>
        // 🔹 grating goes OUT of RGRIP
        rstate = rstate.copy(
          hasGrating = false,
          gratingId = None
        )
        log.info("RGRIP PULL → grating removed")
        publishRgripStatus("PULL", "Completed")
    }
    Completed(runId)
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
