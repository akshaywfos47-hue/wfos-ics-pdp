package wfos.bgrxassembly.models

import wfos.bgrxassembly.SequenceType

case class SequenceCommandRequest(
    sequence: SequenceType,
    requestedBgid: Option[String],
    observationAngle: Option[Int],
    cw: Option[Int]
)
