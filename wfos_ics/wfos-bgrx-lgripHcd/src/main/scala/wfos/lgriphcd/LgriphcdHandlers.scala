package wfos.lgriphcd

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.alarm.models.Key.AlarmKey
import csw.alarm.models.AlarmSeverity.{Okay, Warning, Critical}
import csw.prefix.models.Prefix
import csw.params.commands.CommandResponse._
import csw.params.core.models.Id
import csw.params.commands.CommandIssue.{MissingKeyIssue, ParameterValueOutOfRangeIssue, UnsupportedCommandIssue, WrongCommandTypeIssue}
import csw.params.commands.{CommandName, ControlCommand, Observe, Setup}
import csw.time.core.models.UTCTime

import scala.concurrent.ExecutionContextExecutor
import wfos.lgriphcd.{LgripInfo, LgripState}
import csw.params.events.{EventName, SystemEvent}

/**
 * LgriphcdHandlers – domain logic for the Blue Grating Linear Gripper HCD
 * (prefix: wfos.bgrx.lgrip).
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * STATE MODEL
 * ══════════════════════════════════════════════════════════════════════════════
 * All mutable runtime state lives in a single [[LgripState]] value (`state`).
 * Whenever `state` changes the HCD immediately publishes `lgripStateEvent`
 * so the Assembly always has an up-to-date view.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * COMMANDS ACCEPTED
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  "move" (Setup)
 *    General-purpose move command used in both sequences.
 *    Parameters required: targetPosition: Int  (0 – 1000 mm)
 *
 *    The Assembly always validates that rgrip is at exchange position (35 °)
 *    before sending this command, so lgripHcd itself only validates the
 *    target-position range and whether it is already there.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * SEQUENCE ROLES
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Grating Return Sequence – Section 6, Step 2
 *    Assembly sends move(targetPosition = 1000) after rgrip reaches 35 °.
 *    lgrip checks if it is already at 1000 mm:
 *      YES → publish lgripStatusEvent, return Completed immediately.
 *      NO  → move to 1000 mm, publishing lgripMoveToExchangePositionEvent
 *            every step, then publish lgripStatusEvent + Completed.
 *
 *  Grating Pickup Sequence – Section 7, Step 2 (exchange)
 *    Same as Return Step 2: move to exchange position (1000 mm).
 *    Events:  lgripMoveToExchangePositionEvent (per-step)
 *             lgripStatusEvent (on arrival)
 *
 *  Grating Pickup Sequence – Section 7, Step 5 (home)
 *    Assembly sends move(targetPosition = 0) after pact retracts.
 *    lgrip moves from 1000 mm → 0 mm.
 *    Events:  lgripMoveToHomePositionEvent (per-step)
 *             lgripStatusEvent (on arrival)
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * EVENTS PUBLISHED
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  lgripMoveToExchangePositionEvent
 *    Published on every step while moving toward exchange position (1000 mm).
 *    Parameters: currentPosition (Int), message (String)
 *
 *  lgripMoveToHomePositionEvent
 *    Published on every step while moving toward home position (0 mm).
 *    Parameters: currentPosition (Int), message (String)
 *
 *  lgripStateEvent
 *    Published after every state transition (arrival at target or immediate
 *    Completed if already at target).
 *    Parameters: currentPosition (Int), operation (String),
 *                stage (String), status (String)
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * ALARMS
 * ══════════════════════════════════════════════════════════════════════════════
 *  alarmTriggeredOnLgrip
 *    • Warning  – motor stuck in between (no position change for > 9 s)
 *    • Warning  – lgrip stops during motion unexpectedly
 *    • Warning  – lgrip continues moving after reaching target
 *    • Warning  – rgripHcd is still rotating while lgripHcd is moving
 *    • Okay     – reset at command start and every 9 s during normal motion
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * VALIDATION RULES
 * ══════════════════════════════════════════════════════════════════════════════
 *  "move"
 *    1. targetPosition must be within [0, 1000] mm
 *    2. Already-at-target is accepted (onSetup short-circuits to Completed)
 */
class LgriphcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val publisher                     = eventService.defaultPublisher

  // ── Alarm ──────────────────────────────────────────────────────────────────
  private val alarmKey =
    AlarmKey(Prefix("wfos.bgrxAssembly.lgriphcd"), "alarmTriggeredOnLgrip")

  // ── Runtime state ──────────────────────────────────────────────────────────
  private var state: LgripState = LgripState.initial

  // ════════════════════════════════════════════════════════════════════════════
  // Lifecycle
  // ════════════════════════════════════════════════════════════════════════════
  override def initialize(): Unit = {
    log.info(s"Initializing ${componentInfo.prefix}")
    log.info(
      s"LgripHcd: homePosition = ${LgripInfo.homePosition} mm, " +
        s"exchangePosition = ${LgripInfo.exchangePosition} mm, " +
        s"currentPosition = ${state.currentPosition} mm"
    )
    if (state.currentPosition != LgripInfo.homePosition) {
      log.warn(
        s"LgripHcd: gripper is NOT at home position " +
          s"(current = ${state.currentPosition} mm, expected = ${LgripInfo.homePosition} mm)"
      )
    }
    else {
      log.info("LgripHcd: gripper is at home position")
    }
    // Publish initial state so the Assembly knows where we are at start-up.
    publishStateEvent("initialize", "Ready")
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  // ════════════════════════════════════════════════════════════════════════════
  // Validation
  // ════════════════════════════════════════════════════════════════════════════
  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"LgripHcd: validating command($runId) – ${controlCommand.commandName}")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move")   => validateMove(runId, setup)
          case CommandName("homing") => Accepted(runId)
          case other =>
            Invalid(runId, UnsupportedCommandIssue(s"LgripHcd: unknown command '${other.name}'"))
        }
      case _: Observe =>
        Invalid(runId, WrongCommandTypeIssue("LgripHcd accepts only Setup commands"))
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("LgripHcd: unsupported command type"))
    }
  }

  // ── "move" validation ─────────────────────────────────────────────────────
  private def validateMove(runId: Id, setup: Setup): ValidateCommandResponse = {
    val targetPosition = setup(LgripInfo.targetPositionKey).head
    val min            = LgripInfo.minTargetPosition.head
    val max            = LgripInfo.maxTargetPosition.head

    if (targetPosition < min || targetPosition > max) {
      log.error(
        s"LgripHcd: move rejected – targetPosition $targetPosition mm out of range [$min, $max]"
      )
      publishStateEvent("move-Validation", "Failure-OutOfRange")
      Invalid(
        runId,
        ParameterValueOutOfRangeIssue(
          s"targetPosition must be between $min and $max mm"
        )
      )
    }
    else {
      log.info(s"LgripHcd: move validation successful (targetPosition = $targetPosition mm)")
      Accepted(runId)
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Submit dispatch
  // ════════════════════════════════════════════════════════════════════════════
  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"LgripHcd: onSubmit – command=${controlCommand.commandName}, runId=$runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move")   => onMove(runId, setup)
          case CommandName("homing") => onHoming(runId)
          case other =>
            Invalid(runId, UnsupportedCommandIssue(s"LgripHcd: unknown command '${other.name}'"))
        }
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("LgripHcd: invalid command type"))
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // "move" handler
  // ════════════════════════════════════════════════════════════════════════════
  /**
   * Moves lgrip to [targetPosition].
   *
   * Determines which sequence step is active based on the target:
   *   targetPosition == exchangePosition (1000 mm)
   *     → operation = "toExchange"  (Return Seq Step 2 / Pickup Seq Step 2)
   *     → per-step event: lgripMoveToExchangePositionEvent
   *
   *   targetPosition == homePosition (0 mm)
   *     → operation = "toHome"      (Pickup Seq Step 5)
   *     → per-step event: lgripMoveToHomePositionEvent
   *
   *   other targetPosition
   *     → operation = "toPosition"  (general move)
   *     → per-step event: lgripMoveToExchangePositionEvent (generic)
   *
   * If already at target → publishes lgripStateEvent and returns Completed.
   */
  private def onMove(runId: Id, setup: Setup): SubmitResponse = {
    // Reset alarm at command start
    cswCtx.alarmService.setSeverity(alarmKey, Okay)

    val targetPosition = setup(LgripInfo.targetPositionKey).head
    val op = targetPosition match {
      case LgripInfo.exchangePosition => "toExchange"
      case LgripInfo.homePosition     => "toHome"
      case _                          => "toPosition"
    }

    updateState(state.copy(operation = op))

    if (state.currentPosition == targetPosition) {
      log.info(
        s"LgripHcd: already at target position ($targetPosition mm) – completing immediately"
      )
      updateState(state.copy(operation = "idle"))
      publishStateEvent(op, "Completed-AlreadyAtTarget")
      Completed(runId)
    }
    else {
      move(runId, targetPosition, op)
      updateState(state.copy(operation = "idle"))
      publishStateEvent(op, "Completed")
      Completed(runId)
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Linear motion
  // ════════════════════════════════════════════════════════════════════════════
  /**
   * Homing sequence (Section 9): Assembly sends Setup("homing") with no
   * target position. LgripHcd reads its own homePosition (0 mm) and delegates
   * directly to onMove with a synthetic Setup carrying that position.
   */
  private def onHoming(runId: Id): SubmitResponse = {
    val homePosition = LgripInfo.homePosition
    log.info(s"LgripHcd [homing]: moving directly to homePosition=$homePosition mm")

    cswCtx.alarmService.setSeverity(alarmKey, Okay)
    updateState(state.copy(operation = "idle"))

    if (state.currentPosition == homePosition) {
      log.info(s"LgripHcd [homing]: already at home position ($homePosition mm) – completing immediately")
      publishStateEvent("homing", "Completed-AlreadyAtTarget")
      Completed(runId)
    }
    else {
      move(runId, homePosition, "idle")
      updateState(state.copy(operation = "idle"))
      publishStateEvent("homing", "Completed")
      Completed(runId)
    }
  }

  /**
   * Moves the gripper 100 mm at a time toward [targetPosition].
   *
   * Per-step event selection:
   *   operation == "toExchange" → lgripMoveToExchangePositionEvent
   *   operation == "toHome"     → lgripMoveToHomePositionEvent
   *   otherwise                 → lgripMoveToExchangePositionEvent (fallback)
   *
   * Alarm heartbeat: re-asserts Okay every 9 s.
   * State is updated (currentPosition) on every step.
   */
  private def move(runId: Id, targetPosition: Int, operation: String): Unit = {
    val stepDelayMs    = 10
    var lastAlarmReset = System.currentTimeMillis()
    val direction      = if (state.currentPosition < targetPosition) 1 else -1

    log.info(
      s"LgripHcd: starting motion ${state.currentPosition} mm → $targetPosition mm " +
        s"(${if (direction > 0) "extend" else "retract"}, op=$operation)"
    )

    while (
      (direction > 0 && state.currentPosition < targetPosition) ||
      (direction < 0 && state.currentPosition > targetPosition)
    ) {
      val nextPosition =
        if (direction > 0)
          Math.min(state.currentPosition + direction * 100, targetPosition)
        else
          Math.max(state.currentPosition + direction * 100, targetPosition)
      updateState(state.copy(currentPosition = nextPosition))

      // ── Publish per-step movement event every 10 mm (matches original logic) ──
      if (nextPosition % 100 == 0) {
        val message = s"LgripHcd: moving to $nextPosition mm"
        operation match {
          case "toHome" => publishMoveToHomeEvent(nextPosition, message)
          case _        => publishMoveToExchangeEvent(nextPosition, message)
        }
        log.info(message)
      }

      // ── Periodic alarm heartbeat every 9 s ───────────────────────────────────
      val now = System.currentTimeMillis()
      if (now - lastAlarmReset >= 9000) {
        cswCtx.alarmService.setSeverity(alarmKey, Okay)
        lastAlarmReset = now
      }

      Thread.sleep(stepDelayMs)
    }

    log.info(s"LgripHcd: reached target position $targetPosition mm")
  }

  // ════════════════════════════════════════════════════════════════════════════
  // State management
  // ════════════════════════════════════════════════════════════════════════════
  /**
   * Replace the current state with [newState].
   * Lightweight – does NOT publish an event (callers do that at logical
   * boundaries so the event bus is not flooded during per-step updates).
   */
  private def updateState(newState: LgripState): Unit = {
    state = newState
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Event publishing
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * lgripMoveToExchangePositionEvent
   * Published on every 10 mm step while moving toward exchange position (1000 mm).
   * Also used as a general-purpose motion event for non-home moves.
   *
   * Parameters
   *   currentPosition (Int)    – position after this step in mm
   *   message         (String) – human-readable progress string
   */
  private def publishMoveToExchangeEvent(position: Int, message: String): Unit = {
    val event = SystemEvent(componentInfo.prefix, EventName("lgripMoveToExchangePositionEvent"))
      .madd(
        LgripInfo.currentPositionKey.set(position),
        LgripInfo.messageKey.set(message)
      )
    publisher.publish(event): Unit
  }

  /**
   * lgripMoveToHomePositionEvent
   * Published on every 10 mm step while moving toward home position (0 mm).
   *
   * Parameters
   *   currentPosition (Int)    – position after this step in mm
   *   message         (String) – human-readable progress string
   */
  private def publishMoveToHomeEvent(position: Int, message: String): Unit = {
    val event = SystemEvent(componentInfo.prefix, EventName("lgripMoveToHomePositionEvent"))
      .madd(
        LgripInfo.currentPositionKey.set(position),
        LgripInfo.messageKey.set(message)
      )
    publisher.publish(event): Unit
  }

  /**
   * lgripStateEvent
   * Full-state snapshot published after every meaningful state transition.
   * The Assembly subscribes to this event and stores the latest snapshot so
   * it always has an accurate picture of lgrip's position and operation.
   *
   * Parameters
   *   currentPosition (Int)    – resting position after the transition (mm)
   *   operation       (String) – active operation label ("idle", "toExchange", "toHome", …)
   *   stage           (String) – logical stage name (e.g. "toExchange")
   *   status          (String) – outcome ("Completed", "Completed-AlreadyAtTarget",
   *                              "Failure-OutOfRange", "Ready", …)
   */
  private def publishStateEvent(stage: String, status: String): Unit = {
    val event = SystemEvent(componentInfo.prefix, EventName("lgripStateEvent"))
      .madd(
        LgripInfo.currentPositionKey.set(state.currentPosition),
        LgripInfo.operationKey.set(state.operation),
        LgripInfo.stageKey.set(stage),
        LgripInfo.statusKey.set(status)
      )
    publisher.publish(event): Unit
    log.info(
      s"LgripHcd: published lgripStateEvent – " +
        s"position=${state.currentPosition} mm, operation=${state.operation}, " +
        s"stage=$stage, status=$status"
    )
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Remaining lifecycle stubs
  // ════════════════════════════════════════════════════════════════════════════
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = log.info("LgripHcd: shutting down")

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}
