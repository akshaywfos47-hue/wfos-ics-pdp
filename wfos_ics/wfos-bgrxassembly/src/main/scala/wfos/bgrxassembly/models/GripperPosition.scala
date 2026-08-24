//old
package wfos.bgrxassembly.models

import csw.params.core.generics.GChoiceKey
import csw.params.core.generics.KeyType.ChoiceKey
import csw.params.core.models.Choices
import enumeratum.{Enum, EnumEntry}

sealed trait GripperPosition extends EnumEntry {
  protected def getIndexOf(currentPos: GripperPosition): Int
  protected def nextPosition(step: Int): GripperPosition

  final def nextPosition(target: GripperPosition): GripperPosition = nextPosition(nextIndexDiff(target))

  private final def nextIndexDiff(target: GripperPosition): Int = {
    val currId   = getIndexOf(this)
    val targetId = getIndexOf(target)

    if (this == target) 0
    else if (currId > targetId) -1
    else +1
  }
}

sealed abstract class BluegrxPosition(override val entryName: String) extends GripperPosition {

  override def getIndexOf(currentPos: GripperPosition): Int =
    BluegrxPosition.values.indexOf(currentPos)

  override def nextPosition(step: Int): BluegrxPosition = {
    val currId = getIndexOf(this)
    val nextId = currId + step
    if (nextId < 0 || nextId >= BluegrxPosition.values.length) this
    else BluegrxPosition.values(nextId)
  }
}

object BluegrxPosition extends Enum[BluegrxPosition] {
  override def values: IndexedSeq[BluegrxPosition] = findValues

  private lazy val choices: Choices              = Choices.from(values.map(_.entryName)*)
  def makeChoiceKey(keyName: String): GChoiceKey = ChoiceKey.make(keyName, choices)

  case object left_edge  extends BluegrxPosition("0mm")
  case object right_edge extends BluegrxPosition("100mm")
}
