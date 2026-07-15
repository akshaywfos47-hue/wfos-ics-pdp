package wfos.bgrxassembly

import org.apache.pekko.Done
import scala.util.{Success, Failure}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import com.typesafe.config._
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.alarm.client.AlarmServiceFactory
import csw.alarm.api.scaladsl.AlarmAdminService
import csw.params.commands.CommandResponse._
import csw.params.commands.{ControlCommand, CommandName, Setup, Observe}
import csw.params.commands.CommandIssue.{UnsupportedCommandIssue, RequiredHCDUnavailableIssue, WrongCommandTypeIssue, MissingKeyIssue}
import csw.time.core.models.UTCTime
import csw.params.core.models.{Id, ObsId}
import scala.util.{Success, Failure}
import java.time.Duration

import csw.location.api.models.{ComponentId, ComponentType, LocationRemoved, LocationUpdated, TrackingEvent}
import csw.location.api.models.Connection.PekkoConnection
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.prefix.models.{Prefix, Subsystem}
import csw.params.core.generics.Parameter

import scala.concurrent.{ExecutionContextExecutor, Future}
import wfos.lgriphcd.{LgripInfo, LgripState}
import wfos.rgriphcd.{RgripInfo, RgripState}
import wfos.lgmhcd.{LgmInfo, LgmState}
import wfos.pacthcd.{PactInfo, PactState}
import wfos.bgrxassembly.components.{RgripHcd, LgripHcd, Lgmhcd}
import csw.params.events.{EventKey, EventName, Event, SystemEvent}
import wfos.bgrxassembly.TelemetryManager
import wfos.bgrxassembly.AlarmManager
import wfos.bgrxassembly.BgrxValidator
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import wfos.bgrxassembly.SequenceType
import wfos.bgrxassembly.Step
import Step.*
import wfos.bgrxassembly.api.{AssemblyApi, BgrxApiService, BgrxRoutes, BgrxHttpServer}

class BgrxassemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx)
    with AssemblyApi {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor         = ctx.executionContext
  private val log                                   = loggerFactory.getLogger
  private implicit val system: ActorSystem[Nothing] = ctx.system

  private var rgripHcdCS: Option[CommandService] = None
  private var lgripHcdCS: Option[CommandService] = None
  private var lgmHcdCS: Option[CommandService]   = None
  private var pactHcdCS: Option[CommandService]  = None

  private val rgripHcd: RgripHcd = new RgripHcd()

  implicit val timeout: Timeout = Timeout(5.seconds)

  private val sourcePrefix: Prefix = Prefix("wfos.bgrxAssembly")
  private val obsId: ObsId         = ObsId("2023A-001-123")
  private val locationService      = HttpLocationServiceFactory.makeLocalClient
  private val alarmAdminAPI: AlarmAdminService =
    new AlarmServiceFactory().makeAdminApi(locationService)

  //  // ── Assembly state cache ───────────────────────────────────────────────────
  //  private var rgripState: RgripState = RgripState.initial
  //  private var lgripState: LgripState = LgripState.initial
  //  private var lgmState: LgmState     = LgmState.initial
  //  private var pactState: PactState   = PactState.initial
  //  // assembly state cache code is removed because we are using state
  //  // variables in one file ie AssemblyState.scala

  // After pactHcd reaches OUT, gratingTransferEvent is published. The next
  // sequence step must NOT proceed until lgmHcd confirms it has processed that
  // event by publishing LgmStatusEvent. We store the continuation here and
  // execute it from the LgmStatusEvent handler.
  //
  //   onReturnTransferComplete  – set by returnMovePactHcdToOUT:
  //       logs RETURN-AFTER and (for boot) triggers pickup
  //
  //   onPickupTransferComplete  – set by movePactHcdToOUT (pickup step 4b):
  //       proceeds to lgrip→home and rgrip→observation angle
  private var onReturnTransferComplete: Option[() => Unit] = None
  private var onPickupTransferComplete: Option[() => Unit] = None
  // Set by homingSequence once all 4 HCDs have completed homing.
  // When the boot sequence triggers homing first, this continuation
  // fires the Return → Pickup chain after homing is fully done.
  private var onHomingComplete: Option[() => Unit] = None

  // http routes layer
  private var apiService: BgrxApiService = _
  private var routes: BgrxRoutes         = _

  // manager class
  private val assemblyState = new AssemblyState()
  private val telemetryManager =
    new TelemetryManager(
      loggerFactory,
      assemblyState,
      () => isSequenceRunning,
      () => currentStep,
      publishGratingTransferEvent,
      completeReturnTransfer,
      completePickupTransfer
    )

  private val alarmManger = new AlarmManager()

  private val bgrxValidator = new BgrxValidator(loggerFactory)

  // spawning telementry actor
  private val telemetryEventActor: ActorRef[Event] =
    ctx.spawn(
      TelemetryEventActor(telemetryManager),
      "telemetry-event-actor"
    )

  // =====================================================
  // 1. Sequence Types
  // =====================================================

  // =====================================================
  // 2. Sequence State
  // =====================================================
  private var sequenceRunning: Boolean              = false
  private var currentSequence: Option[SequenceType] = None
  private var currentStepIndex: Int                 = 0

  def isSequenceRunning: Boolean =
    sequenceRunning

  private def currentStep: Step =
    getSequence(currentSequence.get)(currentStepIndex)
  // =====================================================
  // 3. Sequence Definitions
  // =====================================================

  private val homeSequence: List[Step] =
    List(PACT, LGM, LGRIP, RGRIP)

  private val returnSequence: List[Step] =
    List(RGRIP, LGRIP, LGM, PACT)

  private val pickupSequence: List[Step] =
    List(RGRIP, LGRIP, LGM, PACT, LGRIP, RGRIP)

  private val exchangeSequence: List[Step] =
    returnSequence ++ pickupSequence

  // =====================================================
  // 4. Sequence Helper
  // =====================================================

  private def getSequence(sequenceType: SequenceType): List[Step] =
    sequenceType match {
      case SequenceType.HOME     => homeSequence
      case SequenceType.RETURN   => returnSequence
      case SequenceType.PICKUP   => pickupSequence
      case SequenceType.EXCHANGE => exchangeSequence
    }

  private def executeCurrentStep(runId: Id): Unit = {

    val sequence = getSequence(currentSequence.get)

    // Check whether the sequence has completed
    if (currentStepIndex >= sequence.length) {
      log.info(s"$currentSequence sequence completed")
      sequenceRunning = false
      currentSequence = None
      currentStepIndex = 0
      return
    }

    val step = sequence(currentStepIndex)

    if (
      !bgrxValidator.validateCurrentStep(
        currentSequence.get,
        step,
        assemblyState
      )
    ) {
      recoverCurrentStep(runId, step)
      return
    }

    val command =
      BgrxCommandBuilder.buildCommand(
        currentSequence.get,
        step,
        sourcePrefix,
        obsId,
        assemblyState
      )

    sendCurrentStep(runId, step, command)
  }

  private def sendCurrentStep(runId: Id, step: Step, command: Setup): Unit = {

    val hcdName = step match {
      case Step.PACT  => "pacthcd"
      case Step.LGM   => "lgmhcd"
      case Step.LGRIP => "lgriphcd"
      case Step.RGRIP => "rgriphcd"
    }

    resolveAndSubmit(hcdName, command, runId) { case _: Completed =>
      currentStepIndex += 1
      executeCurrentStep(runId)
    }
  }

  // api entry point
  def executeSequence(sequence: SequenceType): Unit = {
    startExecution(
      Id("ui"),
      sequence
    )
  }

  private def startExecution(
      runId: Id,
      sequence: SequenceType
  ): Unit = {

    sequenceRunning = true
    currentSequence = Some(sequence)
    currentStepIndex = 0

    executeCurrentStep(runId)
  }

  private def recoverCurrentStep(
      runId: Id,
      step: Step
  ): Unit = {

    currentSequence.get match {

      // =====================================================
      // HOME
      // =====================================================

      case SequenceType.HOME =>
        step match {

          case Step.PACT =>
            log.info("HOME Recovery: PACT is the first step. No recovery required.")

          case Step.LGM =>
            log.info("HOME Recovery: Validation failed for LGM. Recover by moving PACT to HOME, then retry LGM.")

          case Step.LGRIP =>
            log.info("HOME Recovery: Validation failed for LGRIP. Recover by moving LGM to HOME, then retry LGRIP.")

          case Step.RGRIP =>
            log.info("HOME Recovery: Validation failed for RGRIP. Recover by moving LGRIP to HOME, then retry RGRIP.")
        }

      // =====================================================
      // PICKUP
      // =====================================================

      case SequenceType.PICKUP =>
        step match {

          case Step.RGRIP =>
          // TODO

          case Step.LGRIP =>
          // TODO

          case Step.LGM =>
          // TODO

          case Step.PACT =>
          // TODO
        }

      // =====================================================
      // RETURN
      // =====================================================

      case SequenceType.RETURN =>
        step match {

          case Step.RGRIP =>
          // TODO

          case Step.LGRIP =>
          // TODO

          case Step.LGM =>
          // TODO

          case Step.PACT =>
          // TODO
        }

      // =====================================================
      // EXCHANGE
      // =====================================================

      case SequenceType.EXCHANGE =>
        step match {

          case Step.RGRIP =>
          // TODO

          case Step.LGRIP =>
          // TODO

          case Step.LGM =>
          // TODO

          case Step.PACT =>
          // TODO
        }
    }
  }

  private def initializeClass(): Unit = {
    telemetryManager()
    alarmManger()
    // Create API objects
    apiService = new BgrxApiService(this)
    routes = new BgrxRoutes(apiService)

    val httpServer =
      new BgrxHttpServer(ctx.system, routes)

    httpServer.start()
  }
  // ── Lifecycle ───────────────────────────────────────────────────────────────
  override def initialize(): Unit = {
    log.info("Initializing BgrxAssembly with  ")
    initializeClass()
    log.info(s"Assembly sourcePrefix = $sourcePrefix")

    // Register alarm keys in the CSW Alarm Store before any HCD command runs.
    // HCDs call setSeverity on their first move; if the store isn't populated
    // yet they throw KeyNotFoundException.
    // "valid-alarms.conf" is resolved from the classpath (resources folder).
    try {
      val alarmsConfig: Config = ConfigFactory.parseResources("valid-alarms.conf")
      Await.result(alarmAdminAPI.initAlarms(alarmsConfig), 1.seconds)
      log.info("BgrxAssembly : Alarm store initialised successfully")
    }
    catch {
      case ex: Exception =>
        log.error(s"BgrxAssembly : Alarm store init failed (non-fatal) – ${ex.getMessage}. HCD alarm calls may warn.")
    }
  }

  // ── Location tracking ───────────────────────────────────────────────────────
  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {
    log.info("Bgrx Assembly : onLocationTrackingEvent called")
    trackingEvent match {
      case LocationUpdated(location) =>
        location.connection match {
          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.rgriphcd"), _)) =>
            rgripHcdCS = Some(CommandServiceFactory.make(location))
            log.info("Bgrx Assembly : Command service to RgripHcd created")
          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgriphcd"), _)) =>
            lgripHcdCS = Some(CommandServiceFactory.make(location))
            log.info("Bgrx Assembly : Command service to LgripHcd created")
          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgmhcd"), _)) =>
            lgmHcdCS = Some(CommandServiceFactory.make(location))
            log.info("Bgrx Assembly : Command service to LgmHcd created")
          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.pacthcd"), _)) =>
            pactHcdCS = Some(CommandServiceFactory.make(location))
            log.info("Bgrx Assembly : Command service to PactHcd created")
          case _ => log.info("Unknown HCD encountered")
        }
      case LocationRemoved(_) => log.info("Location Removed")
    }
    if (rgripHcdCS.isDefined && lgripHcdCS.isDefined && lgmHcdCS.isDefined && pactHcdCS.isDefined) {
      log.info("Bgrx Assembly : All HCDs successfully initialized")

      subscribeToHcdEvents()
      if (!sys.props.getOrElse("wfos.test.mode", "false").toBoolean) {
//        startExecution(
//          Id("bgrx"),
//          SequenceType.HOME
//        )
        //    sendCommand(Id("bgrx"))
      }
      else {
        log.info("Bgrx Assembly : test mode – skipping boot sequence")
      }
    }
  }

  // ── Boot-time command ────────────────────────────────────────────────────────
  // Boot sequence (Section 9):
  //   1. Run Homing sequence  (pact → lgm → lgrip → rgrip, each to home position)
  //   2. On Homing completion → run Return sequence (return bgid2 to magazine)
  //   3. On Return completion → run Pickup sequence (pick up bgid3)
  private def sendCommand(runId: Id): SubmitResponse = {
    val homingCommand = Setup(sourcePrefix, CommandName("homing"), Some(obsId))
    log.info("Boot: starting Homing sequence first (Section 9 order: pact → lgm → lgrip → rgrip)")
    onSubmit(runId, homingCommand)
  }

  // Called internally once the Homing sequence fully completes.
  private def startReturnAfterHoming(): Unit = {
    val returnRunId   = Id("boot-return")
    val returnCommand = Setup(sourcePrefix, CommandName("gratingReturn"), Some(obsId))
    log.info("Boot: Homing complete – starting Return sequence for bgid2")
    validateCommand(returnRunId, returnCommand) match {
      case Accepted(id) => onGratingReturn(id, returnCommand): Unit
      case Invalid(_, err) =>
        log.error(s"Boot: Return sequence validation failed – ${err.reason}")
    }
  }

  // Called internally once the Return sequence fully completes (returnMovePactHcdToOUT).
  private def startPickupAfterReturn(): Unit = {
    val pickupRunId = Id("boot-pickup")
    val pickupCommand = Setup(sourcePrefix, CommandName("pickup"), Some(obsId))
      .madd(
        RgripInfo.targetAngleKey.set(35), // bgid3 observation angle (25–35 °, using 28 °)
        RgripInfo.gratingModeKey.set("bgid3"),
        RgripInfo.cwKey.set(6000)
      )
    log.info("Boot: Return complete – starting Pickup sequence for bgid3")
    validateCommand(pickupRunId, pickupCommand) match {
      case Accepted(id) => onSetup(id, pickupCommand): Unit
      case Invalid(_, err) =>
        log.error(s"Boot: Pickup sequence validation failed – ${err.reason}")
    }
  }

  // ── Validation ──────────────────────────────────────────────────────────────
  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"Bgrx Assembly : Validating command $runId")
    controlCommand match {
      case setup: Setup =>
        setup.commandName match {

          case CommandName("pickup") =>
            // Validate bgid, targetAngle, cw, and angle-bgid pairing
            rgripHcd.validateParameters(setup) match {
              case Right(_)    => Accepted(runId)
              case Left(error) => Invalid(runId, error)
            }

          case CommandName("gratingReturn") =>
            // Only pre-condition: rgrip must currently be holding a grating.
            // No need to validate targetAngle/cw – those are not supplied for return.
            // bgid is read from rgripState.gratingId inside the sequence.
            if (!assemblyState.rgripState.hasGrating)
              Invalid(runId, UnsupportedCommandIssue(s"gratingReturn rejected: rgripHcd is not holding a grating (hasGrating=false)"))
            else
              Accepted(runId)

          case CommandName("homing") =>
            // Homing is always valid – each HCD decides autonomously whether it
            // needs to move. No parameters are required.
            Accepted(runId)

          case _ =>
            Invalid(runId, UnsupportedCommandIssue(s"$sourcePrefix accepts 'pickup', 'gratingReturn', or 'homing' Setup commands"))
        }
      case _: Observe => Invalid(runId, WrongCommandTypeIssue("Observe commands not supported"))
      case _          => Invalid(runId, UnsupportedCommandIssue("Invalid command type"))
    }
  }

  // ── Submit ──────────────────────────────────────────────────────────────────
  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"Bgrx Assembly : handling command $runId")

    controlCommand match {
      case setup: Setup =>
        setup.commandName match {
          case CommandName("pickup")        => onSetup(runId, setup)
          case CommandName("gratingReturn") => onGratingReturn(runId, setup)
          case CommandName("homing")        => onHoming(runId)
          case _                            => Invalid(runId, UnsupportedCommandIssue("Unsupported command"))
        }
      case _: Observe => Invalid(runId, WrongCommandTypeIssue("Observe commands not supported"))
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Event subscriptions
  // ─────────────────────────────────────────────────────────────────────────────
  private def subscribeToHcdEvents(): Unit = {
    val subscriber = eventService.defaultSubscriber

    // ── rgrip ──────────────────────────────────────────────────────────────────
//    subscriber.subscribeCallback(
//      Set(EventKey(Prefix("wfos.bgrxAssembly.rgriphcd"), EventName("RgripRotationEvent"))),
//      event =>
//        telemetryManager.eventServiceHelper(event)
//        event match {
//          case sysEvent: SystemEvent =>
//            val angle = sysEvent(RgripInfo.angleKey).head
//            rgripState = rgripState.copy(currentAngle = angle)
//            log.info(s" %%%%%%%%%%%%%%% in assembly Assembly [RgripRotationEvent]: rgrip at $angle°")
//          case _ =>
//        }
//    )

    log.info("Subscribed to PactStatusEvent")

    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.rgriphcd"), EventName("RgripRotationEvent"))),
      telemetryEventActor
    )

    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.rgriphcd"), EventName("rgripStateEvent"))),
      telemetryEventActor
    )

    // ── lgrip ──────────────────────────────────────────────────────────────────
    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("lgripMoveToExchangePositionEvent"))),
      telemetryEventActor
    )

    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("lgripMoveToHomePositionEvent"))),
      telemetryEventActor
    )
    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("lgripStateEvent"))),
      telemetryEventActor
    )

    // ── lgm ────────────────────────────────────────────────────────────────────
    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.lgmhcd"), EventName("LgmMovementEvent"))),
      telemetryEventActor
    )

    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.lgmhcd"), EventName("LgmStatusEvent"))),
      telemetryEventActor
    )

    // ── pact ───────────────────────────────────────────────────────────────────
    subscriber.subscribeActorRef(
      Set(EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("PactMovementEvent"))),
      telemetryEventActor
    )

    subscriber
      .subscribeActorRef(
        Set(EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("PactStatusEvent"))),
        telemetryEventActor
      )

//    subscriber.subscribeActorRef(
//      Set(EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("pactPushPullEvent"))),
//      telemetryEventActor
//    )
  }

  private def publishGratingTransferEvent(operation: String, bgid: String): Unit = {
    val event = SystemEvent(sourcePrefix, EventName("gratingTransferEvent"))
      .madd(
        RgripInfo.operationKey.set(operation),
        LgmInfo.targetBgidKey.set(bgid)
      )
    eventService.defaultPublisher.publish(event)
    log.info(s"Assembly : published gratingTransferEvent (operation=$operation, bgid=$bgid)")
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HOMING SEQUENCE  (Section 9)
  // Order: pactHCD → lgmHCD → lgripHCD → rgripHCD
  // Assembly sends Setup("homing") to each HCD in turn.
  // No target position is sent – each HCD reads its own home key.
  // ═══════════════════════════════════════════════════════════════════════════

  private def onHoming(runId: Id): SubmitResponse = {
    log.info(s"Bgrx Assembly : Starting Homing Sequence – runId=$runId")
    logAllHcdStates("HOMING-BEFORE")
    homingMovePact(runId)
    Started(runId)
  }

  // Step 1 – pactHCD: move to OUT (safe retracted) first
  private def homingMovePact(runId: Id): Unit = {
    log.info("Homing Step 1: sending homing command to pactHcd (target: OUT = 500 mm)")
    val cmd = Setup(sourcePrefix, CommandName("homing"), Some(obsId))
    resolveAndSubmit("pacthcd", cmd, runId) { case _: Completed =>
      log.info("Homing Step 1 DONE: pactHcd at OUT (safe position)")
      homingMoveLgm(runId)
    }
  }

  // Step 2 – lgmHCD: move to home position (250 mm)
  private def homingMoveLgm(runId: Id): Unit = {
    log.info("Homing Step 2: sending homing command to lgmhcd (target: 250 mm)")
    val cmd = Setup(sourcePrefix, CommandName("homing"), Some(obsId))
    resolveAndSubmit("lgmhcd", cmd, runId) { case _: Completed =>
      log.info("Homing Step 2 DONE: lgmhcd at home (250 mm)")
      homingMoveLgrip(runId)
    }
  }

  // Step 3 – lgripHCD: move to home position (0 mm)
  private def homingMoveLgrip(runId: Id): Unit = {
    log.info("Homing Step 3: sending homing command to lgriphcd (target: 0 mm)")
    val cmd = Setup(sourcePrefix, CommandName("homing"), Some(obsId))
    resolveAndSubmit("lgriphcd", cmd, runId) { case _: Completed =>
      log.info("Homing Step 3 DONE: lgriphcd at home (0 mm)")
      homingMoveRgrip(runId)
    }
  }

  // Step 4 – rgripHCD: rotate to home angle (0°)
  private def homingMoveRgrip(runId: Id): Unit = {
    log.info("Homing Step 4: sending homing command to rgriphcd (target: 0°)")
    val cmd = Setup(sourcePrefix, CommandName("homing"), Some(obsId))
    resolveAndSubmit("rgriphcd", cmd, runId) { case completed: Completed =>
      log.info("Homing Step 4 DONE: rgriphcd at home (0°) – HOMING SEQUENCE COMPLETE")
      logAllHcdStates("HOMING-AFTER")
      commandResponseManager.updateCommand(completed.withRunId(runId))

      // If homing was triggered by the boot sequence (runId == "bgrx"),
      // fire the return sequence continuation immediately after homing completes.
      if (runId.id == "bgrx") {
        log.info("Boot: Homing confirmed – starting Return sequence")
        ctx.system.scheduler.scheduleOnce(Duration.ofMillis(10), () => startReturnAfterHoming(), ec)
      }

      // Execute any external continuation registered by the caller
      // (e.g. a future operator-triggered homing that chains another sequence).
      onHomingComplete.foreach { continuation =>
        onHomingComplete = None
        ctx.system.scheduler.scheduleOnce(Duration.ofMillis(10), () => continuation(), ec)
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PICKUP SEQUENCE
  // ═══════════════════════════════════════════════════════════════════════════

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    log.info(s"Bgrx Assembly : Starting Grating Pickup Sequence – runId=$runId")
    logAllHcdStates("PICKUP-BEFORE")
    moveRgripHcd(runId, setup)
    Started(runId)
  }

  private def moveRgripHcd(runId: Id, originalSetup: Setup): Unit = {
    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head))
    resolveAndSubmit("rgriphcd", command, runId) { case _: Completed =>
      log.info("Pickup Step 1 DONE: rgrip at exchange (35°)")
      moveLgripHcdToExchange(runId, originalSetup)
    }
  }

  private def moveLgripHcdToExchange(runId: Id, originalSetup: Setup): Unit = {
    if (assemblyState.rgripState.currentAngle != RgripInfo.exchangeAngle.head) {
      log.warn(s"Pickup Step 2: rgrip not at exchange (${assemblyState.rgripState.currentAngle}°) – re-invoking moveRgripHcd to recover")
      moveRgripHcd(runId, originalSetup)
      return
    }
    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(LgripInfo.targetPositionKey.set(LgripInfo.exchangePosition))
    resolveAndSubmit("lgriphcd", command, runId) { case _: Completed =>
      log.info("Pickup Step 2 DONE: lgrip at exchange (1000mm)")
      moveLgmHcd(runId, originalSetup)
    }
  }

  private def moveLgmHcd(runId: Id, originalSetup: Setup): Unit = {
    // Validation: rgrip and lgrip must be at exchange position, and
    // rgripHasGrating must be false (rgrip must be empty before picking up a new grating).
    // Recovery: re-invoke the appropriate earlier step, which will chain back here.
    // rgripHasGrating==true is a real state error – no mechanical recovery possible.
    if (assemblyState.rgripState.currentAngle != RgripInfo.exchangeAngle.head) {
      log.warn(s"Pickup Step 3: rgrip not at exchange (${assemblyState.rgripState.currentAngle}°) – re-invoking moveRgripHcd to recover")
      moveRgripHcd(runId, originalSetup)
      return
    }
    if (assemblyState.lgripState.currentPosition != LgripInfo.exchangePosition) {
      log.warn(
        s"Pickup Step 3: lgrip not at exchange (${assemblyState.lgripState.currentPosition} mm) – re-invoking moveLgripHcdToExchange to recover"
      )
      moveLgripHcdToExchange(runId, originalSetup)
      return
    }
    if (assemblyState.rgripState.hasGrating) {
      // rgrip still holds a grating – this means the prior return sequence did not
      // complete successfully. Cannot proceed; abort with an error.
      log.error(
        s"Pickup Step 3 ABORTED: rgrip still has grating '${assemblyState.rgripState.gratingId.getOrElse("")}' – rgrip must be empty before lgm is aligned for pickup"
      )
      commandResponseManager.updateCommand(
        Invalid(
          runId,
          UnsupportedCommandIssue(
            "moveLgmHcd: rgripHasGrating must be false before aligning lgm for pickup – complete the return sequence first"
          )
        )
      )
      return
    }

    val bgid              = originalSetup(RgripInfo.gratingModeKey).head
    val slotIndex         = LgmInfo.bgidToIndex(bgid)
    val targetMagazinePos = LgmInfo.gratingExchangePosition.head - LgmInfo.gratingLinearDistance(slotIndex)

    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(LgmInfo.targetGratingPositionKey.set(targetMagazinePos))

    resolveAndSubmit("lgmhcd", command, runId) { case completed: Completed =>
      log.info(s"Pickup Step 3 DONE: lgm aligned '$bgid' to exchange position")
      commandResponseManager.updateCommand(completed.withRunId(runId))
      movePactHcdToIN(runId, originalSetup)
    }
  }

  private def movePactHcdToIN(runId: Id, originalSetup: Setup): Unit = {
    // Validation: rgripHCD, lgripHCD and lgmHCD must all be at exchange
    // position, and pactHcd must be at OUT (retracted) before starting a push.
    // Recovery: re-invoke the appropriate earlier step, which will chain back here.
    if (assemblyState.rgripState.currentAngle != RgripInfo.exchangeAngle.head) {
      log.warn(s"Pickup Step 4a: rgrip not at exchange (${assemblyState.rgripState.currentAngle}°) – re-invoking moveRgripHcd to recover")
      moveRgripHcd(runId, originalSetup)
      return
    }
    if (assemblyState.lgripState.currentPosition != LgripInfo.exchangePosition) {
      log.warn(
        s"Pickup Step 4a: lgrip not at exchange (${assemblyState.lgripState.currentPosition} mm) – re-invoking moveLgripHcdToExchange to recover"
      )
      moveLgripHcdToExchange(runId, originalSetup)
      return
    }
    val bgid              = originalSetup(RgripInfo.gratingModeKey).head
    val slotIndex         = LgmInfo.bgidToIndex(bgid)
    val targetMagazinePos = LgmInfo.gratingExchangePosition.head - LgmInfo.gratingLinearDistance(slotIndex)
    if (assemblyState.lgmState.currentPosition != targetMagazinePos) {
      log.warn(
        s"Pickup Step 4a: lgm not at exchange,Current Position (${assemblyState.lgmState.currentPosition} mm) – re-invoking returnMoveLgmHcd to recover"
      )
      moveLgmHcd(runId, originalSetup)
      return
    }
    if (assemblyState.pactState.currentPosition != PactInfo.outPosition.head) {
      log.warn(s"Pickup Step 4a: pact not at OUT (${assemblyState.pactState.currentPosition} mm) – moving pact to OUT before retrying push")
      val recoverCmd = Setup(sourcePrefix, CommandName("move"), Some(obsId))
        .madd(PactInfo.targetPositionKey.set(PactInfo.outPosition.head), PactInfo.operationKey.set("push"))
      resolveAndSubmit("pacthcd", recoverCmd, runId) { case _: Completed =>
        log.info("Pickup Step 4a RECOVERY: pact moved to OUT – retrying movePactHcdToIN")
        movePactHcdToIN(runId, originalSetup)
      }
      return
    }

    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(PactInfo.targetPositionKey.set(PactInfo.inPosition.head), PactInfo.operationKey.set("push"))
    resolveAndSubmit("pacthcd", command, runId) { case _: Completed =>
      log.info("Pickup Step 4a DONE: pact at IN (grating pushed into rgrip)")
      movePactHcdToOUT(runId, originalSetup)
    }
  }

  private def movePactHcdToOUT(runId: Id, originalSetup: Setup): Unit = {
    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(PactInfo.targetPositionKey.set(PactInfo.outPosition.head), PactInfo.operationKey.set("push"))
    resolveAndSubmit("pacthcd", command, runId) { case _: Completed =>
      log.info(
        "Pickup Step 4b DONE: pact retracted to OUT – gratingTransferEvent(push) in flight. " +
          "Deferring lgrip+rgrip steps until lgmHcd confirms transfer via LgmStatusEvent."
      )
      // Store the continuation. Steps 5 & 6 only start once LgmStatusEvent
      // confirms lgmHcd (and rgripHcd) have processed gratingTransferEvent(push).
      onPickupTransferComplete = Some(() => moveLgripHcdToHome(runId, originalSetup))
    }
  }

  private def moveLgripHcdToHome(runId: Id, originalSetup: Setup): Unit = {
    // Validation: rgripHasGrating must be true (grating was successfully
    // pushed into rgrip) and rgripGratingId must match the requested target bgid,
    // confirming rgrip is holding the correct grating before lgrip retracts.
    val targetBgid = originalSetup(RgripInfo.gratingModeKey).head
    if (!assemblyState.rgripState.hasGrating) {
      log.error("Pickup Step 5 ABORTED: rgripHasGrating is false – grating was not successfully pushed into rgrip")
      commandResponseManager.updateCommand(
        Invalid(
          runId,
          UnsupportedCommandIssue("moveLgripHcdToHome: rgripHasGrating must be true before retracting lgrip – grating push may have failed")
        )
      )
      return
    }
    assemblyState.rgripState.gratingId match {
      case Some(gratingId) if gratingId != targetBgid =>
        log.error(s"Pickup Step 5 ABORTED: rgrip holds '$gratingId' but expected '$targetBgid' – wrong grating engaged")
        commandResponseManager.updateCommand(
          Invalid(
            runId,
            UnsupportedCommandIssue(
              s"moveLgripHcdToHome: rgripGratingId '$gratingId' != targetBgid '$targetBgid' – rgrip has wrong grating, start engagement sequence"
            )
          )
        )
        return
      case None =>
        log.error("Pickup Step 5 ABORTED: rgripGratingId is unknown even though hasGrating is true")
        commandResponseManager.updateCommand(
          Invalid(
            runId,
            UnsupportedCommandIssue("moveLgripHcdToHome: rgripGratingId is unknown – cannot verify correct grating before retracting lgrip")
          )
        )
        return
      case _ => // gratingId == targetBgid → correct grating confirmed, proceed
    }

    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(LgripInfo.targetPositionKey.set(LgripInfo.homePosition))
    resolveAndSubmit("lgriphcd", command, runId) { case _: Completed =>
      log.info("Pickup Step 5 DONE: lgrip at home (0mm)")
      moveRgripHcdToTargetAngle(runId, originalSetup)
    }
  }

  private def moveRgripHcdToTargetAngle(runId: Id, originalSetup: Setup): Unit = {
    // Validation: lgripHCD must be at home position (0 mm) before
    // rgrip is allowed to rotate to the observation target angle.
    // Recovery: re-invoke moveLgripHcdToHome, which will chain back here.
    if (assemblyState.lgripState.currentPosition != LgripInfo.homePosition) {
      log.warn(
        s"Pickup Step 6: lgrip not at home (${assemblyState.lgripState.currentPosition} mm) – re-invoking moveLgripHcdToHome to recover"
      )
      moveLgripHcdToHome(runId, originalSetup)
      return
    }

    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(
        originalSetup(RgripInfo.targetAngleKey),
        originalSetup(RgripInfo.gratingModeKey),
        originalSetup(RgripInfo.cwKey)
      )
    resolveAndSubmit("rgriphcd", command, runId) { case completed: Completed =>
      log.info("Pickup Step 6 DONE: rgrip at target angle – PICKUP SEQUENCE COMPLETE")
      ctx.system.scheduler.scheduleOnce(Duration.ofMillis(200), () => logAllHcdStates("PICKUP-AFTER"), ec)
      commandResponseManager.updateCommand(completed.withRunId(runId))
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // RETURN SEQUENCE
  // ═══════════════════════════════════════════════════════════════════════════

  private def onGratingReturn(runId: Id, setup: Setup): SubmitResponse = {
    log.info(s"Bgrx Assembly : Starting Grating Return Sequence – runId=$runId")
    logAllHcdStates("RETURN-BEFORE")
    // Capture bgid NOW before gratingTransferEvent can clear rgripState.gratingId
    val bgidOpt = assemblyState.rgripState.gratingId
    if (bgidOpt.isEmpty) {
      log.error("Return sequence ABORTED: rgripState.gratingId is empty at sequence start")
      commandResponseManager.updateCommand(Invalid(runId, UnsupportedCommandIssue("rgrip is not holding a grating")))
      return Started(runId)
    }
    returnMoveRgripHcd(runId, bgidOpt.get)
    Started(runId)
  }

  private def returnMoveRgripHcd(runId: Id, bgid: String): Unit = {
    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head))
    resolveAndSubmit("rgriphcd", command, runId) { case _: Completed =>
      log.info("Return Step 1 DONE: rgrip at exchange (35°)")
      returnMoveLgripHcd(runId, bgid)
    }
  }

  private def returnMoveLgripHcd(runId: Id, bgid: String): Unit = {
    if (assemblyState.rgripState.currentAngle != RgripInfo.exchangeAngle.head) {
      log.warn(
        s"Return Step 2: rgrip not at exchange (${assemblyState.rgripState.currentAngle}°) – re-invoking returnMoveRgripHcd to recover"
      )
      returnMoveRgripHcd(runId, bgid)
      return
    }
    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(LgripInfo.targetPositionKey.set(LgripInfo.exchangePosition))
    resolveAndSubmit("lgriphcd", command, runId) { case _: Completed =>
      log.info("Return Step 2 DONE: lgrip at exchange (1000mm)")
      returnMoveLgmHcd(runId, bgid)
    }
  }

  private def returnMoveLgmHcd(runId: Id, bgid: String): Unit = {
    // Validation: rgrip and lgrip must be at exchange position before
    // commanding lgmHcd. Also verify lgm empty-slot bgid matches the grating
    // rgrip is holding (magazine must have the correct empty slot ready).
    // Recovery: re-invoke the appropriate earlier step to fix positioning,
    // which will chain back here once the HCD reaches exchange position.
    if (assemblyState.rgripState.currentAngle != RgripInfo.exchangeAngle.head) {
      log.warn(
        s"Return Step 3: rgrip not at exchange (${assemblyState.rgripState.currentAngle}°) – re-invoking returnMoveRgripHcd to recover"
      )
      returnMoveRgripHcd(runId, bgid)
      return
    }
    if (assemblyState.lgripState.currentPosition != LgripInfo.exchangePosition) {
      log.warn(
        s"Return Step 3: lgrip not at exchange (${assemblyState.lgripState.currentPosition} mm) – re-invoking returnMoveLgripHcd to recover"
      )
      returnMoveLgripHcd(runId, bgid)
      return
    }
    assemblyState.lgmState.emptySlotBgid match {
      case Some(emptyBgid) if emptyBgid != bgid =>
        // This is a real data/state error – no mechanical recovery possible here.
        log.error(
          s"Return Step 3 ABORTED: lgm empty slot bgid '$emptyBgid' does not match rgrip grating bgid '$bgid' – magazine slot mismatch"
        )
        commandResponseManager.updateCommand(
          Invalid(
            runId,
            UnsupportedCommandIssue(s"returnMoveLgmHcd: lgm empty slot bgid '$emptyBgid' != rgrip grating '$bgid' – magazine slot mismatch")
          )
        )
        return
      case None =>
        log.warn("Return Step 3 WARNING: lgm emptySlotBgid not yet known – proceeding; lgmHcd will perform its own slot-alignment check")
      case _ => // emptySlotBgid == bgid → correct slot, proceed
    }

    if (assemblyState.pactState.currentPosition != PactInfo.outPosition.head) {
      log.warn(s"Return Step 3: pact not at OUT (${assemblyState.pactState.currentPosition} mm) – moving pact to OUT before retrying pull")
      val recoverCmd = Setup(sourcePrefix, CommandName("move"), Some(obsId))
        .madd(PactInfo.targetPositionKey.set(PactInfo.outPosition.head), PactInfo.operationKey.set("pull"))
      resolveAndSubmit("pacthcd", recoverCmd, runId) { case _: Completed =>
        log.info("Return Step 3 RECOVERY: pact moved to OUT – retrying returnMoveLgmHcd")
        returnMoveLgmHcd(runId, bgid)
      }
      return
    }

    val slotIndex         = LgmInfo.bgidToIndex(bgid)
    val targetMagazinePos = LgmInfo.gratingExchangePosition.head - LgmInfo.gratingLinearDistance(slotIndex)

    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(LgmInfo.targetGratingPositionKey.set(targetMagazinePos))

    resolveAndSubmit("lgmhcd", command, runId) { case _: Completed =>
      log.info(s"Return Step 3 DONE: lgm empty slot for '$bgid' at exchange position")
      returnMovePactHcdToIN(runId, bgid)
    }
  }

  private def returnMovePactHcdToIN(runId: Id, bgid: String): Unit = {
    // Validation: rgrip and lgrip must be at exchange position, and
    // lgmHcd empty slot must be at exchange position before pactHcd is commanded.
    // Also pactHcd must be at OUT (retracted) position before starting a pull.
    // Recovery: re-invoke the appropriate earlier step, which will chain back here.
    // Note: bgid is re-read from rgripState since it was captured at sequence start.
    val bgid = assemblyState.rgripState.gratingId.getOrElse("")
    if (assemblyState.rgripState.currentAngle != RgripInfo.exchangeAngle.head) {
      log.warn(
        s"Return Step 4a: rgrip not at exchange (${assemblyState.rgripState.currentAngle}°) – re-invoking returnMoveRgripHcd to recover"
      )
      returnMoveRgripHcd(runId, bgid)
      return
    }
    if (assemblyState.lgripState.currentPosition != LgripInfo.exchangePosition) {
      log.warn(
        s"Return Step 4a: lgrip not at exchange (${assemblyState.lgripState.currentPosition} mm) – re-invoking returnMoveLgripHcd to recover"
      )
      returnMoveLgripHcd(runId, bgid)
      return
    }

    val slotIndex         = LgmInfo.bgidToIndex(bgid)
    val targetMagazinePos = LgmInfo.gratingExchangePosition.head - LgmInfo.gratingLinearDistance(slotIndex)
    if (assemblyState.lgmState.currentPosition != targetMagazinePos) {
      log.warn(
        s"Return Step 4a: lgm not at exchange,Current Position (${assemblyState.lgmState.currentPosition} mm) – re-invoking returnMoveLgmHcd to recover"
      )
      returnMoveLgmHcd(runId, bgid)
      return
    }
    if (assemblyState.pactState.currentPosition != PactInfo.outPosition.head) {
      log.warn(s"Return Step 4a: pact not at OUT (${assemblyState.pactState.currentPosition} mm) – moving pact to OUT before retrying pull")
      val recoverCmd = Setup(sourcePrefix, CommandName("move"), Some(obsId))
        .madd(PactInfo.targetPositionKey.set(PactInfo.outPosition.head), PactInfo.operationKey.set("pull"))
      resolveAndSubmit("pacthcd", recoverCmd, runId) { case _: Completed =>
        log.info("Return Step 4a RECOVERY: pact moved to OUT – retrying returnMovePactHcdToIN")
        returnMovePactHcdToIN(runId, bgid)
      }
      return
    }

    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(PactInfo.targetPositionKey.set(PactInfo.inPosition.head), PactInfo.operationKey.set("pull"))
    resolveAndSubmit("pacthcd", command, runId) { case _: Completed =>
      log.info("Return Step 4a DONE: pact at IN (attached to grating)")
      returnMovePactHcdToOUT(runId)
    }
  }

  private def returnMovePactHcdToOUT(runId: Id): Unit = {
    val command = Setup(sourcePrefix, CommandName("move"), Some(obsId))
      .madd(PactInfo.targetPositionKey.set(PactInfo.outPosition.head), PactInfo.operationKey.set("pull"))
    resolveAndSubmit("pacthcd", command, runId) { case completed: Completed =>
      log.info(
        "Return Step 4b DONE: pact retracted to OUT – gratingTransferEvent(pull) in flight. " +
          "Deferring RETURN-AFTER log until lgmHcd confirms transfer via LgmStatusEvent."
      )
      commandResponseManager.updateCommand(completed.withRunId(runId))
      // Store the continuation. RETURN-AFTER logging (and boot pickup trigger)
      // only runs once LgmStatusEvent confirms both HCDs have processed the transfer.
      onReturnTransferComplete = Some(() => {
        log.info("Return sequence fully confirmed by lgmHcd – executing post-return steps")
        logAllHcdStates("RETURN-AFTER")
        // boot-return runId = triggered by homing→return→pickup boot chain
        // bgrx runId        = triggered by legacy direct return boot (kept for compat)
        if (runId.id == "boot-return" || runId.id == "bgrx") {
          startPickupAfterReturn()
        }
      })
    }
  }

  // ── State snapshot logger ──────────────────────────────────────────────────
  private def logAllHcdStates(label: String): Unit = {
    log.info(s"════ HCD STATE SNAPSHOT [$label] ════")
    log.info(
      s"  rgripHcd : angle=${assemblyState.rgripState.currentAngle}°, " +
        s"hasGrating=${assemblyState.rgripState.hasGrating}, gratingId='${assemblyState.rgripState.gratingId.getOrElse("")}', " +
        s"op=${assemblyState.rgripState.operation}"
    )
    log.info(
      s"  lgripHcd : position=${assemblyState.lgripState.currentPosition} mm, op=${assemblyState.lgripState.operation}"
    )
    log.info(
      s"  lgmHcd   : position=${assemblyState.lgmState.currentPosition} mm, op=${assemblyState.lgmState.operation}, " +
        s"emptySlot=${assemblyState.lgmState.emptySlot}, emptySlotBgid='${assemblyState.lgmState.emptySlotBgid.getOrElse("")}'"
    )
    log.info(
      s"  pactHcd  : position=${assemblyState.pactState.currentPosition} mm, op=${assemblyState.pactState.operation}"
    )
    log.info(s"════════════════════════════════════════════")
  }

  // ── Generic resolve + submit helper ─────────────────────────────────────────
  private def resolveAndSubmit(
      hcdName: String,
      command: Setup,
      runId: Id
  )(onResponse: PartialFunction[SubmitResponse, Unit]): Unit = {
    val connection    = PekkoConnection(ComponentId(Prefix(s"wfos.bgrxAssembly.$hcdName"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get
    val cs            = CommandServiceFactory.make(pekkoLocation)

    cs.submit(command).onComplete {
      case Success(response) =>
        if (onResponse.isDefinedAt(response)) {
          onResponse(response)
        }
        else {
          response match {
            case error: Invalid =>
              log.error(s"Bgrx Assembly [$hcdName] : Command failed – ${error.issue}")
              commandResponseManager.updateCommand(error.withRunId(runId))
            case other =>
              commandResponseManager.updateCommand(other.withRunId(runId))
          }
        }
      case Failure(exception) =>
        log.error(s"Bgrx Assembly [$hcdName] : Command Future failed – ${exception.getMessage}")
        commandResponseManager.updateCommand(Invalid(runId, UnsupportedCommandIssue(s"Communication failure: ${exception.getMessage}")))
    }
  }

  // Telementry helper methods
  private def completeReturnTransfer(): Unit = {
    onReturnTransferComplete.foreach { continuation =>
      onReturnTransferComplete = None

      log.info(
        "Assembly: gratingTransfer(pull) confirmed – executing return continuation"
      )

      ctx.system.scheduler.scheduleOnce(
        Duration.ofMillis(10),
        () => continuation(),
        ec
      )
    }
  }

  private def completePickupTransfer(): Unit = {
    onPickupTransferComplete.foreach { continuation =>
      onPickupTransferComplete = None

      log.info(
        "Assembly: gratingTransfer(push) confirmed – executing pickup continuation"
      )

      ctx.system.scheduler.scheduleOnce(
        Duration.ofMillis(10),
        () => continuation(),
        ec
      )
    }
  }

  // ── Lifecycle stubs ──────────────────────────────────────────────────────────
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}
  override def onShutdown(): Unit                                        = {}
  override def onGoOffline(): Unit                                       = {}
  override def onGoOnline(): Unit                                        = {}
  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit  = {}
  override def onOperationsMode(): Unit                                  = {}
}
