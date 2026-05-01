package wfos.rgriphcd

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.alarm.models.Key.AlarmKey
import csw.alarm.models.AlarmSeverity.Okay
import csw.prefix.models.Prefix
import csw.params.commands.CommandResponse._
import csw.params.core.models.Id
import csw.params.commands.CommandIssue.{MissingKeyIssue, ParameterValueOutOfRangeIssue, UnsupportedCommandIssue, WrongCommandTypeIssue}
import csw.params.commands.{CommandName, ControlCommand, Observe, Setup}
import csw.time.core.models.UTCTime
import csw.params.core.generics.KeyType

import scala.concurrent.ExecutionContextExecutor
import wfos.rgriphcd.{RgripInfo, RgripState}
import csw.params.events.{EventKey, EventName, SystemEvent}
import csw.prefix.models.Prefix

/**
 * RgriphcdHandlers – domain logic for the Blue Grating Rotary Gripper HCD
 * (prefix: wfos.bgrx.rgrip).
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * STATE MODEL
 * ══════════════════════════════════════════════════════════════════════════════
 * All mutable runtime state lives in a single [[RgripState]] value (`state`).
 * Whenever `state` changes the HCD publishes `rgripStateEvent` so the Assembly
 * always has an up-to-date view.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * COMMANDS ACCEPTED
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  "move" (Setup)
 *    The single rotation command. Used by ALL sequence steps requiring rotation:
 *      • Return Sequence  Step 1 – rotate to exchange position (35 °)
 *      • Pickup Sequence  Step 1 – rotate to exchange position (35 °)
 *      • Pickup Sequence  Step 6 – rotate to observation angle
 *    The HCD does NOT distinguish between sequences. It simply rotates to the
 *    requested targetAngle. Sequence-level checks (e.g. hasGrating for return)
 *    are the Assembly's responsibility.
 *    Parameters required: targetAngle: Int (0 – 55)
 *
 *  "updateGratingState" (Setup)
 *    Sent by the Assembly after pactHCD confirms a push or pull.
 *    No motion – only updates hasGrating / gratingId and publishes rgripStateEvent.
 *    Parameters required: hasGrating: Boolean, gratingId: String
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * EVENTS PUBLISHED
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  RgripRotationEvent  – every 5-degree step during rotation (angle: Int)
 *  rgripStateEvent     – after every state change
 *                        (currentAngle, hasGrating, gratingId, operation,
 *                         stage, status)
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * VALIDATION RULES
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  "move"
 *    1. targetAngle must be within [0, 55] degrees
 *
 *  "updateGratingState"
 *    1. hasGrating key must be present
 *    2. gratingId key must be present
 */
class RgriphcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {
  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val publisher                     = eventService.defaultPublisher

  // ── Alarm ──────────────────────────────────────────────────────────────────
  private val alarmKey =
    AlarmKey(Prefix("wfos.bgrxAssembly.rgriphcd"), "alarmTriggeredOnRgrip")

  // ── Runtime state ──────────────────────────────────────────────────────────
  // Single mutable reference; all updates go through `updateState()` so that
  // a state event is always published after each change.
  private var state: RgripState = RgripState.initial

  // Guard flag: gratingTransferEvent callbacks are ignored until the HCD
  // receives its first real command. This prevents the CSW event service's
  // startup replay of the last-known Redis event from corrupting the
  // hardcoded initial state before any sequence has run.
  private var readyForTransferEvents: Boolean = false

  // ════════════════════════════════════════════════════════════════════════════
  // Lifecycle
  // ════════════════════════════════════════════════════════════════════════════
  override def initialize(): Unit = {
    log.info(s"Initializing ${componentInfo.prefix}")
    log.info(
      s"RgripHcd: exchange position = ${RgripInfo.exchangeAngle.head} °, " +
        s"current angle = ${state.currentAngle} °, " +
        s"hasGrating = ${state.hasGrating}"
    )
    // Publish initial state so the Assembly knows where we are at start-up.
    publishStateEvent("initialize", "Ready")
    // Subscribe to Assembly's gratingTransferEvent so rgrip updates its
    // grating state as soon as transfer is confirmed – event is faster
    // than a command round-trip.
    subscribeToPactEvents()
  }

  // ── Subscribe to Assembly's gratingTransferEvent ───────────────────────────
  // The Assembly publishes gratingTransferEvent (faster than a command) after
  // pactHcd confirms the physical grating transfer. This carries operation + bgid.
  private def subscribeToPactEvents(): Unit = {
    val subscriber         = eventService.defaultSubscriber
    val gratingTransferKey = EventKey(Prefix("wfos.bgrxAssembly"), EventName("gratingTransferEvent"))

    val targetBgidKey = KeyType.StringKey.make("targetBgid")

    subscriber.subscribeCallback(
      Set(gratingTransferKey),
      event =>
        event match {
          case sysEvent: SystemEvent =>
            // Ignore the stale event that CSW replays from Redis on subscription.
            // readyForTransferEvents is set true once the first move command starts,
            // so this guard prevents the initial replay from corrupting startup state.
            if (!readyForTransferEvents) {
              log.info("RgripHcd [gratingTransferEvent]: ignoring startup replay – no sequence active yet")
            }
            else {
              val operation = sysEvent(RgripInfo.operationKey).head
              val bgid      = sysEvent(targetBgidKey).head
              log.info(s"RgripHcd [gratingTransferEvent]: operation=$operation, bgid=$bgid")

              operation match {
                case "push" =>
                  // Pickup complete: rgrip now holds the grating
                  updateState(state.copy(hasGrating = true, gratingId = Some(bgid), operation = "gratingEngaged"))
                  log.info(s"RgripHcd : hasGrating=true, gratingId='$bgid'")
                  publishStateEvent("gratingTransfer-push", "GratingEngaged")
                  updateState(state.copy(operation = "idle"))

                case "pull" =>
                  // Return complete: rgrip no longer holds a grating
                  updateState(state.copy(hasGrating = false, gratingId = None, operation = "gratingReleased"))
                  log.info("RgripHcd : hasGrating=false, gratingId=None")
                  publishStateEvent("gratingTransfer-pull", "GratingReleased")
                  updateState(state.copy(operation = "idle"))

                case other =>
                  log.warn(s"RgripHcd [gratingTransferEvent]: unknown operation '$other'")
              }
            }
          case _ =>
        }
    )
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  // ════════════════════════════════════════════════════════════════════════════
  // Validation
  // ════════════════════════════════════════════════════════════════════════════
  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"RgripHcd: validating command($runId) – ${controlCommand.commandName}")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move")               => validateMove(runId, setup)
          case CommandName("updateGratingState") => validateUpdateGratingState(runId, setup)
          case CommandName("homing")             => Accepted(runId)
          case other =>
            Invalid(
              runId,
              UnsupportedCommandIssue(s"RgripHcd: unknown command '${other.name}'. Accepts 'move', 'updateGratingState', and 'homing'")
            )
        }
      case _: Observe =>
        Invalid(runId, WrongCommandTypeIssue("RgripHcd accepts only Setup commands"))
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("RgripHcd: unsupported command type"))
    }
  }

  // ── "move" validation ─────────────────────────────────────────────────────
  private def validateMove(runId: Id, setup: Setup): ValidateCommandResponse = {
    val targetAngle = setup(RgripInfo.targetAngleKey).head
    val min         = RgripInfo.minTargetAngle.head
    val max         = RgripInfo.maxTargetAngle.head
    if (targetAngle < min || targetAngle > max) {
      log.error(s"RgripHcd: move rejected – targetAngle $targetAngle ° out of range [$min, $max]")
      publishStateEvent("move-Validation", "Failure-OutOfRange")
      Invalid(
        runId,
        ParameterValueOutOfRangeIssue(s"targetAngle must be between $min and $max degrees")
      )
    }
    else {
      log.info(s"RgripHcd: move validation successful (targetAngle = $targetAngle °)")
      Accepted(runId)
    }
  }

  // ── "updateGratingState" validation ──────────────────────────────────────
  private def validateUpdateGratingState(runId: Id, setup: Setup): ValidateCommandResponse = {
    if (!setup.contains(RgripInfo.hasGratingKey))
      return Invalid(runId, MissingKeyIssue("updateGratingState requires 'hasGrating' key"))
    if (!setup.contains(RgripInfo.gratingIdKey))
      return Invalid(runId, MissingKeyIssue("updateGratingState requires 'gratingId' key"))
    log.info("RgripHcd: updateGratingState validation successful")
    Accepted(runId)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Submit dispatch
  // ════════════════════════════════════════════════════════════════════════════
  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"RgripHcd: onSubmit – command=${controlCommand.commandName}, runId=$runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move")               => onMove(runId, setup)
          case CommandName("updateGratingState") => onUpdateGratingState(runId, setup)
          case CommandName("homing")             => onHoming(runId)
          case other =>
            Invalid(runId, UnsupportedCommandIssue(s"RgripHcd: unknown command '${other.name}'"))
        }
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("RgripHcd: invalid command type"))
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Command handlers
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * "move" – the single rotation command used by ALL sequence steps.
   *
   * The HCD does not know which sequence it is in. It simply rotates to the
   * requested targetAngle. The operation label is derived from the target:
   *   targetAngle == exchangeAngle (35°) → "toExchange"  (Step 1 of either sequence)
   *   any other angle                    → "toObservation" (Step 6 of pickup)
   *
   * Sequence-level validation (e.g. hasGrating check before return sequence)
   * is the Assembly's responsibility. The Assembly already checks
   * rgripState.hasGrating in its own validateCommand before sending this command.
   */
  private def onMove(runId: Id, setup: Setup): SubmitResponse = {
    // From this point on, gratingTransferEvents are real (not startup replays).
    readyForTransferEvents = true
    cswCtx.alarmService.setSeverity(alarmKey, Okay)
    val targetAngle = setup(RgripInfo.targetAngleKey).head
    val op =
      if (targetAngle == RgripInfo.exchangeAngle.head) "toExchange"
      else "toObservation"

    updateState(state.copy(operation = op))

    if (state.currentAngle == targetAngle) {
      log.info(s"RgripHcd: already at target angle ($targetAngle °) – completing immediately")
      updateState(state.copy(operation = "idle"))
      publishStateEvent(op, "Completed-AlreadyAtTarget")
      Completed(runId)
    }
    else {
      rotate(targetAngle, op)
      updateState(state.copy(operation = "idle"))
      publishStateEvent(op, "Completed")
      Completed(runId)
    }
  }

  /**
   * "updateGratingState" – called by the Assembly after pactHCD confirms a
   * push (grating inserted into rgrip) or pull (grating removed from rgrip).
   *
   * This is a pure state-update command; no motion occurs.
   *
   * Push scenario  (hasGrating=true, gratingId="bgidN"):
   *   rgrip now holds grating bgidN.
   *
   * Pull scenario  (hasGrating=false, gratingId=""):
   *   rgrip no longer holds a grating.
   *
   * After updating state, the HCD publishes rgripStateEvent so the Assembly
   * immediately sees the new state.
   */

  // ── "homing" handler ────────────────────────────────────────────────────────
  /**
   * Homing sequence (Section 9): Assembly sends Setup("homing") with no
   * target position. RgripHcd reads its own homeAngle (0°) and delegates
   * directly to onMove with a synthetic Setup carrying that angle.
   */
  private def onHoming(runId: Id): SubmitResponse = {
    val homeAngle = RgripInfo.homeAngle.head
    log.info(s"RgripHcd [homing]: moving directly to homeAngle=$homeAngle °")

    readyForTransferEvents = true
    cswCtx.alarmService.setSeverity(alarmKey, Okay)
    updateState(state.copy(operation = "idle"))

    if (state.currentAngle == homeAngle) {
      log.info(s"RgripHcd [homing]: already at home angle ($homeAngle °) – completing immediately")
      publishStateEvent("homing", "Completed-AlreadyAtTarget")
      Completed(runId)
    }
    else {
      rotate(homeAngle, "idle")
      updateState(state.copy(operation = "idle"))
      publishStateEvent("homing", "Completed")
      Completed(runId)
    }
  }

  private def onUpdateGratingState(runId: Id, setup: Setup): SubmitResponse = {
    val newHasGrating = setup(RgripInfo.hasGratingKey).head
    val newGratingId  = setup(RgripInfo.gratingIdKey).head

    val resolvedGratingId: Option[String] = if (newHasGrating) Some(newGratingId) else None

    val opLabel =
      if (newHasGrating) s"gratingEngaged($newGratingId)"
      else "gratingReleased"

    updateState(
      state.copy(
        hasGrating = newHasGrating,
        gratingId = resolvedGratingId,
        operation = opLabel
      )
    )
    log.info(
      s"RgripHcd: grating state updated – hasGrating=$newHasGrating, gratingId='$resolvedGratingId'"
    )

    // Publish updated state so the Assembly and any other subscriber knows
    publishStateEvent(opLabel, "StateUpdated")

    // Return operation label to idle after state is broadcast
    updateState(state.copy(operation = "idle"))
    publishStateEvent("idle", "Ready")

    Completed(runId)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Rotation
  // ════════════════════════════════════════════════════════════════════════════
  /**
   * Rotates the gripper one degree at a time toward [targetAngle].
   * Publishes [[RgripRotationEvent]] every 5 degrees.
   * Re-asserts alarm Okay every 9 seconds.
   *
   * State is updated (currentAngle) on every step so that if the Assembly
   * reads state mid-motion it always gets the latest position.
   */
  private def rotate(targetAngle: Int, operation: String): Unit = {
    val stepDelayMs    = 50
    var lastAlarmReset = System.currentTimeMillis()
    val direction      = if (state.currentAngle < targetAngle) 1 else -1

    log.info(
      s"RgripHcd: starting rotation ${state.currentAngle} ° → $targetAngle ° " +
        s"(${if (direction > 0) "CW" else "CCW"}, op=$operation)"
    )

    while (state.currentAngle != targetAngle) {
      // 1. Advance one degree
      val nextAngle = state.currentAngle + direction
      updateState(state.copy(currentAngle = nextAngle))

      // 2. Publish rotation event every 5 degrees
      if (nextAngle % 5 == 0) {
        publishRotationEvent(nextAngle)
        log.info(s"RgripHcd: rotating – current angle = $nextAngle °")
      }

      // 3. Periodic alarm heartbeat
      val now = System.currentTimeMillis()
      if (now - lastAlarmReset >= 9000) {
        cswCtx.alarmService.setSeverity(alarmKey, Okay)
        lastAlarmReset = now
      }

      Thread.sleep(stepDelayMs)
    }

    log.info(s"RgripHcd: reached $targetAngle °")
  }

  // ════════════════════════════════════════════════════════════════════════════
  // State management helper
  // ════════════════════════════════════════════════════════════════════════════
  /**
   * Replace the current state with [newState].
   * Callers invoke [[publishStateEvent]] separately when the logical step
   * is complete; this method is intentionally lightweight so it can be called
   * on every rotation step without flooding the event bus with full-state
   * events (rotation events serve that per-step purpose).
   */
  private def updateState(newState: RgripState): Unit = {
    state = newState
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Event publishing
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * RgripRotationEvent – lightweight event published every 5 degrees during rotation.
   *
   * Parameters
   *   angle (Int) – current angle in degrees after this step
   */
  private def publishRotationEvent(angle: Int): Unit = {
    val event = SystemEvent(componentInfo.prefix, EventName("RgripRotationEvent"))
      .madd(RgripInfo.angleKey.set(angle))
    publisher.publish(event): Unit
  }

  /**
   * rgripStateEvent – full-state snapshot published after every meaningful
   * state transition so the Assembly always has an up-to-date picture.
   *
   * Parameters
   *   currentAngle (Int)     – resting angle after the transition
   *   hasGrating   (Boolean) – whether rgrip holds a grating
   *   gratingId    (String)  – ID of the held grating, or "" if none
   *   operation    (String)  – active operation label
   *   stage        (String)  – logical stage name (e.g. "returnSequence")
   *   status       (String)  – outcome ("Completed", "Failure-NoGrating", …)
   */
  private def publishStateEvent(stage: String, status: String): Unit = {
    val event = SystemEvent(componentInfo.prefix, EventName("rgripStateEvent"))
      .madd(
        RgripInfo.currentAngleKey.set(state.currentAngle),
        RgripInfo.hasGratingKey.set(state.hasGrating),
        RgripInfo.gratingIdKey.set(state.gratingId.getOrElse("")),
        RgripInfo.operationKey.set(state.operation),
        RgripInfo.stageKey.set(stage),
        RgripInfo.statusKey.set(status)
      )
    publisher.publish(event): Unit
    log.info(
      s"RgripHcd: published rgripStateEvent – " +
        s"angle=${state.currentAngle} °, hasGrating=${state.hasGrating}, " +
        s"gratingId='${state.gratingId.getOrElse("")}', stage=$stage, status=$status"
    )
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Remaining lifecycle stubs
  // ════════════════════════════════════════════════════════════════════════════
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = log.info("RgripHcd: shutting down")

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}
