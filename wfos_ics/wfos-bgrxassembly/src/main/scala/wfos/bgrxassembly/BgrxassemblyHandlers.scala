package wfos.bgrxassembly

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import com.typesafe.config.*
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.alarm.client.AlarmServiceFactory
import csw.alarm.api.scaladsl.{AlarmAdminService, AlarmService, AlarmSubscription}
import csw.params.commands.CommandResponse.*
import csw.params.commands.{CommandName, ControlCommand, Observe, Setup}
import csw.params.commands.CommandIssue.{RequiredHCDUnavailableIssue, UnsupportedCommandIssue, WrongCommandTypeIssue}
import csw.time.core.models.UTCTime
import csw.params.core.models.{Id, ObsId}
import csw.location.api.models.{ComponentId, ComponentType, LocationRemoved, LocationUpdated, TrackingEvent}
import csw.location.api.models.Connection.PekkoConnection
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.prefix.models.{Prefix, Subsystem}
import csw.params.core.generics.Parameter
import csw.params.events.{Event, SystemEvent}
//import wfos.bgrxassembly.state.{AssemblyStateVariable, LgmState, PactState}
// import csw.params.core.generics.{Key,KeyType, Parameter}
import scala.concurrent.{ExecutionContextExecutor, Future}
// import scala.util.{Success, Failure}
import wfos.lgriphcd.LgripInfo
import wfos.rgriphcd.RgripInfo
import wfos.lgmhcd.LgmInfo
import wfos.pacthcd.PactInfo
import wfos.bgrxassembly.components.{RgripHcd, LgripHcd, Lgmhcd} //Make sure to import Pacthcd..
import csw.params.events.{EventKey, EventName}

import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await

//import wfos.bgrxassembly.state.RgripState
//import wfos.bgrxassembly.state.LgripState
import wfos.bgrxassembly.state._

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to Rgriphcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/commons/framework.html
 */
class BgrxassemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger

  private implicit val system: ActorSystem[Nothing] = ctx.system

  // private var hcdLocation: AkkaLocation     = _
  private var rgripHcdCS: Option[CommandService] = None
  private var lgripHcdCS: Option[CommandService] = None
  private var lgmHcdCS: Option[CommandService]   = None
  private var pactHcdCS: Option[CommandService]  = None

  var rgripRunning: Boolean = false

  private val rgripHcd: RgripHcd = new RgripHcd()
  // private val lgmHcd: LgmHcd     = new LgmHcd()

  implicit val timeout: Timeout = Timeout(5.seconds)

  // Prefix of assembly
  private val sourcePrefix: Prefix = Prefix("wfos.bgrxAssembly")
  private val obsId: ObsId         = ObsId("2023A-001-123")

//rgrip status variables
  private var rgripState = RgripState(
    currentAngle = 28,
    hasGrating = true,
    gratingId = Some("bgid2")
  )
//lgrip state variables
  private var lgripState = LgripState(
    currentPosition = 0
  )

//lgm stste variable
  private var lgmState = LgmState(
    currentPosition = 112.5,
    emptySlot = true,
    emptySlotIndex = Some(1),
    emptySlotBgid = Some("bgid2"),
    emptySlotLinearDistance = Some(112.5)
  )

  private var pactState = PactState(
    currentPosition = 500.0
  )

  private var assemblyStateVariable =
    AssemblyStateVariable(
      currentOperation = EXCHANGE,
      currentGratingMode = RgripInfo.gratingModeKey.set("bgid2")
    )

  sealed trait Step

  case object IDLE extends Step

  case object RGRIP extends Step

  case object LGRIP extends Step

  case object LGM extends Step

  case object PACT extends Step

  var currentStep1: Step = IDLE

  def isEventAllowed(step: Step, source: String): Boolean = {
    step match {

      case RGRIP => source == "RGRIP"

      case LGRIP => source == "LGRIP"

      case LGM => source == "LGM"

      case PACT =>
        source == "PACT" || source == "RGRIP" || source == "LGM"

      case IDLE => false
    }
  }
// dynamic sequence execution variables
  def exchangeSequence: List[Step] =
  List(RGRIP, LGRIP, LGM, PACT, LGM, PACT, LGRIP, RGRIP)

  def returnSequence: List[Step] =
    List(RGRIP, LGRIP, LGM, PACT)

  def pickupSequence: List[Step] =
    List(LGM, PACT, RGRIP)

  def homeSequence: List[Step] =
    List(RGRIP, LGRIP, LGM, PACT)

  def parkSequence: List[Step] =
    List(RGRIP, LGRIP, LGM, PACT)


//  sealed trait Operation
//
//  case object EXCHANGE extends Operation
//
//  case object RETURN extends Operation
//
//  case object PICKUP extends Operation
//
//  case object HOME extends Operation
//
//  case object PARK extends Operation
//  
//  case object NONE extends Operation

  //var currentOperation: Operation = EXCHANGE

  // variables for dynamic sending command 
  var sequence: List[Step] = Nil
  var index: Int = 0

  var currentRunId: Option[Id] = None
  var currentStep: Step = IDLE
  
  def executeCurrentStep(): Unit = {

    if (index >= sequence.length) {
      log.info("✅ Sequence completed")
      currentStep = IDLE
      return
    }

    currentStep = sequence(index)

    log.info(s"Step: $currentStep | Operation: ${assemblyStateVariable.currentOperation} | Index: $index")
    
    assemblyStateVariable.currentOperation  match {

      // ================= EXCHANGE =================
      case EXCHANGE =>
        currentStep match {

          case RGRIP =>
            if (index == 0)
              sendCommandToRgrip(currentRunId.get, "move", Some(35))
            else
              sendCommandToRgrip(
                currentRunId.get,
                "move",
                getAngleFromGrating(rgripState.gratingId.get)
              )

          case LGRIP =>
            if (index == 1)
              sendCommandToLgrip(currentRunId.get, "move", 1000)
            else
              sendCommandToLgrip(currentRunId.get, "move", 0)

          case LGM =>
            if (index == 2)
              sendCommandToLgm(
                currentRunId.get,
                "move",
                lgmState.emptySlotLinearDistance.get
              )
            else
              sendCommandToLgm(
                currentRunId.get,
                "move",
                getTargetDistanceFromGrating(rgripState.gratingId.get)
              )

          case PACT =>
            if (index == 3)
              sendCommandToPact(currentRunId.get, "move", "PULL")
            else
              sendCommandToPact(currentRunId.get, "move", "PUSH")

          case _ =>
        }

      // ================= RETURN =================
      case RETURN =>
        currentStep match {
          case RGRIP =>
            sendCommandToRgrip(currentRunId.get, "move", 35)

          case LGRIP =>
            sendCommandToLgrip(currentRunId.get, "move", 1000)

          case LGM =>
            sendCommandToLgm(
              currentRunId.get,
              "move",
              lgmState.emptySlotLinearDistance.get
            )

          case PACT =>
            sendCommandToPact(currentRunId.get, "move", "PULL")

          case _ =>
        }

      // ================= PICKUP =================
      case PICKUP =>
        currentStep match {

          case LGM =>
            sendCommandToLgm(
              currentRunId.get,
              "move",
              getTargetDistanceFromGrating(rgripState.gratingId.get)
            )

          case PACT =>
            sendCommandToPact(currentRunId.get, "move", "PUSH")

          case RGRIP =>
            sendCommandToRgrip(
              currentRunId.get,
              "move",
              getAngleFromGrating(rgripState.gratingId.get)
            )

          case _ =>
        }

      // ================= HOME =================
      case HOME =>
        currentStep match {
          case RGRIP =>
            sendCommandToRgrip(currentRunId.get, "goHome", 0)

          case LGRIP =>
            sendCommandToLgrip(currentRunId.get, "goHome", 0)

          case LGM =>
            sendCommandToLgm(currentRunId.get, "goHome", 0.0)

          case PACT =>
            sendCommandToPact(currentRunId.get, "goHome", "NONE")

          case _ =>
        }

      // ================= PARK =================
      case PARK =>
        currentStep match {
          case RGRIP =>
            sendCommandToRgrip(currentRunId.get, "goPark", 0)

          case LGRIP =>
            sendCommandToLgrip(currentRunId.get, "goPark", 0)

          case LGM =>
            sendCommandToLgm(currentRunId.get, "goPark", 0.0)

          case PACT =>
            sendCommandToPact(currentRunId.get, "goPark", "NONE")

          case _ =>
        }
    }
  }

  def goToNextStep(): Unit = {
    index += 1
    executeCurrentStep()
  }
  
  

  private def sendCommandToRgrip(runId:Id,commandName:String,targetposition:Option[Int]: Unit={
    val command:Setup
    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetAngle, gratingModeParam, cw)
    
    
  }
  
  override def initialize(): Unit = {
    log.info("Initializing BgrxAssembly")
    // <<<<<<< HEAD
    // // log.info(s"Printing lgminfo var ${LgmInfo.exchangeAngle}")
    // =======

    // >>>>>>> upstream / phase3
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = { // no need to create CS here
    log.info("Bgrx Assembly : Locations of components in the assembly are updated")
    log.info(s"Bgrx Assembly : onLocationTrackingEvent is called")
    trackingEvent match {
      case LocationUpdated(location) => {

        location.connection match {
          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.rgriphcd"), _)) => {
            log.info("Bgrx Assembly : Creating command service to RgripHcd")
            rgripHcdCS = Some(CommandServiceFactory.make(location))
          }
          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgriphcd"), _)) => {
            log.info("Bgrx Assembly : Creating command service to LgripHcd")
            lgripHcdCS = Some(CommandServiceFactory.make(location))
          }

          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgmhcd"), _)) => {
            log.info("Bgrx Assembly : Creating command service to Lgmhcd")
            lgmHcdCS = Some(CommandServiceFactory.make(location))
          }

          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.pacthcd"), _)) => {
            log.info("Bgrx Assembly : Creating command service to Pacthcd")
            pactHcdCS = Some(CommandServiceFactory.make(location))
          }

          case _ => log.info("Unknown HCD encountered")
        }
      }
      case LocationRemoved(connection) => log.info("Location Removed")
    }
    if (rgripHcdCS != None && lgripHcdCS != None && lgmHcdCS != None && pactHcdCS != None) {
      log.info("Bgrx Assembly : All HCDs are successfully initialized")
      sendCommand(Id("bgrx"))
    }
  }

//  private val gratingModeParam: Parameter[String] =
//    RgripInfo.gratingModeKey.set("bgid3")

  private def sendCommand(runId: Id): SubmitResponse = {
//    val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
//    val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid3")
//    val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)
//
//    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetAngle, gratingModeParam, cw)

    // new code 

    currentOperation = EXCHANGE
    // Store operation in state variable
    assemblyStateVariable = assemblyStateVariable.copy(
      currentOperation = currentOperation,
      currentGratingMode = RgripInfo.gratingModeKey.set("bgid3")
    )
    val command: Setup = assemblyStateVariable.currentOperation match{

      // ================= EXCHANGE =================
      case EXCHANGE =>
        assemblyStateVariable=assemblyStateVariable.copy(
          currentGratingMode = RgripInfo.gratingModeKey.set("bgid3")
        )
        val targetAngle = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
        val gratingMode = assemblyStateVariable.currentGratingMode
        val cw = RgripInfo.cwKey.set(6000)

        Setup(sourcePrefix, CommandName("move"), Some(obsId))
          .madd(targetAngle, gratingMode, cw)

      // ================= RETURN =================
      case RETURN =>
        assemblyStateVariable=assemblyStateVariable.copy(
          currentGratingMode = RgripInfo.gratingModeKey.set("bgid2")
        )
        val targetAngle = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
        val gratingMode = assemblyStateVariable.currentGratingMode
        val cw = RgripInfo.cwKey.set(6000)

        Setup(sourcePrefix, CommandName("move"), Some(obsId))
          .madd(targetAngle, gratingMode, cw)

      // ================= PICKUP =================
      case PICKUP =>
        assemblyStateVariable=assemblyStateVariable.copy(
          currentGratingMode = RgripInfo.gratingModeKey.set("bgid3")
        )
        val targetAngle = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
        val gratingMode = ssemblyStateVariable.currentGratingMode,
        val cw = RgripInfo.cwKey.set(6000)

        Setup(sourcePrefix, CommandName("move"), Some(obsId))
          .madd(targetAngle,gratingMode, cw)

      // ================= HOME =================
      case HOME =>
        val targetAngle = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
        val gratingMode = RgripInfo.gratingModeKey.set("bgid2")
        val cw = RgripInfo.cwKey.set(6000)
        Setup(sourcePrefix, CommandName("goHome"), Some(obsId))
          .madd(targetAngle,gratingMode,cw)

      // ================= PARK =================
      case PARK =>
        val targetAngle = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
        val gratingMode = RgripInfo.gratingModeKey.set("bgid2")
        val cw = RgripInfo.cwKey.set(6000)
        Setup(sourcePrefix, CommandName("goPark"), Some(obsId))
        .madd(targetAngle,gratingMode,cw)
    }

    

    val validateResponse = validateCommand(runId, command)

    validateResponse match {
      case Accepted(_) =>
        onSubmit(runId, command)

      case Invalid(_, error) =>
        Invalid(runId, UnsupportedCommandIssue(error.reason))
    }
    
    
  }

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"Bgrx Assembly : Command - $runId is being validated")

    controlCommand match {
      case setup: Setup =>
        log.info("Bgrx Assembly : Command type validation is Successful")
        log.info("Bgrx Assembly : Validating command name")

        setup.commandName match {
          case CommandName("move") => {

            log.info("Bgrx Assembly : Command name validation is successful")
            log.info("Bgrx Assembly : Validating Parameters")

            val validateParamasRes = rgripHcd.validateParameters(setup)
            validateParamasRes match {

              case Right(_) => {
                log.info("Bgrx Assembly : Parameters' validation is Successful")
                Accepted(runId)
              }
              case Left(error) => {
                log.error(s"Bgrx Assembly: Parameter validation Failure. ${error}")
                // Invalid(runId, WrongCommandTypeIssue("Wrong parameters in the command"))
                Invalid(runId, error)
              }
            }
          }
          case _ => {
            log.error(s"Bgrx Assembly : Command name validation Failure. $sourcePrefix takes only 'move' Setup as commands")
            Invalid(runId, UnsupportedCommandIssue(s"$sourcePrefix takes only 'move' Setup as commands"))
          }
        }
      case _: Observe =>
        log.error(s"Bgrx Assembly : Validation is Failure. $sourcePrefix only accepts Setup Commands")
        Invalid(runId, WrongCommandTypeIssue("Observe commands are not supported"))
      case _ =>
        Invalid(runId, UnsupportedCommandIssue("Invalid command type"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"Bgrx Assembly : handling command: $runId")

    controlCommand match {
      case setup: Setup => onSetup(runId, setup)
      case _: Observe   => Invalid(runId, WrongCommandTypeIssue("This assembly can't handle observe commands"))
    }
  }

  val locationService             = HttpLocationServiceFactory.makeLocalClient
  val adminAPI2                   = new AlarmServiceFactory().makeAdminApi(locationService)
  val adminAPI: AlarmAdminService = adminAPI2

  val resource               = "wfos-bgrxassembly/src/main/resources/valid-alarms.conf"
  val alarmsConfig: Config   = ConfigFactory.parseResources(resource)
  val result2F: Future[Done] = adminAPI.initAlarms(alarmsConfig)

  val gripperMovementEventKey             = EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("LgripMovementEvent"))
  val rgripRotationEventKey               = EventKey(Prefix("wfos.bgrxAssembly.rgriphcd"), EventName("RgripRotationEvent"))
  val rgripStatusEventKey                 = EventKey(Prefix("wfos.bgrxAssembly.rgriphcd"), EventName("RgripStatusEvent"))
  val lgripStatusEventKey                 = EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("LgripStatusEvent"))
  val lgripMoveToExchangePositionEventKey = EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("lgripMoveToExchangePositionEvent"))
  val lgripMoveToHomePositionEventKey     = EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("lgripMoveToHomePositionEvent"))
  val lgmMovementEventKey                 = EventKey(Prefix("wfos.bgrxAssembly.lgmhcd"), EventName("LgmMovementEvent"))
  val lgmStatusKey                        = EventKey(Prefix("wfos.bgrxAssembly.lgmhcd"), EventName("LgmHcd_status"))
  val lgmStatusEventKey                   = EventKey(Prefix("wfos.bgrxAssembly.lgmhcd"), EventName("LgmStatusEvent"))
  val pactMovementEventKey                = EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("PactMovementEvent"))
  val pactStatusEventKey                  = EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("PactStatusEventone"))
  val pactPushPullEventKey                = EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("pactPushPullEventone"))

  private def handlePactEvent(event: Event): Unit = {
    val operation = event(PactInfo.operationKey).head // PUSH / PULL
    operation match {
      case "PUSH" =>
        log.info("==============================================Assembly → PUSH detected")
        // update LGM → slot empty and send command to rgrip and lgmhcd
        sendPushPullStatusToHcds("PUSH")
      // update RGRIP → has grating

      case "PULL" =>
        log.info("======================================================Assembly → PULL detected")
        // update LGM → slot filled and send command to lgmhcd and rgrip
        sendPushPullStatusToHcds("PULL")
      // update RGRIP → empty
    }
  }
  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    currentRunId = Some(runId)
    //currentOperation = assemblyStateVariable.currentOperation // or RETURN / PICKUP / HOME / PARK

    sequence = assemblyStateVariable.currentOperation match {
      case EXCHANGE => exchangeSequence
      case RETURN => returnSequence
      case PICKUP => pickupSequence
      case HOME => homeSequence
      case PARK => parkSequence
    }

    index = 0
    executeCurrentStep()

    val subscriber = eventService.defaultSubscriber

    subscriber.subscribeCallback(
      Set(rgripRotationEventKey),
      event => {
        if (isEventAllowed(currentStep, "RGRIP")) {
          val angle = event(RgripInfo.currentAngleKey).head

          rgripState = rgripState.copy(currentAngle = angle)

          log.info(s"Assembly updated RGRIP angle: ${rgripState.currentAngle}")
        }
      }
    )
    // rgrip status event
    subscriber.subscribeCallback(
      Set(rgripStatusEventKey),
      event => {
        if (isEventAllowed(currentStep, "RGRIP")) {
          val angle      = event(RgripInfo.currentAngleKey).head
          val hasGrating = event(RgripInfo.rgripHasGratingKey).head

          val gratingId =
            if (hasGrating) Some(event(RgripInfo.rgripGratingIdKey).head)
            else None

          rgripState = rgripState.copy(
            currentAngle = angle,
            hasGrating = hasGrating,
            gratingId = gratingId
          )

          log.info(s"Assembly updated RGRIP state: $rgripState")
        }
      }
    )
    // lgrip event
    subscriber.subscribeCallback(
      Set(lgripStatusEventKey),
      event => {
        if (isEventAllowed(currentStep, "LGRIP")) {

          val position = event(LgripInfo.currentPositionKey).head

          lgripState = lgripState.copy(
            currentPosition = position
          )

          log.info(s"Assembly updated LGRIP state :- $lgripState")
        }
      }
    )

    subscriber.subscribeCallback(
      Set(lgripMoveToExchangePositionEventKey),
      event => {
        if (isEventAllowed(currentStep, "LGRIP")) {
          val position = event(LgripInfo.currentPositionKey).head
          lgripState = lgripState.copy(
            currentPosition = position
          )
          log.info(s"Event  lgripMoveToExchangePositionEvent  l grip moved to : ${lgripState.currentPosition}")
        }
      }
    )

    // lgrip move to home position event
    subscriber.subscribeCallback(
      Set(lgripMoveToHomePositionEventKey),
      event => {
        if (isEventAllowed(currentStep, "LGRIP")) {
          val position = event(LgripInfo.currentPositionKey).head
          lgripState = lgripState.copy(
            currentPosition = position
          )
          log.info(s"Event  lgripMoveToHomePositionEvent ---   lgrip moved to : ${lgripState.currentPosition}")
        }
      }
    )

    subscriber.subscribeCallback(
      Set(lgmMovementEventKey),
      event => {
        if (isEventAllowed(currentStep, "LGM")) {
          val curpos = event(LgmInfo.messageKey).head
          log.info(s"Received LgmMovement Event: Lgmhcd: Moving grating magazine to$curpos")
          // Handle the RgripRotationEvent here
        }
      }
    )

    subscriber.subscribeCallback(
      Set(lgmStatusEventKey),
      event => {
        if (isEventAllowed(currentStep, "LGM")) {

          val isEmpty = event(LgmInfo.emptySlotKey).head

          lgmState = if (isEmpty) {
            lgmState.copy(
              currentPosition = event(LgmInfo.currentPositionKey).head,
              emptySlot = true,
              emptySlotBgid = Some(event(LgmInfo.emptySlotBgidKey).head),
              emptySlotLinearDistance = Some(event(LgmInfo.emptySlotLinearDistanceKey).head),
              emptySlotIndex = Some(event(LgmInfo.emptySlotIndexKey).head)
            )
          }
          else {
            lgmState.copy(
              currentPosition = event(LgmInfo.currentPositionKey).head,
              emptySlot = false,
              emptySlotBgid = None,
              emptySlotLinearDistance = None,
              emptySlotIndex = None
            )
          }

          log.info(s"Assembly updated LGM state → $lgmState")
        }
      }
    )

    subscriber.subscribeCallback(
      Set(lgmStatusKey),
      event => {
        if (isEventAllowed(currentStep, "LGM")) {
          lgmState = lgmState.copy(
            currentPosition = event(LgmInfo.currentPositionKey).head
            // below are not required we handled it above
            //          emptySlot = event(LgmInfo.emptySlotKey).head,
            //          emptySlotBgid = event(LgmInfo.emptySlotBgidKey).head,
            //          emptySlotLinearDistance = event(LgmInfo.emptySlotLinearDistanceKey).head,
            //          emptySlotIndex = event(LgmInfo.emptySlotIndexKey).head
          )

          log.info(s"Assembly updated LGM state → $lgmState")
        }
      }
    )

    subscriber.subscribeCallback(
      Set(pactStatusEventKey),
      event => {
        if (isEventAllowed(currentStep, "PACT")) {
          val stage  = event(PactInfo.stageKey).head
          val status = event(PactInfo.statusKey).head
          pactState = pactState.copy(event(PactInfo.currentPositionKey).head)
          log.info(s"pactHCD  ststus : currentPosition : ${pactState.currentPosition} stage : $stage status : $status ")
        }
      }
    )

    subscriber.subscribeCallback(
      Set(pactMovementEventKey),
      event => {
        if (isEventAllowed(currentStep, "PACT")) {
          val pactCurpos = event(PactInfo.messageKey).head
          // log.info(s"Received PactMovement Event: Pacthcd: Moving rod to${PactInfo.out.head}")
          // Handle the RgripRotationEvent here
          // log.info(s"Recieved PactMovement Event - First log");
          log.info(s"Received PactMovement Event: Pacthcd: Moving rod to $pactCurpos")
        }
      }
    )

    subscriber.subscribeCallback(
      Set(pactPushPullEventKey),
      event => {
        if (isEventAllowed(currentStep, "PACT")) {
          log.info("pact sent push pull event ")
          handlePactEvent(event)

        }
      }
    )
    moveRgripHcd(runId, setup)
    // moveLgmHcd(runId)
    Started(runId)
  }

  private def moveRgripHcd(runId: Id, setup: Setup): Unit = {
    currentStep1 = RGRIP
    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.rgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    rgripHcdCS = Some(CommandServiceFactory.make(pekkoLocation))

    rgripHcdCS match {
      case Some(cs) => {
        val response: Future[SubmitResponse] = cs.submit(setup)
        response.foreach {
          case completed: Completed => {
            // log.info(s"Bgrx Assembly: Command with runId - $runId is executed successfully")
            log.info(s"Bgrx Assembly: RgripHcd has moved to exchange position successfully")
            moveLgripHcd(runId, setup)
            goToNextStep()
            // commandResponseManager.updateCommand(completed.withRunId(runId))
          }
          case error: Invalid => {
            log.error(s"Bgrx Assembly: Execution of command with runId- $runId has failed")
            log.error(s"Bgrx Assembly: ${error.issue}")
            commandResponseManager.updateCommand(error.withRunId(runId))
          }

          case other => commandResponseManager.updateCommand(other.withRunId(runId))
        }
      }
      case None => {
        log.error("Bgrx Assembly : Rgrip Hcd is not available. Failed to create an instance of command service to Rgrip Hcd")
        commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Rgrip Hcd is not available")))
      }
    }
  }

  private def moveLgripHcd(runId: Id, setup: Setup): Unit = {
    currentStep1 = LGRIP
    val targetPosition = LgripInfo.targetPositionKey.set(100)
    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetPosition)

    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.lgriphcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    lgripHcdCS = Some(CommandServiceFactory.make(pekkoLocation))

    lgripHcdCS match {
      case Some(cs) => {
        val response: Future[SubmitResponse] = cs.submit(command)
        response.foreach {
          case completed: Completed => {
            log.info(s"Bgrx Assembly: LgripHcd has moved to exchange position successfully")
            log.info(s"Bgrx Assembly: Execution of command with runId - $runId is completed successfully")
            // commandResponseManager.updateCommand(completed.withRunId(runId))

            moveLgmHcd(runId, setup)
          }

          case error: Invalid => {
            log.error(s"Bgrx Assembly: Execution of command with runId- $runId has failed")
            log.error(s"Bgrx Assembly: ${error.issue}")
            commandResponseManager.updateCommand(error.withRunId(runId))
          }
          case other => commandResponseManager.updateCommand(other.withRunId(runId))
        }
      }
      case None => {
        log.error("Bgrx Assembly : Rgrip Hcd is not available. Failed to create an instance of command service to Rgrip Hcd")
        commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Rgrip Hcd is not available")))
      }
    }
  }

  private def moveLgmHcd(runId: Id, setup: Setup): Unit = {

    currentStep1 = LGM
//    val bgid: String = setup(RgripInfo.gratingModeKey).head
    val (bgid, linearDistance): (String, Double) =
      if (lgmState.emptySlot) {
        val bgid =
          lgmState.emptySlotBgid.getOrElse(
            throw new RuntimeException("bgid missing")
          )
        val distance =
          lgmState.emptySlotLinearDistance.getOrElse(
            throw new RuntimeException("linear distance missing")
          )
        (bgid, distance)
      }
      else {
        throw new RuntimeException(
          s"Slot is not empty : emptyslot bgid :  ${lgmState.emptySlotBgid} , empty slot linear distance :  ${lgmState.emptySlotLinearDistance} "
        )
      }
    val isValid =
      rgripState.hasGrating &&
        rgripState.gratingId.contains(bgid)
    // fetching linear distance from map
    if (isValid) {
      log.info(" rgrip grating and lgmHcd empty slot index is matching *********************")
      currentStep = LGM
      val targetGratingPosition = LgmInfo.targetGratingPositionKey.set(linearDistance)

      // commented below line, now we are fetching the linear disrtnce from map
      // val targetGratingPosition = LgmInfo.targetGratingPositionKey.set(LgmInfo.gratingLinearDistance(2))

      val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetGratingPosition)

      val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.lgmhcd"), ComponentType.HCD))
      val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

      lgmHcdCS = Some(CommandServiceFactory.make(pekkoLocation))

      lgmHcdCS match {
        case Some(cs) => {
          val response: Future[SubmitResponse] = cs.submit(command)
          response.foreach {
            case completed: Completed => {
              log.info(
                s"Bgrx Assembly: LgmHcd has moved to exchange position successfully and bgid is $bgid  and linear distance is : $linearDistance"
              )
              log.info(s"Bgrx Assembly: Execution of command with runId - $runId is completed successfully")
              commandResponseManager.updateCommand(completed.withRunId(runId))
              movePactHcd(runId);
            }

            case error: Invalid => {
              log.error(s"Bgrx Assembly: Execution of command with runId- $runId has failed")
              log.error(s"Bgrx Assembly: ${error.issue}")
              commandResponseManager.updateCommand(error.withRunId(runId))
            }
            // pacthcd (SupervisorBehavior.scala 352) - Starting InitializeTimer for 10 second
            case other => commandResponseManager.updateCommand(other.withRunId(runId))
          }
        }
        case None => {
          log.error("Bgrx Assembly : Lgm Hcd is not available. Failed to create an instance of command service to Lgm Hcd")
          commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Lgm Hcd is not available")))
        }
      }
    }
    else {
      log.error("Rgrip gratingId  and lgm empty slot Bgid is not matching ")
      commandResponseManager.updateCommand(
        Invalid(runId, UnsupportedCommandIssue("Grating mismatch"))
      )
    }

  }

  private def movePactHcd(runId: Id): Unit = {
    currentStep1 = PACT
    val targetPosition = PactInfo.targetPositionKey.set(500.0)
    val operation      = PactInfo.operationKey.set("PULL")
    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetPosition, operation)

    val connection    = PekkoConnection(ComponentId(Prefix("wfos.bgrxAssembly.pacthcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pactHcdCS = Some(CommandServiceFactory.make(pekkoLocation))

    pactHcdCS match {
      case Some(cs) =>
        val response: Future[SubmitResponse] = cs.submit(command)
        response.foreach {
          case completed: Completed =>
            log.info(s"Bgrx Assembly: PactHcd has moved to target position successfully")
            log.info(s"Bgrx Assembly: Execution of command with runId - $runId is completed successfully")
            commandResponseManager.updateCommand(completed.withRunId(runId))

          case error: Invalid =>
            log.error(s"Bgrx Assembly: Execution of command with runId- $runId has failed")
            log.error(s"Bgrx Assembly: ${error.issue}")
            commandResponseManager.updateCommand(error.withRunId(runId))

          case other =>
            commandResponseManager.updateCommand(other.withRunId(runId))
        }

      case None =>
        log.error("Bgrx Assembly : Pact Hcd is not available. Failed to create an instance of command service to Pact Hcd")
        commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Pact Hcd is not available")))
    }
  }
  private def sendPushPullStatusToHcds(operation: String): Unit = {
    // val param = LgmInfo.operationKey.set(operation)

    // 🔹 RGRIP command

    val gratingMode = assemblyStateVariable.currentGratingMode
    val rgripCommand =
      Setup(sourcePrefix, CommandName("update"), Some(obsId))
        .madd(RgripInfo.operationKey.set(operation), gratingMode)

    // 🔹 LGM command
    val gratingMode = assemblyStateVariable.currentGratingMode
    val lgmCommand =
      Setup(sourcePrefix, CommandName("update"), Some(obsId))
        .madd(LgmInfo.operationKey.set(operation))

//    val command: Setup =
//      Setup(sourcePrefix, CommandName("update"), Some(obsId))
//        .madd(param)

    // 🔹 Send to RGRIP
    rgripHcdCS match {
      case Some(cs) =>
        cs.submit(rgripCommand).foreach {
          case _: Completed =>
            log.info(s" ===================Assembly Sent pushpull operation status  $operation to RgripHcd")
          case err: Invalid =>
            log.error(s"======================RgripHcd failed: ${err.issue}")
          case _ =>
        }
      case None =>
        log.error("RgripHcd not available")
    }

    // 🔹 Send to LGM
    lgmHcdCS match {
      case Some(cs) =>
        cs.submit(lgmCommand).foreach {
          case _: Completed =>
            log.info(s"===============Assembly Sent pushpull operation status command to operation   $operation to LgmHcd")
          case err: Invalid =>
            log.error(s"LgmHcd failed: ${err.issue}")
          case _ =>
        }
      case None =>
        log.error("LgmHcd not available")
    }
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {}

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}
