package wfos.lgmhcd

// import csw.params.commands.CommandName
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.{ObsId}
import csw.params.core.models.ArrayData

object LgmInfo {
  // lgmHcd configurations
  val homePositionKey: Key[Double]    = KeyType.DoubleKey.make("homePosition")
  val homePosition: Parameter[Double] = homePositionKey.set(250.0)

  val gratingExchangePositionKey: Key[Double]    = KeyType.DoubleKey.make("gratingExchangePosition")
  val gratingExchangePosition: Parameter[Double] = gratingExchangePositionKey.set(500.0)

  // current postion of start of the hcd
  val currentPositionKey: Key[Double] = KeyType.DoubleKey.make("currentPosition")
  // var currentPosition: Parameter[Double] = currentPositionKey.set(250.0)

  // Postions at which the grating is present
  val gratingLinearDistance: Array[Double]             = Array(67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5, 382.5, 427.5, 472.5)
  val gratingLinearDistanceKey: Key[ArrayData[Double]] = KeyType.DoubleArrayKey.make("gratingLinearDistance")
  // val gratingPostion: Parameter[ArrayData[Double]]     = gratingLinearDistance.set(ArrayData(gratingLinearDistance))

  // TO check whether the grating is present or not
  val gratingMap: Array[Int]             = Array(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
  val gratingMapKey: Key[ArrayData[Int]] = KeyType.IntArrayKey.make("gratingMap")

  // target position
  val targetGratingPositionKey: Key[Double] = KeyType.DoubleKey.make("targetGratingPosition")

  // ranges of targetPosition
  val minTargetGratingKey: Key[Double]    = KeyType.DoubleKey.make("minTargetGratingPosition")
  val minTargetGrating: Parameter[Double] = minTargetGratingKey.set(0)

  val maxTargetGratingKey: Key[Double]    = KeyType.DoubleKey.make("maxTargetGratingPosition")
  val maxTargetGrating: Parameter[Double] = maxTargetGratingKey.set(500)

  // event parameters
  val stageKey: Key[String]   = KeyType.StringKey.make("stage")
  val statusKey: Key[String]  = KeyType.StringKey.make("status")
  val messageKey: Key[String] = KeyType.StringKey.make("message")

  val obsId: ObsId = ObsId("2023A-001-123")

  // linear distance of the grating id
  val bgidToLinearPosition: Map[String, Double] = Map(
    "bgid1"  -> 67.5,
    "bgid2"  -> 112.5,
    "bgid3"  -> 157.5,
    "bgid4"  -> 202.5,
    "bgid5"  -> 247.5,
    "bgid6"  -> 292.5,
    "bgid7"  -> 337.5,
    "bgid8"  -> 382.5,
    "bgid9"  -> 427.5,
    "bgid10" -> 472.5
  )

  val emptySlotKey: Key[Boolean] = KeyType.BooleanKey.make("slotEmpty")

  // val slotEmptyParam: Parameter[Boolean] = slotEmptyKey.set(true) // slot empty

  // Empty slot index
  val emptySlotIndexKey: Key[Int] = KeyType.IntKey.make("emptySlotIndex")

  // Default → bgid3 → index = 2
  // val emptySlotIndex: Parameter[Int] = emptySlotIndexKey.set(2)

  // Empty slot BGID
  val emptySlotBgidKey: Key[String] = KeyType.StringKey.make("emptySlotBgid")

  // Default → bgid3
  // val emptySlotBgid: Parameter[String] = emptySlotBgidKey.set("bgid3")

  // Empty slot linear distance
  val emptySlotLinearDistanceKey: Key[Double] =
    KeyType.DoubleKey.make("emptySlotLinearDistance")
  val operationKey: Key[String] = KeyType.StringKey.make("operation")
}
