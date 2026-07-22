package wfos.bgrxassembly

import wfos.lgriphcd.{LgripInfo}
import wfos.rgriphcd.{RgripInfo}
import wfos.lgmhcd.{LgmInfo}
import wfos.pacthcd.{PactInfo}
import wfos.bgrxassembly.SequenceType
import wfos.bgrxassembly.Step
import wfos.bgrxassembly.AssemblyState
import csw.logging.client.scaladsl.LoggerFactory

class BgrxValidator(loggerFactory: LoggerFactory) {

  private val log = loggerFactory.getLogger

  def validateCurrentStep(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): Boolean =
    sequence match {

      case SequenceType.HOME =>
        validateHome(sequence, step, assemblyState)

      case SequenceType.PICKUP =>
        validatePickup(sequence, step, assemblyState)

      case SequenceType.RETURN =>
        validateReturn(sequence, step, assemblyState)

      case SequenceType.EXCHANGE =>
        validateExchange(sequence, step, assemblyState)
    }

  // =====================================================
  // HOME VALIDATION
  // =====================================================

  private def validateHome(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): Boolean =
    step match {

      case Step.PACT =>
        true

      case Step.LGM =>
        log.info(
          s"pact current position in assembly state - ${assemblyState.pactState.currentPosition} " +
            s"and pact outposition in pact hcd ${PactInfo.outPosition.head} "
        )
        assemblyState.pactState.currentPosition == PactInfo.outPosition.head

      case Step.LGRIP =>
        log.info(
          s"lgm current position in assembly state - ${assemblyState.lgmState.currentPosition} " +
            s"and in lgm hcd ${LgmInfo.homePosition.head} "
        )
        assemblyState.lgmState.currentPosition == LgmInfo.homePosition.head

      case Step.RGRIP =>
        log.info(
          s"lgrip current position in assembly state - ${assemblyState.lgripState.currentPosition} " +
            s"and lgrip  hcd ${LgripInfo.homePosition} "
        )
        assemblyState.lgripState.currentPosition == LgripInfo.homePosition

    }

  // =====================================================
  // PICKUP VALIDATION
  // =====================================================

  private def validatePickup(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): Boolean =
    step match {

      case Step.RGRIP =>
        true

      case Step.LGRIP =>
        true

      case Step.LGM =>
        true

      case Step.PACT =>
        true
    }

  // =====================================================
  // RETURN VALIDATION
  // =====================================================

  private def validateReturn(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): Boolean =
    log.info(
      s"[Validator] current=${assemblyState.rgripState.currentAngle}, expected=${RgripInfo.exchangeAngle.head}"
    )
    step match {

      case Step.RGRIP =>
        val valid = assemblyState.rgripState.gratingId.isDefined

        if (!valid)
          log.error(
            s"Return sequence ABORTED--Validation failed: sequence=$sequence, step=$step, rgripState.gratingId is empty at sequence start"
          )

        valid

      case Step.LGRIP =>
        val valid =
          assemblyState.rgripState.currentAngle == RgripInfo.exchangeAngle.head

        if (!valid) {
          log.warn(
            s"Return validation failed: Validation failed: sequence=$sequence, step=$step, rgrip is at ${assemblyState.rgripState.currentAngle}°, expected ${RgripInfo.exchangeAngle.head}°"
          )
        }
        valid

      case Step.LGM =>
        val rgripAtExchange =
          assemblyState.rgripState.currentAngle == RgripInfo.exchangeAngle.head

        if (!rgripAtExchange) {
          log.warn(
            s"Return Validation failed: sequence=$sequence, step=$step  : rgrip is at ${assemblyState.rgripState.currentAngle}°, expected ${RgripInfo.exchangeAngle.head}°"
          )
          return false
        }

        val lgripAtExchange =
          assemblyState.lgripState.currentPosition == LgripInfo.exchangePosition

        if (!lgripAtExchange) {
          log.warn(
            s"Return Validation failed: sequence=$sequence, step=$step : lgrip is at ${assemblyState.lgripState.currentPosition} mm, expected ${LgripInfo.exchangePosition} mm"
          )
          return false
        }

        assemblyState.lgmState.emptySlotBgid match {

          case Some(emptyBgid) =>
            val gratingId = assemblyState.rgripState.gratingId.get

            if (emptyBgid != gratingId) {
              log.error(
                s"Return Validation failed: sequence=$sequence, step=$step : lgm empty slot bgid '$emptyBgid' does not match rgrip grating '$gratingId'"
              )
              return false
            }

          case None =>
            log.error(
              s"Validation failed: sequence=$sequence, step=$step  reason=LGM emptySlotBgid is None."
            )
            return false
        }

        val pactAtOut =
          assemblyState.pactState.currentPosition == PactInfo.outPosition.head

        if (!pactAtOut) {
          log.warn(
            s"Return Validation failed: sequence=$sequence, step=$step  : pact is at ${assemblyState.pactState.currentPosition} mm, expected ${PactInfo.outPosition.head} mm"
          )
          return false
        }

        true

      case Step.PACT =>
        true
    }

  // =====================================================
  // EXCHANGE VALIDATION
  // =====================================================

  private def validateExchange(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): Boolean =
    step match {

      case Step.RGRIP =>
        true

      case Step.LGRIP =>
        true

      case Step.LGM =>
        true

      case Step.PACT =>
        true
    }
}
