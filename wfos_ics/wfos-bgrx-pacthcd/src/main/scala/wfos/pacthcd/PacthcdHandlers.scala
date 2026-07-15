package wfos.pacthcd

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse._
import csw.params.core.models.Id
import csw.params.commands.CommandIssue.{MissingKeyIssue, ParameterValueOutOfRangeIssue, UnsupportedCommandIssue, WrongCommandTypeIssue}
import csw.params.commands.{CommandName, ControlCommand, Observe, Setup}
import csw.time.core.models.UTCTime
import csw.params.events.{EventName, SystemEvent}
import scala.util.{Success, Failure}

import scala.concurrent.ExecutionContextExecutor
import wfos.pacthcd.{PactInfo, PactState}

/**
 * PacthcdHandlers – Push/Pull Actuator HCD
 *
 * Command: Setup("move") with targetPositionKey: Double (0.0=IN, 500.0=OUT)
 *
 * Determines operation from direction of travel:
 *   OUT→IN = "push"  (grating being pushed from magazine into rgrip)
 *   IN→OUT = "pull"  (grating being pulled from rgrip back into magazine)
 *
 * pactPushPullEvent is published when OUT is reached after a push or pull:
 *   operation="push" → pickup transfer complete (rgrip now has grating)
 *   operation="pull" → return transfer complete  (magazine slot now full)
 *
 * The Assembly subscribes to pactPushPullEvent and sends "updateState"
 * commands to lgmHcd and rgripHcd so they update their internal state.
 *
 * Events published:
 *   PactMovementEvent  – per step (currentPosition, stage)
 *   PactStatusEvent    – on arrival at IN or OUT (full state snapshot)
 *   pactPushPullEvent  – when OUT reached after meaningful push/pull
 */
class PacthcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  private val prefix                        = cswCtx.componentInfo.prefix
  private val publisher                     = eventService.defaultPublisher

  private val IN      = PactInfo.inPosition.head  //   0.0 mm
  private val OUT     = PactInfo.outPosition.head // 500.0 mm
  private val EPSILON = 0.01

  private var state: PactState = PactState.initial

  // ── Lifecycle ───────────────────────────────────────────────────────────────

  override def initialize(): Unit = {
    log.info(s"Initializing $prefix  currentPosition=${state.currentPosition} mm")
    if (math.abs(state.currentPosition - OUT) > EPSILON)
      log.warn("PactHcd : Not at OUT (safe) position on startup")
    else
      log.info("PactHcd : At OUT (safe retracted) position")
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  // ── Validation ──────────────────────────────────────────────────────────────

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"PactHcd : Validating command $runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move") =>
            setup.get(PactInfo.targetPositionKey) match {
              case None =>
                publishStatusEvent("Validation", "Failure")
                Invalid(runId, MissingKeyIssue("PactHcd : 'targetPosition' key is missing"))
              case Some(param) =>
                val target = param.head
                if (math.abs(target - IN) > EPSILON && math.abs(target - OUT) > EPSILON) {
                  publishStatusEvent("Validation", "Failure")
                  Invalid(
                    runId,
                    ParameterValueOutOfRangeIssue(
                      s"PactHcd : targetPosition must be IN ($IN mm) or OUT ($OUT mm), got $target"
                    )
                  )
                }
                else {
                  setup.get(PactInfo.operationKey) match {
                    case Some(opParam) =>
                      val op = opParam.head.toLowerCase
                      if (op != "push" && op != "pull" && op != "idle") {
                        publishStatusEvent("Validation", "Failure")
                        Invalid(
                          runId,
                          csw.params.commands.CommandIssue.WrongParameterTypeIssue(
                            s"PactHcd : operation must be 'push' or 'pull', got '${opParam.head}'"
                          )
                        )
                      }
                      else {
                        log.info(s"PactHcd : Validation successful (target=$target mm, operation=${opParam.head})")
                        publishStatusEvent("Validation", "Success")
                        Accepted(runId)
                      }
                    case None =>
                      log.info(s"PactHcd : Validation successful (target=$target mm)")
                      publishStatusEvent("Validation", "Success")
                      Accepted(runId)
                  }
                }
            }
          case CommandName("homing") =>
            Accepted(runId)

          case _ =>
            Invalid(runId, UnsupportedCommandIssue(s"PactHcd : unknown command '${setup.commandName.name}'. Accepts 'move' and 'homing'"))
        }
      case _: Observe => Invalid(runId, WrongCommandTypeIssue("PactHcd accepts only Setup commands"))
      case _          => Invalid(runId, UnsupportedCommandIssue("PactHcd : Invalid command type"))
    }
  }

  // ── Submit ──────────────────────────────────────────────────────────────────

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"PactHcd : Executing command $runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("move")   => onMove(runId, setup)
          case CommandName("homing") => onHoming(runId)
          case _                     => Invalid(runId, UnsupportedCommandIssue("PactHcd : Invalid command type"))
        }
      case _ => Invalid(runId, UnsupportedCommandIssue("PactHcd : Invalid command type"))
    }
  }

  // ── Homing handler ───────────────────────────────────────────────────────────
  /**
   * Homing sequence (Section 9, Step 1 – safe position first).
   * Assembly sends Setup("homing") with no target position.
   * PactHcd reads its own homePosition (OUT = 500 mm) and delegates
   * directly to onMove with a synthetic Setup carrying that position.
   * operation is set to "idle" since this is not a push/pull transfer —
   * pactPushPullEvent must NOT fire during homing.
   */
  private def onHoming(runId: Id): SubmitResponse = {
    val homePos = PactInfo.homePosition.head
    log.info(s"PactHcd [homing]: moving directly to homePosition=$homePos mm (OUT)")

    if (math.abs(state.currentPosition - homePos) <= EPSILON) {
      log.info(s"PactHcd [homing]: already at home position ($homePos mm) – completing immediately")
      publishStatusEvent("homing", "Completed")
      log.info(s"pact publishes pact status event ")
      return Completed(runId)
    }

    state = state.copy(operation = "idle")
    moveActuatorTo(homePos)
    state = state.copy(currentPosition = homePos, operation = "idle")
    PactInfo.currentPosition = PactInfo.currentPositionKey.set(state.currentPosition)

    // pactPushPullEvent must NOT fire during homing (operation is "idle")
    publishStatusEvent("homing", "Completed")
    log.info(s"pact publishes pact status event out side if block")
    Completed(runId)
  }

  // ── Move handler ────────────────────────────────────────────────────────────

  private def onMove(runId: Id, setup: Setup): SubmitResponse = {
    val targetPos = setup(PactInfo.targetPositionKey).head
    val fromPos   = state.currentPosition

    val operation = setup.get(PactInfo.operationKey).map(_.head).getOrElse("idle")

    log.info(s"PactHcd : Moving $fromPos mm → $targetPos mm")

    if (math.abs(fromPos - targetPos) <= EPSILON) {
      log.info(s"PactHcd : Already at target $targetPos mm")
      publishStatusEvent(positionLabel(targetPos), "Completed")
      return Completed(runId)
    }

    state = state.copy(operation = operation)

    // Execute motion
    moveActuatorTo(targetPos)
    state = state.copy(currentPosition = targetPos)
    PactInfo.currentPosition = PactInfo.currentPositionKey.set(state.currentPosition)

    val posLabel = positionLabel(targetPos)
    log.info(s"PactHcd : Reached $posLabel (${state.currentPosition} mm)")

    // Publish arrival status event
    publishStatusEvent(posLabel, "Completed")

    // Publish pactPushPullEvent when OUT is reached after push or pull
    // This is the authoritative signal that a physical grating transfer has completed.
    // The Assembly subscribes to this and sends updateState to lgmHcd and rgripHcd.
    val movingToOUT = math.abs(targetPos - OUT) <= EPSILON
    if (movingToOUT && (operation == "push" || operation == "pull")) {
      log.info(s"PactHcd : Publishing pactPushPullEvent (operation=$operation) – transfer confirmed")
      publishPushPullEvent(operation)
    }

    Completed(runId)
  }

  // ── Actuator motion ─────────────────────────────────────────────────────────

  private def moveActuatorTo(targetPos: Double): Unit = {
    val delay = 100                        // ms
    val step  = PactInfo.movementStep.head // 50 mm

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

      publisher.publish(
        SystemEvent(componentInfo.prefix, EventName("PactMovementEvent"))
          .madd(
            PactInfo.messageKey.set(s"PactHcd : actuator at ${state.currentPosition} mm"),
            PactInfo.currentPositionKey.set(state.currentPosition),
            PactInfo.stageKey.set(state.operation)
          )
      )

      Thread.sleep(delay)
    }
  }

  // ── Event helpers ────────────────────────────────────────────────────────────

  private def positionLabel(pos: Double): String =
    if (math.abs(pos - IN) <= EPSILON) "IN" else "OUT"

  private def publishStatusEvent(stage: String, status: String): Unit = {
    log.info(s"Publishing with prefix = ${componentInfo.prefix}")
    publisher
      .publish(
        SystemEvent(componentInfo.prefix, EventName("PactStatusEvent"))
          .madd(
            PactInfo.stageKey.set(stage),
            PactInfo.statusKey.set(status),
            PactInfo.currentPositionKey.set(state.currentPosition),
            PactInfo.operationKey.set(state.operation)
          )
      )
      .onComplete {
        case Success(_) =>
          log.info("PactStatusEvent published successfully")
        case Failure(ex) =>
          log.error(s"Failed to publish PactStatusEvent: ${ex.getMessage}")
      }(ec)
  }

  /**
   * pactPushPullEvent
   * Published when OUT is reached after a physical transfer move.
   *   operation="push" → pickup complete (rgrip now has grating, lgm slot empty)
   *   operation="pull" → return complete (rgrip no longer has grating, lgm slot full)
   *
   * Assembly receives this and sends "updateState" to lgmHcd and rgripHcd.
   */
  private def publishPushPullEvent(operation: String): Unit = {
    publisher.publish(
      SystemEvent(componentInfo.prefix, EventName("pactPushPullEvent"))
        .madd(
          PactInfo.operationKey.set(operation),
          PactInfo.operationStatusKey.set(s"${operation}Complete"),
          PactInfo.currentPositionKey.set(state.currentPosition)
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
