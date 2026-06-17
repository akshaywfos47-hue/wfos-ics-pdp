package wfos.bgrxassembly.state

import csw.params.core.generics.Parameter

// Rgripstate
case class RgripState(
    currentAngle: Int,
    hasGrating: Boolean,
    gratingId: Option[String]
)
// lgripstate
case class LgripState(
    currentPosition: Int
)
//lgmhcd state
case class LgmState(
    currentPosition: Double,
    emptySlot: Boolean,
    emptySlotIndex: Option[Int],
    emptySlotBgid: Option[String],
    emptySlotLinearDistance: Option[Double]
)

// pact states
case class PactState(
    currentPosition: Double
)

sealed trait Operation
case object EXCHANGE extends Operation
case object RETURN extends Operation
case object PICKUP extends Operation
case object HOME extends Operation
case object PARK extends Operation
case object NONE extends Operation

case class AssemblyStateVariable(
                   currentOperation : Operation,
                   currentGratingMode: Parameter[String]
                    )