package wfos.bgrxassembly.command

import csw.logging.client.scaladsl.LoggerFactory
import wfos.bgrxassembly.Step
import wfos.bgrxassembly.models.ValidationMessage
import wfos.bgrxassembly.models.ValidationMessage.*
import csw.params.commands.CommandResponse._
import csw.prefix.models.Prefix
import csw.params.core.models.{Id, ObsId}
import csw.params.commands.{CommandName, Setup}
import wfos.lgriphcd.{LgripInfo}
import wfos.rgriphcd.{RgripInfo}
import wfos.lgmhcd.{LgmInfo}
import wfos.pacthcd.{PactInfo}

class BgrxRecovery(
    loggerFactory: LoggerFactory,
    obsId: ObsId,
    resolveAndSubmit: (String, Setup, Id) => PartialFunction[SubmitResponse, Unit] => Unit
) {

  private val log = loggerFactory.getLogger

  def recover(runId: Id, step: Step, failure: ValidationMessage)(
      onResponse: PartialFunction[(Step, SubmitResponse), Unit]
  ): Unit = {
    log.info(s"Starting recovery for step=$step, runId=$runId, reason=$failure")

    failure match {

      // -------------------------
      // RGRIP
      // -------------------------

      case RgripHasNoGrating =>
        recoverRgripHasNoGrating(runId)(onResponse)

      case RgripIsNotAtExchange =>
        recoverRgripToExchange(runId)(onResponse)

      case LgripNotInHome =>
        recoverLgripHome(runId)(onResponse)

      case LgripNotInExchange =>
        recoverLgripExchange(runId)(onResponse)

      case LgmNotInHome =>
        recoverLgmHome(runId)(onResponse)

      case LgmNotAtTarget =>
        recoverLgmTarget(runId)(onResponse)

      case LgmMySlotNotMatchInGratingId =>
        recoverLgmTarget(runId)(onResponse)

      case LgmEmptySlotNotAvailable =>
        recoverLgmEmptySlot(runId)(onResponse)

      case PactNotIn =>
        recoverPactIn(runId)(onResponse)

      case PactNotOut =>
        recoverPactOut(runId)(onResponse)

      case ValidationPassed =>
        log.warn("Recovery called for ValidationPassed. Nothing to recover.")
    }
  }

  // =====================================================
  // Recovery handlers
  // =====================================================

  private def recoverRgripHasNoGrating(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: RGRIP has no grating")

  private def recoverRgripToExchange(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: Move RGRIP to exchange")

  private def recoverLgripHome(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: Move LGRIP to home")

  private def recoverLgripExchange(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: Move LGRIP to exchange")

  private def recoverLgmHome(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: Move LGM to home")

  private def recoverLgmTarget(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: Move LGM to target")

  private def recoverLgmEmptySlot(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: Find empty LGM slot")

  private def recoverPactIn(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit =
    log.info("Recover: Move PACT IN")

  private def recoverPactOut(runId: Id)(onResponse: PartialFunction[(Step, SubmitResponse), Unit]): Unit = {
    log.info("Recover: Move PACT OUT")

    val command =
      Setup(Prefix("wfos.bgrxAssembly"), CommandName("homing"), Some(obsId))

    resolveAndSubmit("pacthcd", command, runId) {
      case completed: Completed if onResponse.isDefinedAt((Step.PACT, completed)) =>
        onResponse((Step.PACT, completed))
    }

  }
}
