package wfos.lgmhcd

case class LgmState(
    currentPosition: Double,
    emptySlot: Boolean,
    emptySlotIndex: Option[Int],
    emptySlotBgid: Option[String],
    emptySlotLinearDistance: Option[Double]
)
