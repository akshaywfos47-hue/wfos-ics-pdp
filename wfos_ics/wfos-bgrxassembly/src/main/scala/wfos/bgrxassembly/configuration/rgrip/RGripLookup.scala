package wfos.bgrxassembly.configuration.rgrip

case class RGripLookup(
    entries: Vector[RGripLookupEntry]
)

case class RGripLookupEntry(
    rotationAngle: Int,
    assemblyCoordinate: Int,
    hcdCoordinate: Int
)
