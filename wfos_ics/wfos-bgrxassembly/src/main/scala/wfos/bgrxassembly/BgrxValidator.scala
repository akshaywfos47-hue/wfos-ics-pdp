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
        validateHome(step, assemblyState)

      case SequenceType.PICKUP =>
        validatePickup(step, assemblyState)

      case SequenceType.RETURN =>
        validateReturn(step, assemblyState)

      case SequenceType.EXCHANGE =>
        validateExchange(step, assemblyState)
    }

  // =====================================================
  // HOME VALIDATION
  // =====================================================

  private def validateHome(
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
  // EXCHANGE VALIDATION
  // =====================================================

  private def validateExchange(
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
