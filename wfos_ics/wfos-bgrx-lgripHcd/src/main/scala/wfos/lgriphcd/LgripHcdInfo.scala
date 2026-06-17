package wfos.lgriphcd

import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.{ObsId}

object LgripInfo {
  // lgripHcd configurations
  val exchangePositionKey: Key[Int]    = KeyType.IntKey.make("exchangePosition")
  val exchangePosition: Parameter[Int] = exchangePositionKey.set(100)

  val homePositionKey: Key[Int]    = KeyType.IntKey.make("homePosition")
  val homePosition: Parameter[Int] = homePositionKey.set(0)

  val currentPositionKey: Key[Int] = KeyType.IntKey.make("currentPosition")
  // var currentPosition: Parameter[Int] = currentPositionKey.set(0)

  val targetPositionKey: Key[Int] = KeyType.IntKey.make("targetPosition")
  // val gratingModeKey: Key[String] = KeyType.StringKey.make("gratingMode")
  // val cwKey: Key[Int]             = KeyType.IntKey.make("cw")

  // event parameters
  val stageKey: Key[String]   = KeyType.StringKey.make("stage")
  val statusKey: Key[String]  = KeyType.StringKey.make("status")
  val messageKey: Key[String] = KeyType.StringKey.make("message")

  // ranges of targetPosition
  val minTargetPositionKey: Key[Int]    = KeyType.IntKey.make("minTargetPosition")
  val minTargetPosition: Parameter[Int] = minTargetPositionKey.set(0)

  val maxTargetPositionKey: Key[Int]    = KeyType.IntKey.make("maxTargetPosition")
  val maxTargetPosition: Parameter[Int] = maxTargetPositionKey.set(100)

  val obsId: ObsId = ObsId("2023A-001-123")
}
