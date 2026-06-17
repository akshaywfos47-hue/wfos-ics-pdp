package wfos.rgriphcd

// import csw.params.commands.CommandName
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.{ObsId}

object RgripInfo {
  // rgripHcd configurations
  val exchangeAngleKey: Key[Int]    = KeyType.IntKey.make("exchangeAngle")
  val exchangeAngle: Parameter[Int] = exchangeAngleKey.set(35)

  // val homeAngleKey: Key[Int]    = KeyType.IntKey.make("homeAngle")
  // val homeAngle: Parameter[Int] = homeAngleKey.set(28)

  val currentAngleKey: Key[Int] = KeyType.IntKey.make("currentAngle")
  // var currentAngle: Parameter[Int] = currentAngleKey.set(28)

  val targetAngleKey: Key[Int]    = KeyType.IntKey.make("targetAngle")
  val gratingModeKey: Key[String] = KeyType.StringKey.make("gratingMode")
  val cwKey: Key[Int]             = KeyType.IntKey.make("cw")

  // event parameters
  val stageKey: Key[String]  = KeyType.StringKey.make("stage")
  val statusKey: Key[String] = KeyType.StringKey.make("status")
  val angleKey: Key[Int]     = KeyType.IntKey.make("angle")

  // ranges of targetAngle
  val minTargetAngleKey: Key[Int]    = KeyType.IntKey.make("minTargetAngle")
  val minTargetAngle: Parameter[Int] = minTargetAngleKey.set(0)

  val maxTargetAngleKey: Key[Int]    = KeyType.IntKey.make("maxTargetAngle")
  val maxTargetAngle: Parameter[Int] = maxTargetAngleKey.set(55)

  // ranges of target CommonWavelength
  val minCWKey: Key[Int]    = KeyType.IntKey.make("minCW")
  val minCW: Parameter[Int] = minCWKey.set(3100)

  val maxCWKey: Key[Int]    = KeyType.IntKey.make("maxCW")
  val maxCW: Parameter[Int] = maxCWKey.set(9000)

  val obsId: ObsId = ObsId("2023A-001-123")

  // grating presence key
  val rgripHasGratingKey: Key[Boolean] = KeyType.BooleanKey.make("gratingPresence")
  // val rgripHasGrating: Parameter[Boolean] = gratingPresenceKey.set(true)

  val rgripGratingIdKey: Key[String] = KeyType.StringKey.make("gratingId")
  // val rgripGratingId: Parameter[String] = rgripGratingIdKey.set("bgid2")

  val operationKey: Key[String] = KeyType.StringKey.make("operation")

}
