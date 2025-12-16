package wfos.bgrxassembly

import org.apache.pekko
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.ActorContext
//import com.typesafe.config.ConfigFactory
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
//import csw.location.client.scaladsl.HttpLocationServiceFactory
//import csw.alarm.client.AlarmServiceFactory
//import csw.alarm.api.scaladsl.{AlarmAdminService, AlarmService}
import csw.params.commands.CommandResponse._
import csw.params.commands.{ControlCommand, CommandName, Setup, Observe}
import csw.params.commands.CommandIssue.{UnsupportedCommandIssue, RequiredHCDUnavailableIssue, WrongCommandTypeIssue}
import csw.time.core.models.UTCTime
import csw.params.core.models.{Id, ObsId}
import csw.location.api.models.{ComponentId, LocationRemoved, LocationUpdated, TrackingEvent}
import csw.location.api.models.Connection.PekkoConnection
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.prefix.models.{Prefix, Subsystem}
import csw.params.core.generics.Parameter

import scala.concurrent.{ExecutionContextExecutor}
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._

import wfos.lgriphcd.LgripInfo
import wfos.rgriphcd.RgripInfo
import wfos.lgmhcd.LgmInfo
import wfos.pacthcd.PactInfo
import wfos.bgrxassembly.components.{RgripHcd}

class BgrxassemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor         = ctx.executionContext
  private val log                                   = loggerFactory.getLogger
  private implicit val system: ActorSystem[Nothing] = ctx.system

  private var rgripHcdCS: Option[CommandService] = None
  private var lgripHcdCS: Option[CommandService] = None
  private var lgmHcdCS: Option[CommandService]   = None
  private var pactHcdCS: Option[CommandService]  = None

  private val rgripHcd: RgripHcd   = new RgripHcd()
  private val sourcePrefix: Prefix = Prefix("wfos.bgrxassembly")
  private val obsId: ObsId         = ObsId("2023A-001-123")
  implicit val timeout: Timeout    = Timeout(5.seconds)

  override def initialize(): Unit = {
    log.info("Initializing BgrxAssembly")
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {
    log.info("Bgrx Assembly : Locations of components in the assembly are updated")
    log.info("Bgrx Assembly : onLocationTrackingEvent is called")

    trackingEvent match {
      case LocationUpdated(location) =>
        location.connection match {
          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.rgriphcd"), _)) =>
            log.info("Bgrx Assembly : Creating command service to RgripHcd")
            rgripHcdCS = Some(CommandServiceFactory.make(location))

          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgriphcd"), _)) =>
            log.info("Bgrx Assembly : Creating command service to LgripHcd")
            lgripHcdCS = Some(CommandServiceFactory.make(location))

          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.lgmhcd"), _)) =>
            log.info("Bgrx Assembly : Creating command service to Lgmhcd")
            lgmHcdCS = Some(CommandServiceFactory.make(location))

          case PekkoConnection(ComponentId(Prefix(Subsystem.WFOS, "bgrxAssembly.pacthcd"), _)) =>
            log.info("Bgrx Assembly : Creating command service to Pacthcd")
            pactHcdCS = Some(CommandServiceFactory.make(location))

          case _ =>
            log.info("Unknown HCD encountered")
        }

      case LocationRemoved(connection) =>
        log.info("Location Removed")
    }

    if (rgripHcdCS.nonEmpty && lgripHcdCS.nonEmpty && lgmHcdCS.nonEmpty && pactHcdCS.nonEmpty) {
      log.info("Bgrx Assembly : All HCDs are successfully initialized")
      sendCommand(Id("bgrx")): Unit
    }
  }

  private def sendCommand(runId: Id): SubmitResponse = {
    val targetAngle: Parameter[Int]    = RgripInfo.targetAngleKey.set(RgripInfo.exchangeAngle.head)
    val gratingMode: Parameter[String] = RgripInfo.gratingModeKey.set("bgid3")
    val cw: Parameter[Int]             = RgripInfo.cwKey.set(6000)

    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetAngle, gratingMode, cw)

    validateCommand(runId, command) match {
      case Accepted(runId)       => onSubmit(runId, command)
      case Invalid(runId, error) => Invalid(runId, UnsupportedCommandIssue(error.reason))
    }
  }

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"Bgrx Assembly : Command - $runId is being validated")

    controlCommand match {
      case setup: Setup =>
        log.info("Bgrx Assembly : Command type validation is Successful")
        setup.commandName match {
          case CommandName("move") =>
            val validateParamsRes = rgripHcd.validateParameters(setup)
            validateParamsRes match {
              case Right(_)    => Accepted(runId)
              case Left(error) => Invalid(runId, error)
            }
          case _ => Invalid(runId, UnsupportedCommandIssue(s"$sourcePrefix takes only 'move' Setup as commands"))
        }

      case _: Observe =>
        Invalid(runId, WrongCommandTypeIssue("Observe commands are not supported"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    controlCommand match {
      case setup: Setup => onSetup(runId, setup)
      case _: Observe   => Invalid(runId, WrongCommandTypeIssue("This assembly can't handle observe commands"))
      case other        => Invalid(runId, UnsupportedCommandIssue("Unsupported command"))
    }
  }

  private def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    moveRgripHcd(runId, setup)
    Started(runId)
  }

  private def moveRgripHcd(runId: Id, setup: Setup): Unit = {
    rgripHcdCS match {
      case Some(cs) =>
        cs.submit(setup).foreach {
          case _: Completed =>
            log.info(s"Bgrx Assembly: RgripHcd moved successfully")
            moveLgripHcd(runId)
          case error: Invalid =>
            log.error(s"Bgrx Assembly: Execution failed: ${error.issue}")
            commandResponseManager.updateCommand(error.withRunId(runId))
          case other =>
            commandResponseManager.updateCommand(other.withRunId(runId))
        }
      case None =>
        commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Rgrip Hcd is not available")))
    }
  }

  private def moveLgripHcd(runId: Id): Unit = {
    val targetPosition = LgripInfo.targetPositionKey.set(100)
    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetPosition)

    lgripHcdCS match {
      case Some(cs) =>
        cs.submit(command).foreach {
          case _: Completed =>
            log.info(s"LgripHcd moved successfully")
            moveLgmHcd(runId)
          case error: Invalid =>
            log.error(s"Execution failed: ${error.issue}")
            commandResponseManager.updateCommand(error.withRunId(runId))
          case other =>
            commandResponseManager.updateCommand(other.withRunId(runId))
        }
      case None =>
        commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Lgrip Hcd is not available")))
    }
  }

  private def moveLgmHcd(runId: Id): Unit = {
    val targetGratingPosition = LgmInfo.targetGratingPositionKey.set(LgmInfo.gratingLinearDistance(2))
    val command: Setup        = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetGratingPosition)

    lgmHcdCS match {
      case Some(cs) =>
        cs.submit(command).foreach {
          case _: Completed =>
            log.info(s"LgmHcd moved successfully")
            movePactHcd(runId)
          case error: Invalid =>
            log.error(s"Execution failed: ${error.issue}")
            commandResponseManager.updateCommand(error.withRunId(runId))
          case other =>
            commandResponseManager.updateCommand(other.withRunId(runId))
        }
      case None =>
        commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Lgm Hcd is not available")))
    }
  }

  private def movePactHcd(runId: Id): Unit = {
    val targetPosition = PactInfo.targetPositionKey.set(500.0)
    val command: Setup = Setup(sourcePrefix, CommandName("move"), Some(obsId)).madd(targetPosition)

    pactHcdCS match {
      case Some(cs) =>
        cs.submit(command).foreach {
          case _: Completed =>
            log.info(s"PactHcd moved successfully")
          case error: Invalid =>
            log.error(s"Execution failed: ${error.issue}")
            commandResponseManager.updateCommand(error.withRunId(runId))
          case other =>
            commandResponseManager.updateCommand(other.withRunId(runId))
        }
      case None =>
        commandResponseManager.updateCommand(Invalid(runId, RequiredHCDUnavailableIssue("Pact Hcd is not available")))
    }
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}
  override def onShutdown(): Unit                                        = {}
  override def onGoOffline(): Unit                                       = {}
  override def onGoOnline(): Unit                                        = {}
  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit  = {}
  override def onOperationsMode(): Unit                                  = {}
}
