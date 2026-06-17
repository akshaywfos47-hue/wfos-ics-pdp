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

  // state variables

  private var lgmState = LgmState(
    currentPosition = 112.5,
    emptySlot = true,
    emptySlotBgid = Some("bgid2"),
    emptySlotLinearDistance = Some(112.5),
    emptySlotIndex = Some(1)
  )
  override def initialize(): Unit = {
    log.info(s"Initializing $prefix")
    log.info(s"LgmHcd : Checking if $prefix is at home position")

    log.info(s"Home Position - ${LgmInfo.homePosition.head}, Current Position - ${lgmState.currentPosition}")
    if (lgmState.currentPosition != LgmInfo.homePosition.head) {
      log.info("LgminfoHcd : Grating magazine is not at the home position")
    }
    else {
      log.info("LgmHcd : Grating Magazine is at home position")
    }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {

    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          // 🔹 MOVE command
          case CommandName("move") =>
            val target = setup(LgmInfo.targetGratingPositionKey)
            if (
              target.head >= LgmInfo.minTargetGrating.head &&
              target.head <= LgmInfo.maxTargetGrating.head
            ) Accepted(runId)
            else
              val stage  = LgmInfo.stageKey.set("Validation")
              val status = LgmInfo.statusKey.set("Failure")
              val event  = SystemEvent(componentInfo.prefix, EventName("LgmHcd_status")).madd(stage, status)
              publisher.publish(event)
              Invalid(runId, ParameterValueOutOfRangeIssue("Target out of range"))

          //  UPDATE command (PUSH / PULL)
          case CommandName("update") =>
            val op =
              if (setup.exists(LgmInfo.operationKey))
                Some(setup(LgmInfo.operationKey).head)
              else None
            if (op.contains("PUSH") || op.contains("PULL"))
              Accepted(runId)
            else
              Invalid(runId, UnsupportedCommandIssue("Invalid operation"))

          case _ =>
            Invalid(runId, UnsupportedCommandIssue("Unsupported command"))
        }

      case _: Observe =>
        Invalid(runId, WrongCommandTypeIssue("Only Setup supported"))

      case _ =>
        Invalid(runId, UnsupportedCommandIssue("Invalid command"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"LgmHcd : Handling command with runId - $runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move") =>
            onSetup(runId, setup)
          case CommandName("update") =>
            onUpdate(runId, setup)
          case _ =>
            Invalid(runId, UnsupportedCommandIssue("Unsupported command name"))
        }
      case _ => Invalid(runId, UnsupportedCommandIssue("LgmHcd : Inavlid Command received"))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {

    log.info(s"LgmHcd : Executing the received command with runId - $runId")
    // val targetGratingId = setup(LgmInfo.targetGratingIdKey).head

    val delay: Int                               = 10
    val targetGratingPosition: Parameter[Double] = setup(LgmInfo.targetGratingPositionKey)

    log.info(s"LgmHcd : Grating Magazine is at ${lgmState.currentPosition}cm")

    while (lgmState.currentPosition != targetGratingPosition.head) {
      lgmState = lgmState.copy(
        currentPosition =
          if (lgmState.currentPosition < targetGratingPosition.head)
            lgmState.currentPosition + 0.5
          else
            lgmState.currentPosition - 0.5
      )

      if (lgmState.currentPosition % 10 == 0) {
        val message = s"LgmHcd : Moving gripper to ${lgmState.currentPosition}"
        // Create and publish the event
        val event = createMovementEvent(message)
        publisher.publish(event)
      }
      Thread.sleep(delay)
    }

    // Update the gratingMap corresponding to the target grating position (denoting the grating is taken out)

    val stage             = LgmInfo.stageKey.set("Setup")
    val status            = LgmInfo.statusKey.set("Completed")
    val currentPoitionlgm = LgmInfo.currentPositionKey.set(lgmState.currentPosition)
//    val emptySlot=LgmInfo.emptySlotKey.set(lgmState.emptySlot)
//    val emptySlotBgid=LgmInfo.emptySlotBgidKey.set(lgmState.emptySlotBgid)
//    val linearDistance=LgmInfo.gratingLinearDistanceKey.set(lgmState.emptySlotLinearDistance)
//    val slotIndex=LgmInfo.emptySlotIndexKey.set(lgmState.emptySlotIndex)
    val event = SystemEvent(componentInfo.prefix, EventName("LgmHcd_status")).madd(stage, status, currentPoitionlgm)
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
// helper methods

  private def getIndex(position: Double): Int = {
    LgmInfo.gratingLinearDistance.indexWhere(d => Math.abs(d - position) < 0.001)
  }

  private def getBgid(index: Int): String = {
    s"bgid${index + 1}"
  }

  private def isSlotEmpty(index: Int): Boolean = {
    LgmInfo.gratingMap(index) == 0
  }

  private def handleGratingPush(): Unit = {
    val index = getIndex(lgmState.currentPosition)
    if (index >= 0) {
      val bgid           = getBgid(index)
      val linearDistance = LgmInfo.gratingLinearDistance(index)

      //  mark slot EMPTY
      LgmInfo.gratingMap(index) = 0

      lgmState = lgmState.copy(
        emptySlot = true,
        emptySlotBgid = Some(bgid),
        emptySlotLinearDistance = Some(linearDistance),
        emptySlotIndex = Some(index)
      )

      log.info(s"PUSH → Slot emptied at index $index, bgid = $bgid")
      publishLgmStatus(
        stage = "PUSH",
        status = "Completed"
      )

    }
    else {
      log.error("Invalid position during PUSH")
    }
  }

  private def handleGratingPull(): Unit = {
    val index = getIndex(lgmState.currentPosition)
    if (index >= 0) {
      val bgid           = getBgid(index)
      val linearDistance = LgmInfo.gratingLinearDistance(index)

      // mark slot FILLED
      LgmInfo.gratingMap(index) = 1

      lgmState = lgmState.copy(
        emptySlot = false,
        emptySlotBgid = None,
        emptySlotLinearDistance = None,
        emptySlotIndex = None
      )
      log.info(s"PULL → Slot filled at index $index, bgid = $bgid")
      publishLgmStatus(
        stage = "PULL",
        status = "Completed"
      )
    }
    else {
      log.error("Invalid position during PULL")
    }
  }

  private def publishLgmStatus(stage: String, status: String): Unit = {

    // Step 1: base event (always present fields)
    var event =
      SystemEvent(prefix, EventName("LgmStatusEvent"))
        .add(LgmInfo.stageKey.set(stage))
        .add(LgmInfo.statusKey.set(status))
        .add(LgmInfo.currentPositionKey.set(lgmState.currentPosition))
        .add(LgmInfo.emptySlotKey.set(lgmState.emptySlot))

    // Step 2: add slot details only when emptySlot = true
    if (lgmState.emptySlot) {

      lgmState.emptySlotBgid.foreach { bgid =>
        event = event.add(LgmInfo.emptySlotBgidKey.set(bgid))
      }

      lgmState.emptySlotLinearDistance.foreach { distance =>
        event = event.add(LgmInfo.emptySlotLinearDistanceKey.set(distance))
      }

      lgmState.emptySlotIndex.foreach { index =>
        event = event.add(LgmInfo.emptySlotIndexKey.set(index))
      }
    }

    // Step 3: publish
    publisher.publish(event)

    log.info(s"LGM Status Event Published → $lgmState")
  }

  private def onUpdate(runId: Id, setup: Setup): SubmitResponse = {

    val operation = setup(LgmInfo.operationKey).head

    operation match {
      case "PUSH" => handleGratingPush()
      case "PULL" => handleGratingPull()
    }
    Completed(runId)
  }

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}

}
