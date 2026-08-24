package wfos.bgrxassembly.models

import csw.params.core.models.Id
import wfos.bgrxassembly.Step
import wfos.bgrxassembly.SequenceType

case class SequenceContext(
    currentSequence: Option[SequenceType],
    currentStep: Option[Step],
    expectedTelemetryStep: Option[Step],
    requiredTelemetrySteps: Set[Step],
    completedTelemetrySteps: Set[Step],
    barrierMode: BarrierMode,
    runId: Option[Id],
    targetGratingId: Option[String],
    isSequenceRunning: Boolean,
    isCurrentStepValid: Boolean
)

class SequenceState {
  var context: SequenceContext = SequenceContext(
    currentSequence = None,
    currentStep = None,
    expectedTelemetryStep = None,
    requiredTelemetrySteps = Set.empty,
    completedTelemetrySteps = Set.empty,
    barrierMode = BarrierMode.NotSet,
    runId = None,
    targetGratingId = None,
    isSequenceRunning = false,
    isCurrentStepValid = false
  )
}
