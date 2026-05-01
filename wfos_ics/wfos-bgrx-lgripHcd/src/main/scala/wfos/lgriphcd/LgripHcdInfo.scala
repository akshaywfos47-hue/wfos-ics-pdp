package wfos.lgriphcd

import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.ObsId

// ─────────────────────────────────────────────────────────────────────────────
// LgripState – single source of truth for the linear gripper's runtime state.
//
// The HCD mutates this object and publishes it as a `lgripStateEvent` after
// every meaningful state change so the Assembly always has an up-to-date view.
//
// Fields
//   currentPosition – current linear position in mm  (0 – 1000)
//   operation       – label of the active sequence step:
//                       "idle"
//                       "returnSequence-toExchange"   (Section 6, Step 2)
//                       "pickupSequence-toExchange"   (Section 7, Step 2)
//                       "pickupSequence-toHome"       (Section 7, Step 5)
// ─────────────────────────────────────────────────────────────────────────────
case class LgripState(
    currentPosition: Int,
    operation: String
)

object LgripState {

  /** Initial state on HCD start-up: parked at home (0 mm), idle. */
  val initial: LgripState = LgripState(
    currentPosition = 200,
    operation = "idle"
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// LgripInfo – all CSW parameter keys used by lgripHcd and the Assembly.
// ─────────────────────────────────────────────────────────────────────────────
object LgripInfo {

  // ── Fixed configuration values ──────────────────────────────────────────────
  /** Exchange position (mm) – fixed at 1000 mm per spec. */
  val exchangePositionKey: Key[Int]         = KeyType.IntKey.make("exchangePosition")
  val exchangePosition: Int                 = 1000 // mm  (used as plain Int for arithmetic)
  val exchangePositionParam: Parameter[Int] = exchangePositionKey.set(exchangePosition)

  /** Home position (mm) – fixed at 0 mm per spec. */
  val homePositionKey: Key[Int]         = KeyType.IntKey.make("homePosition")
  val homePosition: Int                 = 0 // mm
  val homePositionParam: Parameter[Int] = homePositionKey.set(homePosition)

  // ── Motion keys ─────────────────────────────────────────────────────────────
  val currentPositionKey: Key[Int] = KeyType.IntKey.make("currentPosition")
  val targetPositionKey: Key[Int]  = KeyType.IntKey.make("targetPosition")

  // ── State / operation key ────────────────────────────────────────────────────
  val operationKey: Key[String] = KeyType.StringKey.make("operation")

  // ── Per-step movement event key ──────────────────────────────────────────────
  /** Carries a human-readable progress message (e.g. "Moving to 500 mm"). */
  val messageKey: Key[String] = KeyType.StringKey.make("message")

  // ── Status event keys ────────────────────────────────────────────────────────
  val stageKey: Key[String]  = KeyType.StringKey.make("stage")
  val statusKey: Key[String] = KeyType.StringKey.make("status")

  // ── Target-position range ────────────────────────────────────────────────────
  val minTargetPositionKey: Key[Int]    = KeyType.IntKey.make("minTargetPosition")
  val minTargetPosition: Parameter[Int] = minTargetPositionKey.set(0)

  val maxTargetPositionKey: Key[Int]    = KeyType.IntKey.make("maxTargetPosition")
  val maxTargetPosition: Parameter[Int] = maxTargetPositionKey.set(1000)

  val obsId: ObsId = ObsId("2023A-001-123")
}
