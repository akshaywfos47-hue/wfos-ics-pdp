package wfos.bgrxassembly.command

import wfos.lgriphcd.{LgripInfo}
import wfos.rgriphcd.{RgripInfo}
import wfos.lgmhcd.{LgmInfo}
import wfos.pacthcd.{PactInfo}
import wfos.bgrxassembly.SequenceType
import wfos.bgrxassembly.Step
import wfos.bgrxassembly.AssemblyState
import csw.logging.client.scaladsl.LoggerFactory
import wfos.bgrxassembly.PositionUtil
import wfos.bgrxassembly.models.ValidationMessage
import wfos.bgrxassembly.models.ValidationMessage.*

class BgrxValidator(loggerFactory: LoggerFactory) {

  private val log = loggerFactory.getLogger

  // helper functions

  case class ValidationContext(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  )

  private def validateGratingIdExists(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage = {
    val valid = assemblyState.rgripState.gratingId.isDefined

    if (!valid)
      log.error(s"Validation failed: sequence=$sequence, step=$step: RGRIP gratingId is empty.")

    if (valid)
      ValidationPassed
    else
      RgripHasNoGrating
  }

  private def validateRgripAtExchange(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage = {
    val valid = assemblyState.rgripState.currentAngle == RgripInfo.exchangeAngle.head
    if (!valid)
      log.warn(
        s"Validation failed: sequence=$sequence, step=$step: RGRIP is at ${assemblyState.rgripState.currentAngle}°, expected ${RgripInfo.exchangeAngle.head}°"
      )
    if (valid)
      ValidationPassed
    else
      RgripIsNotAtExchange
  }

  private def validateLgripAtExchange(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage = {
    val valid = assemblyState.lgripState.currentPosition == LgripInfo.exchangePosition
    if (!valid)
      log.warn(
        s"Validation failed: sequence=$sequence, step=$step: LGRIP is at ${assemblyState.lgripState.currentPosition} mm, expected ${LgripInfo.exchangePosition} mm"
      )
    if (valid)
      ValidationPassed
    else
      LgripNotInExchange
  }

  private def validateLgripAtHome(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage = {
    val valid = assemblyState.lgripState.currentPosition == LgripInfo.homePosition
    if (!valid)
      log.warn(
        s"Validation failed: sequence=$sequence, step=$step: LGRIP is at ${assemblyState.lgripState.currentPosition} mm, expected ${LgripInfo.homePosition} mm"
      )
    if (valid)
      ValidationPassed
    else
      LgripNotInHome
  }

  private def validateLgmAtHome(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage = {
    val valid = assemblyState.lgmState.currentPosition == LgmInfo.homePosition.head
    if (!valid)
      log.warn(
        s"... LGM is at ${assemblyState.lgmState.currentPosition} mm, expected ${LgmInfo.homePosition.head} mm"
      )
    if (valid)
      ValidationPassed
    else
      LgmNotInHome
  }

  private def validateLgmEmptySlot(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): ValidationMessage =
    (
      assemblyState.rgripState.gratingId,
      assemblyState.lgmState.emptySlotBgid
    ) match {

      case (Some(gratingId), Some(emptyBgid)) =>
        val valid = gratingId == emptyBgid

        if (!valid)
          log.error(
            s"Validation failed: sequence=$sequence, step=$step: " +
              s"LGM empty slot BGID '$emptyBgid' does not match RGRIP grating ID '$gratingId'."
          )

        if (valid)
          ValidationPassed
        else
          LgmMySlotNotMatchInGratingId

      case (_, None) =>
        log.error(
          s"Validation failed: sequence=$sequence, step=$step: " +
            s"LGM emptySlotBgid is None."
        )
        LgmEmptySlotNotAvailable

      case (None, _) =>
        log.error(
          s"Validation failed: sequence=$sequence, step=$step: " +
            s"RGRIP gratingId is None."
        )
        RgripHasNoGrating
    }

  private def validateLgmAtTarget(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage =
    assemblyState.rgripState.gratingId match {
      case Some(bgid) =>
        val current = assemblyState.lgmState.currentPosition
        val target  = LgmInfo.targetMagazinePosition(bgid)

        // val valid = PositionUtil.isAtPosition(current, target)
        val valid = current == target

        if (!valid) {
          log.warn(f"Current    = $current%.15f")
          log.warn(f"Target     = $target%.15f")
          log.warn(f"Difference = ${current - target}%.15f")

          log.warn(
            s"Validation failed: sequence=$sequence, step=$step: " +
              s"LGM is at $current mm, expected $target mm"
          )
        }

        if (valid)
          ValidationPassed
        else
          LgmNotAtTarget

      case None =>
        log.error(s"Validation failed: sequence=$sequence, step=$step: RGRIP gratingId is empty.")
        RgripHasNoGrating
    }

  private def validatePactAtOut(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage = {
    val valid = assemblyState.pactState.currentPosition == PactInfo.outPosition.head
    if (!valid)
      log.warn(
        s"Validation failed: sequence=$sequence, step=$step: PACT is at ${assemblyState.pactState.currentPosition} mm, expected ${PactInfo.outPosition.head} mm"
      )
    if (valid)
      ValidationPassed
    else
      PactNotOut
  }

  def validateCurrentStep(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage =
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

  private def validateHome(sequence: SequenceType, step: Step, assemblyState: AssemblyState): ValidationMessage =
    step match {

      case Step.PACT =>
        ValidationPassed

      case Step.LGM =>
        validatePactAtOut(sequence, step, assemblyState)

      case Step.LGRIP =>
        validateLgmAtHome(sequence, step, assemblyState)

      case Step.RGRIP =>
        validateLgripAtHome(sequence, step, assemblyState)
    }

  // =====================================================
  // PICKUP VALIDATION
  // =====================================================

  private def validatePickup(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): ValidationMessage =
    step match {

      case Step.RGRIP =>
        ValidationPassed

      case Step.LGRIP =>
        ValidationPassed

      case Step.LGM =>
        ValidationPassed

      case Step.PACT =>
        ValidationPassed
    }

  // =====================================================
  // RETURN VALIDATION
  // =====================================================

  private def validateReturn(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): ValidationMessage =
    step match {

      case Step.RGRIP =>
        validateGratingIdExists(sequence, step, assemblyState)

      case Step.LGRIP =>
        validateRgripAtExchange(sequence, step, assemblyState)

      case Step.LGM =>
        validateRgripAtExchange(sequence, step, assemblyState) match {
          case ValidationPassed =>
            validateLgripAtExchange(sequence, step, assemblyState) match {
              case ValidationPassed =>
                validateLgmEmptySlot(sequence, step, assemblyState) match {
                  case ValidationPassed =>
                    validatePactAtOut(sequence, step, assemblyState)

                  case error =>
                    error
                }
              case error =>
                error
            }
          case error =>
            error
        }

      case Step.PACT =>
        validateGratingIdExists(sequence, step, assemblyState) match {
          case ValidationPassed =>
            validateRgripAtExchange(sequence, step, assemblyState) match {
              case ValidationPassed =>
                validateLgripAtExchange(sequence, step, assemblyState) match {
                  case ValidationPassed =>
                    validateLgmAtTarget(sequence, step, assemblyState) match {
                      case ValidationPassed =>
                        validatePactAtOut(sequence, step, assemblyState)

                      case failure =>
                        failure
                    }
                  case failure =>
                    failure
                }
              case failure =>
                failure
            }
          case failure =>
            failure
        }
    }

  // =====================================================
  // EXCHANGE VALIDATION
  // =====================================================

  private def validateExchange(
      sequence: SequenceType,
      step: Step,
      assemblyState: AssemblyState
  ): ValidationMessage =
    step match {

      case Step.RGRIP =>
        ValidationPassed

      case Step.LGRIP =>
        ValidationPassed

      case Step.LGM =>
        ValidationPassed

      case Step.PACT =>
        ValidationPassed
    }
}
