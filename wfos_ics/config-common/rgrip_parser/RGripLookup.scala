package config_common.rgrip_parser

case class RGripLookupEntry(
                             rotationAngle: Double,
                             assemblyCoordinate: Double,
                             hcdCoordinate: Double
                           )

case class RGripLookup(
                        entries: Vector[RGripLookupEntry]
                      )