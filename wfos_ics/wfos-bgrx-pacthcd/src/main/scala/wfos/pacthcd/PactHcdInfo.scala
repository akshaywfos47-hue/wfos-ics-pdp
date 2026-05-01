package wfos.pacthcd

import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.ObsId

case class PactState(
    currentPosition: Double,
    operation: String
)

object PactState {
  val initial: PactState = PactState(currentPosition = 400.0, operation = "idle")
}

object PactInfo {

  val homePositionKey: Key[Double]    = KeyType.DoubleKey.make("homePosition")
  val homePosition: Parameter[Double] = homePositionKey.set(500.0)

  val maxExtensionKey: Key[Double]    = KeyType.DoubleKey.make("maxExtension")
  val maxExtension: Parameter[Double] = maxExtensionKey.set(500.0)

  val minExtensionKey: Key[Double]    = KeyType.DoubleKey.make("minExtension")
  val minExtension: Parameter[Double] = minExtensionKey.set(0.0)

  val movementStepKey: Key[Double]    = KeyType.DoubleKey.make("movementStep")
  val movementStep: Parameter[Double] = movementStepKey.set(50.0)

  val inPositionKey: Key[Double]    = KeyType.DoubleKey.make("inPosition")
  val inPosition: Parameter[Double] = inPositionKey.set(0.0)

  val outPositionKey: Key[Double]    = KeyType.DoubleKey.make("outPosition")
  val outPosition: Parameter[Double] = outPositionKey.set(500.0)

  val targetPositionKey: Key[Double]    = KeyType.DoubleKey.make("targetPosition")
  val targetPosition: Parameter[Double] = targetPositionKey.set(500.0)

  val currentPositionKey: Key[Double]    = KeyType.DoubleKey.make("currentPosition")
  var currentPosition: Parameter[Double] = currentPositionKey.set(500.0)

  val operationKey: Key[String]       = KeyType.StringKey.make("operation")
  val operationStatusKey: Key[String] = KeyType.StringKey.make("operationStatus")
  val stageKey: Key[String]           = KeyType.StringKey.make("stage")
  val statusKey: Key[String]          = KeyType.StringKey.make("status")
  val messageKey: Key[String]         = KeyType.StringKey.make("message")

  val retryCountKey: Key[Int]    = KeyType.IntKey.make("retryCount")
  var retryCount: Parameter[Int] = retryCountKey.set(0)

  val obsId: ObsId = ObsId("2023A-001-456")
}
