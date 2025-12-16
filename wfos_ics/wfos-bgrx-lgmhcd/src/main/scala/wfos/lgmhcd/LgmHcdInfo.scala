package wfos.lgmhcd

import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.ObsId
import csw.params.core.models.ArrayData

object LgmInfo {

  // lgmHcd configurations
  val homePositionKey: Key[Double]    = KeyType.DoubleKey.make("homePosition")
  val homePosition: Parameter[Double] = homePositionKey.set(250.0)

  val gratingExchangePositionKey: Key[Double]    = KeyType.DoubleKey.make("gratingExchangePosition")
  val gratingExchangePosition: Parameter[Double] = gratingExchangePositionKey.set(500.0)

  // Current position of start of the HCD
  val currentPositionKey: Key[Double]    = KeyType.DoubleKey.make("currentPosition")
  var currentPosition: Parameter[Double] = currentPositionKey.set(250.0)

  // Positions at which the grating is present
  val gratingLinearDistance: Array[Double]             = Array(67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5, 382.5, 427.5, 472.5)
  val gratingLinearDistanceKey: Key[ArrayData[Double]] = KeyType.DoubleArrayKey.make("gratingLinearDistance")

  // To check whether the grating is present or not
  val gratingMap: Array[Int]             = Array(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
  val gratingMapKey: Key[ArrayData[Int]] = KeyType.IntArrayKey.make("gratingMap")

  // Target position
  val targetGratingPositionKey: Key[Double] = KeyType.DoubleKey.make("targetGratingPosition") // corrected

  // Ranges of target position
  val minTargetGratingKey: Key[Double]    = KeyType.DoubleKey.make("minTargetGratingPosition") // corrected
  val minTargetGrating: Parameter[Double] = minTargetGratingKey.set(0)

  val maxTargetGratingKey: Key[Double]    = KeyType.DoubleKey.make("maxTargetGratingPosition")
  val maxTargetGrating: Parameter[Double] = maxTargetGratingKey.set(500)

  // Event parameters
  val stageKey: Key[String]   = KeyType.StringKey.make("stage")
  val statusKey: Key[String]  = KeyType.StringKey.make("status")
  val messageKey: Key[String] = KeyType.StringKey.make("message")

  val obsId: ObsId = ObsId("2023A-001-123")
}
