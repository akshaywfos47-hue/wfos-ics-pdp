package wfos.bgrxassembly

import wfos.bgrxassembly.SequenceType
import wfos.bgrxassembly.Step
import csw.prefix.models.Prefix
import csw.params.core.models.ObsId
import csw.params.commands.{CommandName, Setup}
import wfos.bgrxassembly.AssemblyState

object BgrxCommandBuilder {

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

      // =====================================================
      // RETURN
      // =====================================================

      case SequenceType.RETURN =>
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
