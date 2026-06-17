package wfos.rgriphcd

case class RgripState(
    currentAngle: Int,
    hasGrating: Boolean,
    gratingId: Option[String]
)
