package wfos.bgrxassembly.components

import csw.params.commands.{CommandIssue, Setup}
import csw.params.commands.CommandIssue.{MissingKeyIssue, ParameterValueOutOfRangeIssue}
import csw.params.core.generics.Parameter
import wfos.lgmhcd.LgmInfo

/**
 * Assembly-side wrapper for lgmHcd parameter validation.
 *
 * The Assembly uses this before dispatching a "move" command to lgmHcd.
 * It validates that:
 *   1. operationKey  is present and is "pickup" or "return"
 *   2. targetBgidKey is present and is a known BGID
 */
class Lgmhcd extends Hcd[String] {

  override def validateParameters(setup: Setup): Either[CommandIssue, Parameter[String]] = {
    for {
      operation <- setup
        .get(LgmInfo.operationKey)
        .toRight(MissingKeyIssue("LgmHcd: 'operation' key not found in command"))
      _ <-
        if (operation.head == "pickup" || operation.head == "return") Right(())
        else
          Left(
            ParameterValueOutOfRangeIssue(
              s"LgmHcd: 'operation' must be 'pickup' or 'return', got '${operation.head}'"
            )
          )
      targetBgid <- setup
        .get(LgmInfo.targetBgidKey)
        .toRight(MissingKeyIssue("LgmHcd: 'targetBgid' key not found in command"))
      _ <-
        if (LgmInfo.bgidToIndex(targetBgid.head) >= 0) Right(())
        else
          Left(
            ParameterValueOutOfRangeIssue(
              s"LgmHcd: Unknown BGID '${targetBgid.head}'. " +
                s"Valid values: ${LgmInfo.bgidList.mkString(", ")}"
            )
          )
    } yield targetBgid
  }

  // Range check is not applicable for string-typed BGID parameters.
  // The check is inlined in validateParameters above.
  override def inRange(
      parameter: Parameter[String],
      minVal: Parameter[String],
      maxVal: Parameter[String]
  ): Either[CommandIssue, Parameter[String]] = Right(parameter)
}
