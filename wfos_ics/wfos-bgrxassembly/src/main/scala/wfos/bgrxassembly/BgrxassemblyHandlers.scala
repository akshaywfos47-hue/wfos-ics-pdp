package wfos.bgrxassembly

import akka.Done
import akka.actor.typed.ActorSystem
import com.typesafe.config._
import akka.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.alarm.client.AlarmServiceFactory
import csw.alarm.api.scaladsl.{AlarmAdminService, AlarmService, AlarmSubscription}
import csw.params.commands.CommandResponse._
import csw.params.commands.{ControlCommand, CommandName, Setup, Observe}
import csw.params.commands.CommandIssue.{UnsupportedCommandIssue, RequiredHCDUnavailableIssue, WrongCommandTypeIssue}
import csw.time.core.models.UTCTime
import csw.params.core.models.{Id, ObsId}

import csw.location.api.models.{ComponentId, ComponentType, LocationRemoved, LocationUpdated, TrackingEvent}
import csw.location.api.models.Connection.AkkaConnection
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.prefix.models.{Prefix, Subsystem}
import csw.params.core.generics.Parameter
// import csw.params.core.generics.{Key,KeyType, Parameter}

import scala.concurrent.{ExecutionContextExecutor, Future}
// import scala.util.{Success, Failure}
import wfos.lgriphcd.LgripInfo
import wfos.rgriphcd.RgripInfo
import wfos.lgmhcd.LgmInfo
import wfos.pacthcd.PactInfo
import wfos.bgrxassembly.components.{RgripHcd, LgripHcd, Lgmhcd} //Make sure to import Pacthcd..
import csw.params.events.{EventKey, EventName}

import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await

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

  private val rgripHcd: RgripHcd = new RgripHcd()
  // private val lgmHcd: LgmHcd     = new LgmHcd()

  implicit val timeout: Timeout = Timeout(5.seconds)

  // Prefix of assembly
  private val sourcePrefix: Prefix = Prefix("wfos.bgrxassembly")
  private val obsId: ObsId         = ObsId("2023A-001-123")

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
          case AkkaConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.rgriphcd"), _)) => {
            log.info("Bgrx Assembly : Creating command service to RgripHcd")
            rgripHcdCS = Some(CommandServiceFactory.make(location))
          }
          case AkkaConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgriphcd"), _)) => {
            log.info("Bgrx Assembly : Creating command service to LgripHcd")
            lgripHcdCS = Some(CommandServiceFactory.make(location))
          }

          case AkkaConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgmhcd"), _)) => {
            log.info("Bgrx Assembly : Creating command service to Lgmhcd")
            lgmHcdCS = Some(CommandServiceFactory.make(location))
          }

          case AkkaConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.pacthcd"), _)) => {
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

  private def sendCommand(runId: Id): SubmitResponse = {
    val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
    val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid3")
    val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)

    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetAngle, gratingMode, cw)

    val validateResponse = validateCommand(runId, command)
    validateResponse match {
      case Accepted(runId)       => onSubmit(runId, command)
      case Invalid(runId, error) => Invalid(runId, UnsupportedCommandIssue(error.reason))
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
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"Bgrx Assembly : handling command: $runId")

    val locationService             = HttpLocationServiceFactory.makeLocalClient
    val adminAPI2                   = new AlarmServiceFactory().makeAdminApi(locationService)
    val adminAPI: AlarmAdminService = adminAPI2

    val resource               = "wfos-bgrxassembly/src/main/resources/valid-alarms.conf"
    val alarmsConfig: Config   = ConfigFactory.parseResources(resource)
    val result2F: Future[Done] = adminAPI.initAlarms(alarmsConfig)

    val gripperMovementEventKey = EventKey(Prefix("wfos.bgrxAssembly.lgriphcd"), EventName("LgripMovementEvent"))
    val rgripRotationEventKey   = EventKey(Prefix("wfos.bgrxAssembly.rgriphcd"), EventName("RgripRotationEvent"))
    val lgmMovementEventKey     = EventKey(Prefix("wfos.bgrxAssembly.lgmhcd"), EventName("LgmMovementEvent"))
    val pactMovementEventKey    = EventKey(Prefix("wfos.bgrxAssembly.pacthcd"), EventName("PactMovementEvent"))

    val subscriber = eventService.defaultSubscriber
    subscriber.subscribeCallback(
      Set(gripperMovementEventKey),
      event => {
        log.info(s"Received GripperMovementEvent: LgripHcd :Moving gripper to ${LgripInfo.currentPosition.head}")
        // Handle the GripperMovementEvent here
      }
    )
    subscriber.subscribeCallback(
      Set(rgripRotationEventKey),
      event => {
        log.info(s"Received RgripRotationEvent: RgripHcd: Rotating gripper to${RgripInfo.currentAngle.head}")
        // Handle the RgripRotationEvent here
      }
    )

    subscriber.subscribeCallback(
      Set(lgmMovementEventKey),
      event => {
        log.info(s"Received LgmMovement Event: Lgmhcd: Moving grating magazine to${LgmInfo.currentPosition.head}")
        // Handle the RgripRotationEvent here
      }
    )

    subscriber.subscribeCallback(
      Set(pactMovementEventKey),
      event => {
        // log.info(s"Received PactMovement Event: Pacthcd: Moving rod to${PactInfo.out.head}")
        // Handle the RgripRotationEvent here
        // log.info(s"Recieved PactMovement Event - First log");
        log.info(s"Received PactMovement Event: Pacthcd: Moving rod to ${PactInfo.currentPosition.head}")
      }
    )

    controlCommand match {
      case setup: Setup => onSetup(runId, setup)
      case _: Observe   => Invalid(runId, WrongCommandTypeIssue("This assembly can't handle observe commands"))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    moveRgripHcd(runId, setup)
    // moveLgmHcd(runId)
    Started(runId)
  }

  private def moveRgripHcd(runId: Id, setup: Setup): Unit = {
    val connection   = AkkaConnection(ComponentId(Prefix("wfos.bgrxAssembly.rgriphcd"), ComponentType.HCD))
    val akkaLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    rgripHcdCS = Some(CommandServiceFactory.make(akkaLocation))

    rgripHcdCS match {
      case Some(cs) => {
        val response: Future[SubmitResponse] = cs.submit(setup)
        response.foreach {
          case completed: Completed => {
            // log.info(s"Bgrx Assembly: Command with runId - $runId is executed successfully")
            log.info(s"Bgrx Assembly: RgripHcd has moved to exchange position successfully")
            moveLgripHcd(runId)
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

  private def moveLgripHcd(runId: Id): Unit = {
    val targetPosition = LgripInfo.targetPositionKey.set(100)
    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetPosition)

    val connection   = AkkaConnection(ComponentId(Prefix("wfos.bgrxAssembly.lgriphcd"), ComponentType.HCD))
    val akkaLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    lgripHcdCS = Some(CommandServiceFactory.make(akkaLocation))

    lgripHcdCS match {
      case Some(cs) => {
        val response: Future[SubmitResponse] = cs.submit(command)
        response.foreach {
          case completed: Completed => {
            log.info(s"Bgrx Assembly: LgripHcd has moved to exchange position successfully")
            log.info(s"Bgrx Assembly: Execution of command with runId - $runId is completed successfully")
            // commandResponseManager.updateCommand(completed.withRunId(runId))

            moveLgmHcd(runId)
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

  private def moveLgmHcd(runId: Id): Unit = {
    val targetGratingPosition = LgmInfo.targetGratingPositionKey.set(LgmInfo.gratingLinearDistance(2))

    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetGratingPosition)

    val connection   = AkkaConnection(ComponentId(Prefix("wfos.bgrxAssembly.lgmhcd"), ComponentType.HCD))
    val akkaLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    lgmHcdCS = Some(CommandServiceFactory.make(akkaLocation))

    lgmHcdCS match {
      case Some(cs) => {
        val response: Future[SubmitResponse] = cs.submit(command)
        response.foreach {
          case completed: Completed => {
            log.info(s"Bgrx Assembly: LgripHcd has moved to exchange position successfully")
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

  private def movePactHcd(runId: Id): Unit = {
    val targetPosition = PactInfo.targetPositionKey.set(500.0)

    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetPosition)

    val connection   = AkkaConnection(ComponentId(Prefix("wfos.bgrxAssembly.pacthcd"), ComponentType.HCD))
    val akkaLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pactHcdCS = Some(CommandServiceFactory.make(akkaLocation))

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

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {}

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}
