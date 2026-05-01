package wfos.lgmhcd

import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.{ObsId, ArrayData}

// ─────────────────────────────────────────────────────────────────────────────
// LgmState – single source of truth for the magazine's runtime state.
//
// gratingMap is updated ONLY when the Assembly sends an "updateState" command
// after pactHcd confirms the physical transfer (pactPushPullEvent).
//
// emptySlotIndex = -1, emptySlotBgid = "" mean no slot is currently empty.
// ─────────────────────────────────────────────────────────────────────────────
case class LgmState(
    currentPosition: Double,
    gratingMap: Array[Int],
    emptySlot: Boolean,
    emptySlotIndex: Option[Int],
    emptySlotBgid: Option[String],
    operation: String
) {
  def hasGratingAt(index: Int): Boolean =
    index >= 0 && index < gratingMap.length && gratingMap(index) == 1

  def withGratingMap(index: Int, value: Int): LgmState = {
    val updated = gratingMap.clone()
    updated(index) = value
    copy(gratingMap = updated)
  }
}

object LgmState {
  val initial: LgmState = LgmState(
    currentPosition = 200.0,
    gratingMap = Array(1, 0, 1, 1, 1, 1, 1, 1, 1, 1),
    emptySlot = true,
    emptySlotIndex = Some(1),
    emptySlotBgid = Some("bgid2"),
    operation = "idle"
  )
}

object LgmInfo {

  // ── Fixed positions ────────────────────────────────────────────────────────
  val homePositionKey: Key[Double]    = KeyType.DoubleKey.make("homePosition")
  val homePosition: Parameter[Double] = homePositionKey.set(500.0)

  val gratingExchangePositionKey: Key[Double]    = KeyType.DoubleKey.make("gratingExchangePosition")
  val gratingExchangePosition: Parameter[Double] = gratingExchangePositionKey.set(500.0)

  // ── Runtime position ───────────────────────────────────────────────────────
  val currentPositionKey: Key[Double]    = KeyType.DoubleKey.make("currentPosition")
  var currentPosition: Parameter[Double] = currentPositionKey.set(250.0)

  // ── Slot distances along magazine axis (mm) ────────────────────────────────
  // targetMagazinePos = 500.0 - slotLinearDistance
  val gratingLinearDistance: Array[Double] =
    Array(67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5, 382.5, 427.5, 472.5)

  val gratingLinearDistanceKey: Key[ArrayData[Double]] =
    KeyType.DoubleArrayKey.make("gratingLinearDistance")

  val gratingMapKey: Key[ArrayData[Int]] = KeyType.IntArrayKey.make("gratingMap")

  // ── BGID helpers ───────────────────────────────────────────────────────────
  val bgidList: Array[String] =
    Array("bgid1", "bgid2", "bgid3", "bgid4", "bgid5", "bgid6", "bgid7", "bgid8", "bgid9", "bgid10")

  def bgidToIndex(bgid: String): Int = bgidList.indexOf(bgid)
  def indexToBgid(index: Int): String =
    if (index >= 0 && index < bgidList.length) bgidList(index) else ""

  // ── Command keys ───────────────────────────────────────────────────────────

  // "move" command: assembly sends computed magazine position
  val targetGratingPositionKey: Key[Double] = KeyType.DoubleKey.make("targetGratingPosition")

  // "updateState" command payload keys:
  //   Assembly sends these after pactPushPullEvent to tell lgmHcd what changed.
  //   gratingPresentKey: true  → grating is now in this slot (return complete)
  //                      false → grating left this slot     (pickup complete)
  //   targetBgidKey:     which BGID slot to update
  val gratingPresentKey: Key[Boolean] = KeyType.BooleanKey.make("gratingPresent")
  val targetBgidKey: Key[String]      = KeyType.StringKey.make("targetBgid")

  // ── Range guard ────────────────────────────────────────────────────────────
  val minTargetGratingKey: Key[Double]    = KeyType.DoubleKey.make("minTargetGratingPosition")
  val minTargetGrating: Parameter[Double] = minTargetGratingKey.set(0.0)

  val maxTargetGratingKey: Key[Double]    = KeyType.DoubleKey.make("maxTargetGratingPosition")
  val maxTargetGrating: Parameter[Double] = maxTargetGratingKey.set(500.0)

  // ── Event keys ─────────────────────────────────────────────────────────────
  val emptySlotKey: Key[Boolean]              = KeyType.BooleanKey.make("emptySlot")
  val emptySlotIndexKey: Key[Int]             = KeyType.IntKey.make("emptySlotIndex")
  val emptySlotBgidKey: Key[String]           = KeyType.StringKey.make("emptySlotBgid")
  val emptySlotLinearDistanceKey: Key[Double] = KeyType.DoubleKey.make("emptySlotLinearDistance")
  val operationKey: Key[String]               = KeyType.StringKey.make("operation")
  val stageKey: Key[String]                   = KeyType.StringKey.make("stage")
  val statusKey: Key[String]                  = KeyType.StringKey.make("status")
  val messageKey: Key[String]                 = KeyType.StringKey.make("message")

  val obsId: ObsId = ObsId("2023A-001-123")
}
