package wfos.lgmhcd

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse._
import csw.params.core.models.Id
import csw.params.commands.CommandIssue.{ParameterValueOutOfRangeIssue, UnsupportedCommandIssue, WrongCommandTypeIssue, MissingKeyIssue}
import csw.params.commands.{CommandName, ControlCommand, Observe, Setup}
import csw.time.core.models.UTCTime
import csw.params.events.{EventKey, EventName, SystemEvent}
import csw.prefix.models.Prefix

import scala.concurrent.ExecutionContextExecutor
import wfos.lgmhcd.{LgmInfo, LgmState}

/**
 * LgmhcdHandlers – Linear Grating Magazine HCD
 *
 * Commands accepted:
 *
 *   Setup("move")
 *     Key: targetGratingPositionKey (Double, mm)
 *     Moves magazine to the given position and publishes arrival state.
 *     gratingMap is NOT modified here.
 *
 *   gratingTransferEvent (Assembly prefix)
 *     Subscribed in initialize(). Updates gratingMap and emptySlot fields
 *     when physical transfer is confirmed. Publishes LgmStatusEvent after.
 *
 * Events published:
 *   LgmMovementEvent  – per step during movement (currentPosition)
 *   LgmHcd_status     – after validation (Success/Failure)
 *   LgmStatusEvent    – full state snapshot after move or updateState
 */
class LgmhcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {
  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val prefix                        = cswCtx.componentInfo.prefix
  private val publisher                     = eventService.defaultPublisher

  private val EPSILON  = 0.01
  private val EXCHANGE = LgmInfo.gratingExchangePosition.head // 500.0 mm

  // ── Internal state ──────────────────────────────────────────────────────────
  private var state: LgmState = LgmState.initial

  // Guard flag: gratingTransferEvent callbacks are ignored until the HCD
  // receives its first real command. This prevents the CSW event service's
  // startup replay of the last-known Redis event from corrupting the
  // hardcoded initial state before any sequence has run.
  private var readyForTransferEvents: Boolean = false

  // ── Lifecycle ───────────────────────────────────────────────────────────────

  override def initialize(): Unit = {
    log.info(s"Initializing $prefix  currentPosition=${state.currentPosition} mm")
    // Subscribe to Assembly's gratingTransferEvent so lgmHcd updates its
    // gratingMap as soon as the physical transfer is confirmed – event is
    // faster than a command round-trip.
    subscribeToGratingTransferEvent()
  }

  // ── Subscribe to Assembly's gratingTransferEvent ───────────────────────────
  private def subscribeToGratingTransferEvent(): Unit = {
    val subscriber         = eventService.defaultSubscriber
    val gratingTransferKey = EventKey(Prefix("wfos.bgrxAssembly"), EventName("gratingTransferEvent"))

    subscriber.subscribeCallback(
      Set(gratingTransferKey),
      event =>
        event match {
          case sysEvent: SystemEvent =>
            // Ignore the stale event that CSW replays from Redis on subscription.
            // readyForTransferEvents is set true once the first move command starts,
            // so this guard prevents the initial replay from corrupting startup state.
            if (!readyForTransferEvents) {
              log.info("LgmHCD [gratingTransferEvent]: ignoring startup replay – no sequence active yet")
            }
            else {
              val operation = sysEvent(LgmInfo.operationKey).head
              val bgid      = sysEvent(LgmInfo.targetBgidKey).head
              val slotIndex = LgmInfo.bgidToIndex(bgid)

              log.info(s"LgmHcd [gratingTransferEvent]: operation=$operation, bgid=$bgid, slotIndex=$slotIndex")

              if (slotIndex < 0) {
                log.warn(s"LgmHcd [gratingTransferEvent]: unknown bgid '$bgid' – ignoring")
              }
              else {
                operation match {
                  case "push" =>
                    // Pickup complete: grating left this slot → mark as empty
                    state = state
                      .withGratingMap(slotIndex, 0)
                      .copy(
                        emptySlot = true,
                        emptySlotIndex = Some(slotIndex),
                        emptySlotBgid = Some(bgid),
                        operation = "pickup"
                      )
                    log.info(s"LgmHcd [gratingTransfer/push]: slot $slotIndex ('$bgid') marked EMPTY")
                    LgmInfo.currentPosition = LgmInfo.currentPositionKey.set(state.currentPosition)
                    publishLgmStatusEvent()

                  case "pull" =>
                    // Return complete: grating returned to this slot → mark as occupied
                    state = state
                      .withGratingMap(slotIndex, 1)
                      .copy(
                        emptySlot = false,
                        emptySlotIndex = None,
                        emptySlotBgid = None,
                        operation = "return"
                      )
                    log.info(s"LgmHcd [gratingTransfer/pull]: slot $slotIndex ('$bgid') marked OCCUPIED")
                    LgmInfo.currentPosition = LgmInfo.currentPositionKey.set(state.currentPosition)
                    publishLgmStatusEvent()

                  case other =>
                    log.warn(s"LgmHcd [gratingTransferEvent]: unknown operation '$other'")
                }
              }
            }
          case _ =>
        }
    )
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  // ── Validation ──────────────────────────────────────────────────────────────

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"LgmHcd : Validating command $runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {

          // ── move: validate targetGratingPosition is present and in range ──────
          case CommandName("move") =>
            setup.get(LgmInfo.targetGratingPositionKey) match {
              case None =>
                publishStatusEvent("Validation", "Failure")
                Invalid(runId, MissingKeyIssue("LgmHcd : 'targetGratingPosition' key is missing"))
              case Some(param) =>
                val targetPos = param.head
                if (targetPos < LgmInfo.minTargetGrating.head || targetPos > LgmInfo.maxTargetGrating.head) {
                  publishStatusEvent("Validation", "Failure")
                  Invalid(
                    runId,
                    ParameterValueOutOfRangeIssue(
                      s"LgmHcd : targetGratingPosition $targetPos out of range " +
                        s"[${LgmInfo.minTargetGrating.head}, ${LgmInfo.maxTargetGrating.head}]"
                    )
                  )
                }
                else {
                  publishStatusEvent("Validation", "Success")
                  Accepted(runId)
                }
            }

          case CommandName("homing") =>
            Accepted(runId)

          case _ =>
            Invalid(runId, UnsupportedCommandIssue(s"LgmHcd : unknown command '${setup.commandName.name}'. Accepts 'move' and 'homing'"))
        }

      case _: Observe => Invalid(runId, WrongCommandTypeIssue("LgmHcd accepts only Setup commands"))
      case _          => Invalid(runId, UnsupportedCommandIssue("LgmHcd : Invalid command type"))
    }
  }

  // ── Submit ──────────────────────────────────────────────────────────────────

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"LgmHcd : Executing command $runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move")   => onMove(runId, setup)
          case CommandName("homing") => onHoming(runId)
          case _                     => Invalid(runId, UnsupportedCommandIssue("LgmHcd : Unknown command – accepts 'move' and 'homing'"))
        }
      case _ => Invalid(runId, UnsupportedCommandIssue("LgmHcd : Invalid command type"))
    }
  }

  // ── homing handler ───────────────────────────────────────────────────────────
  /**
   * Homing sequence (Section 9): Assembly sends Setup("homing") with no
   * target position. LgmHcd reads its own homePosition (250 mm) and delegates
   * directly to onMove with a synthetic Setup carrying that position.
   */
  private def onHoming(runId: Id): SubmitResponse = {
    val homePos = LgmInfo.homePosition.head
    log.info(s"LgmHcd [homing]: moving directly to homePosition=$homePos mm")

    readyForTransferEvents = true

    if (math.abs(state.currentPosition - homePos) <= EPSILON) {
      log.info(s"LgmHcd [homing]: already at home position ($homePos mm) – completing immediately")
      publishStatusEvent("homing", "Completed")
      publishLgmStatusEvent()
      return Completed(runId)
    }

    moveMagazineTo(homePos)
    state = state.copy(operation = "idle")
    LgmInfo.currentPosition = LgmInfo.currentPositionKey.set(state.currentPosition)

    publishStatusEvent("homing", "Completed")
    publishLgmStatusEvent()
    Completed(runId)
  }

  // ── move handler ────────────────────────────────────────────────────────────

  private def onMove(runId: Id, setup: Setup): SubmitResponse = {
    readyForTransferEvents = true
    val targetPos = setup(LgmInfo.targetGratingPositionKey).head

    log.info(s"LgmHcd [move] : ${state.currentPosition} mm → $targetPos mm")

    if (math.abs(state.currentPosition - targetPos) > EPSILON) {
      moveMagazineTo(targetPos)
    }
    else {
      log.info("LgmHcd [move] : Already at target – no movement needed")
    }

    // Determine which slot is now at exchange and what operation this is for
    val slotLinearDist = EXCHANGE - state.currentPosition
    val slotIndex      = findSlotIndex(slotLinearDist)
    val bgid           = if (slotIndex >= 0) LgmInfo.indexToBgid(slotIndex) else "unknown"
    val operation = setup.get(LgmInfo.operationKey) match {
      case Some(param) => param.head // Will be "idle" for homing
      case None =>
        if (slotIndex >= 0 && state.hasGratingAt(slotIndex)) "pickup" else "return"
    }

    log.info(s"LgmHcd [move] : Slot '$bgid' (index=$slotIndex) at exchange, operation=$operation")

    state = state.copy(operation = operation)
    LgmInfo.currentPosition = LgmInfo.currentPositionKey.set(state.currentPosition)

    publishStatusEvent("Move", "Completed")
    publishLgmStatusEvent()
    Completed(runId)
  }

  // ── Magazine motion ─────────────────────────────────────────────────────────

  private def moveMagazineTo(targetPos: Double): Unit = {
    val delay = 4   // ms
    val step  = 0.5 // mm

    while (math.abs(state.currentPosition - targetPos) > EPSILON) {
      val current = state.currentPosition
      val delta   = if (current < targetPos) step else -step
      val next    = current + delta

      val clamped =
        if (
          (current < targetPos && next > targetPos) ||
          (current > targetPos && next < targetPos)
        )
          targetPos
        else next

      state = state.copy(currentPosition = clamped)

      if (math.abs(clamped % 10) < step) {
        publisher.publish(
          SystemEvent(componentInfo.prefix, EventName("LgmMovementEvent"))
            .madd(
              LgmInfo.messageKey.set(s"LgmHcd : magazine at ${state.currentPosition} mm"),
              LgmInfo.currentPositionKey.set(state.currentPosition)
            )
        )
      }

      Thread.sleep(delay)
    }

    log.info(s"LgmHcd : Reached $targetPos mm")
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private def findSlotIndex(slotLinearDist: Double): Int =
    LgmInfo.gratingLinearDistance.indexWhere(d => math.abs(d - slotLinearDist) <= EPSILON)

  // ── Event publishers ────────────────────────────────────────────────────────

  private def publishStatusEvent(stage: String, status: String): Unit = {
    publisher.publish(
      SystemEvent(componentInfo.prefix, EventName("LgmHcd_status"))
        .madd(LgmInfo.stageKey.set(stage), LgmInfo.statusKey.set(status))
    )
  }

  /**
   * LgmStatusEvent – full state snapshot.
   * Published after every "move" arrival AND after every gratingTransferEvent.
   * Assembly subscribes to this to keep its lgmState cache current.
   */
  private def publishLgmStatusEvent(): Unit = {
    val slotLinearDist =
      state.emptySlotIndex.map(LgmInfo.gratingLinearDistance).getOrElse(-1.0)

    publisher.publish(
      SystemEvent(componentInfo.prefix, EventName("LgmStatusEvent"))
        .madd(
          LgmInfo.currentPositionKey.set(state.currentPosition),
          LgmInfo.operationKey.set(state.operation),
          LgmInfo.emptySlotKey.set(state.emptySlot),
          LgmInfo.emptySlotIndexKey.set(state.emptySlotIndex.getOrElse(-1)),
          LgmInfo.emptySlotBgidKey.set(state.emptySlotBgid.getOrElse("")),
          LgmInfo.emptySlotLinearDistanceKey.set(slotLinearDist),
          LgmInfo.statusKey.set("Completed")
        )
    )
  }

  // ── Lifecycle stubs ──────────────────────────────────────────────────────────
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}
  override def onShutdown(): Unit                                        = log.info(s"$prefix shutting down")
  override def onGoOffline(): Unit                                       = {}
  override def onGoOnline(): Unit                                        = {}
  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit  = {}
  override def onOperationsMode(): Unit                                  = {}
}
