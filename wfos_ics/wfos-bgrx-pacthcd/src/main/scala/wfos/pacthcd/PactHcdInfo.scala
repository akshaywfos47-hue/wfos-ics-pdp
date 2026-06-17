package wfos.pacthcd

import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.ArrayData
import csw.params.core.models.ObsId

object PactInfo {
  // Physical movement constraints
  val maxExtensionKey: Key[Double]    = KeyType.DoubleKey.make("maxExtension")
  val maxExtension: Parameter[Double] = maxExtensionKey.set(500.0)

  val minExtensionKey: Key[Double]    = KeyType.DoubleKey.make("minExtension")
  val minExtension: Parameter[Double] = minExtensionKey.set(0.0)

  val movementStepKey: Key[Double]    = KeyType.DoubleKey.make("movementStep")
  val movementStep: Parameter[Double] = movementStepKey.set(1.0)

  // Position keys
  val targetPositionKey: Key[Double] = KeyType.DoubleKey.make("targetPosition")
  // val targetPosition: Parameter[Double] = targetPositionKey.set(500.0)

  val currentPositionKey: Key[Double] = KeyType.DoubleKey.make("currentPosition")
  // var currentPosition: Parameter[Double] = currentPositionKey.set(0.0)

  // Predefined positions
  // val inPositionKey: Key[Double]    = KeyType.DoubleKey.make("inPosition")
  // val inPosition: Parameter[Double] = inPositionKey.set(250.0)

  // val outPositionKey: Key[Double]    = KeyType.DoubleKey.make("outPosition")
  // val outPosition: Parameter[Double] = outPositionKey.set(0.0)

  // Retry count
  val retryCountKey: Key[Int]    = KeyType.IntKey.make("retryCount")
  var retryCount: Parameter[Int] = retryCountKey.set(0)

  // Event parameters
  val stageKey: Key[String]   = KeyType.StringKey.make("stage")
  val statusKey: Key[String]  = KeyType.StringKey.make("status")
  val messageKey: Key[String] = KeyType.StringKey.make("message")

  val operationKey: Key[String]       = KeyType.StringKey.make("operation")
  val operationStatusKey: Key[String] = KeyType.StringKey.make("operationStatusKey")

  // Observation ID
  val obsId: ObsId = ObsId("2023A-001-456")
}
// Define the command key for the PACT HCD
