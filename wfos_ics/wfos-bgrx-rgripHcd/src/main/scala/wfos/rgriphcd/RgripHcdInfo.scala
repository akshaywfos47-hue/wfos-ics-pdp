package wfos.rgriphcd

import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.ObsId

// ─────────────────────────────────────────────────────────────────────────────
// RgripState – single source of truth for the rotary gripper's runtime state.
//
// The HCD mutates this object and publishes it as an event after every
// meaningful state change so that the Assembly always has an up-to-date view.
//
// Fields
//   currentAngle  – current rotary position in degrees (0 – 55)
//   hasGrating    – true while rgrip is holding a grating
//   gratingId     – e.g. "bgid1"…"bgid5"; empty string when hasGrating = false
//   operation     – human-readable label of the active sequence step:
//                     "idle"
//                     "returnSequence"
//                     "pickupSequence-exchange"
//                     "pickupSequence-observation"
// ─────────────────────────────────────────────────────────────────────────────
case class RgripState(
    currentAngle: Int,
    hasGrating: Boolean,
    gratingId: Option[String],
    operation: String
)

object RgripState {

  /** Initial state on HCD start-up: parked at 28 °, no grating, idle. */
  val initial: RgripState = RgripState(
    currentAngle = 28,
    hasGrating = true,
    gratingId = Some("bgid2"),
    operation = "idle"
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RgripInfo – all CSW parameter keys used by rgripHcd and consumed by the
//             BgrxAssembly.
// ─────────────────────────────────────────────────────────────────────────────
object RgripInfo {

  // ── Fixed configuration values ──────────────────────────────────────────────

  val homeAngleKey: Key[Int]    = KeyType.IntKey.make("homeAngle")
  val homeAngle: Parameter[Int] = homeAngleKey.set(0)

  val exchangeAngleKey: Key[Int]    = KeyType.IntKey.make("exchangeAngle")
  val exchangeAngle: Parameter[Int] = exchangeAngleKey.set(35)

  // ── Motion keys ─────────────────────────────────────────────────────────────
  val currentAngleKey: Key[Int] = KeyType.IntKey.make("currentAngle")
  val targetAngleKey: Key[Int]  = KeyType.IntKey.make("targetAngle")

  // ── Command auxiliary keys ───────────────────────────────────────────────────
  val gratingModeKey: Key[String] = KeyType.StringKey.make("gratingMode")
  val cwKey: Key[Int]             = KeyType.IntKey.make("cw")

  // ── Grating-state keys (used in rgripStateEvent) ────────────────────────────
  val hasGratingKey: Key[Boolean] = KeyType.BooleanKey.make("hasGrating")
  val gratingIdKey: Key[String]   = KeyType.StringKey.make("gratingId")
  val operationKey: Key[String]   = KeyType.StringKey.make("operation")

  // ── Per-step rotation event key ─────────────────────────────────────────────
  val angleKey: Key[Int] = KeyType.IntKey.make("angle")

  // ── Status event keys ───────────────────────────────────────────────────────
  val stageKey: Key[String]  = KeyType.StringKey.make("stage")
  val statusKey: Key[String] = KeyType.StringKey.make("status")

  // ── Target-angle range ──────────────────────────────────────────────────────
  val minTargetAngleKey: Key[Int]    = KeyType.IntKey.make("minTargetAngle")
  val minTargetAngle: Parameter[Int] = minTargetAngleKey.set(0)

  val maxTargetAngleKey: Key[Int]    = KeyType.IntKey.make("maxTargetAngle")
  val maxTargetAngle: Parameter[Int] = maxTargetAngleKey.set(55)

  // ── Common-wavelength range ──────────────────────────────────────────────────
  val minCWKey: Key[Int]    = KeyType.IntKey.make("minCW")
  val minCW: Parameter[Int] = minCWKey.set(3100)

  val maxCWKey: Key[Int]    = KeyType.IntKey.make("maxCW")
  val maxCW: Parameter[Int] = maxCWKey.set(9000)

  val obsId: ObsId = ObsId("2023A-001-123")
}
