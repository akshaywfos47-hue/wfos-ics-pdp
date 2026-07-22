package wfos.bgrxassembly

import wfos.bgrxassembly.SequenceType
import wfos.bgrxassembly.Step
import csw.prefix.models.Prefix
import csw.params.core.models.ObsId
import csw.params.commands.{CommandName, Setup}
import wfos.bgrxassembly.AssemblyState
import wfos.lgriphcd.{LgripInfo}
import wfos.rgriphcd.{RgripInfo}
import wfos.lgmhcd.{LgmInfo}
import wfos.pacthcd.{PactInfo}
import wfos.bgrxassembly.configuration.rgrip.RGripLookupService

object BgrxCommandBuilder {

  private def rGripAssemblyCoordinate(targetAngle: Int): Int =
    RGripLookupService
      .assemblyCoordinate(targetAngle)
      .getOrElse(
        throw new IllegalArgumentException(
          s"No lookup entry found for target angle: $targetAngle"
        )
      )

  private def targetMagazinePosition(bgid: String): Double = {
    val slotIndex = LgmInfo.bgidToIndex(bgid)
    LgmInfo.gratingExchangePosition.head -
    LgmInfo.gratingLinearDistance(slotIndex)
  }

  def buildCommand(
      sequence: SequenceType,
      step: Step,
      sourcePrefix: Prefix,
      obsId: ObsId,
      assemblyState: AssemblyState
  ): Setup = {

    val commandName = sequence match {
      case SequenceType.HOME => "homing"
//      case SequenceType.PARK     => "park"
      case SequenceType.PICKUP   => "move"
      case SequenceType.RETURN   => "move"
      case SequenceType.EXCHANGE => "move"
    }

    val command =
      Setup(
        sourcePrefix,
        CommandName(commandName),
        Some(obsId)
      )

    addParameters(sequence, step, command, assemblyState)
  }

  private def addParameters(
      sequence: SequenceType,
      step: Step,
      command: Setup,
      assemblyState: AssemblyState
  ): Setup = {

    sequence match {

      // =====================================================
      // HOME
      // =====================================================

      case SequenceType.HOME =>
        // Homing command has no parameters
        command

      // =====================================================
      // PICKUP
      // =====================================================

      case SequenceType.PICKUP =>
        step match {

          case Step.RGRIP =>
            val updatedCommand = command.madd(
              RgripInfo.targetAngleKey
                .set(RgripInfo.exchangeAngle.head)
            )
            updatedCommand

          case Step.LGRIP =>
            // TODO
            command

          case Step.LGM =>
            // TODO
            command

          case Step.PACT =>
            // TODO
            command
        }

      // =====================================================
      // RETURN
      // =====================================================

      case SequenceType.RETURN =>
        step match {

          case Step.RGRIP =>
            val updatedCommand = command.madd(
              RgripInfo.targetAngleKey
                .set(rGripAssemblyCoordinate(RgripInfo.exchangeAngle.head))
            )
            updatedCommand

          case Step.LGRIP =>
            // TODO
            val updatedCommand = command.madd(LgripInfo.targetPositionKey.set(LgripInfo.exchangePosition))
            updatedCommand

          case Step.LGM =>
            // TODO
            val bgid = assemblyState.rgripState.gratingId.get
            command.madd(LgmInfo.targetGratingPositionKey.set(targetMagazinePosition(bgid)))

          case Step.PACT =>
            // TODO
            command.madd(PactInfo.targetPositionKey.set(PactInfo.outPosition.head), PactInfo.operationKey.set("pull"))
        }

      // =====================================================
      // EXCHANGE
      // =====================================================

      case SequenceType.EXCHANGE =>
        step match {

          case Step.RGRIP =>
            // TODO
            command

          case Step.LGRIP =>
            // TODO
            command

          case Step.LGM =>
            // TODO
            command

          case Step.PACT =>
            // TODO
            command
        }
    }
  }
}
