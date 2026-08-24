package wfos.bgrxassembly

object PositionUtil {

  private val PositionTolerance = 0.01 // mm

  def isAtPosition(current: Double, target: Double): Boolean =
    math.abs(current - target) <= PositionTolerance
}
