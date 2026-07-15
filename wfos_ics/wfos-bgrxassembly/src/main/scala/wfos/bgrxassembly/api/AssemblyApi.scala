package wfos.bgrxassembly.api

import wfos.bgrxassembly.SequenceType

trait AssemblyApi {

  def executeSequence(sequence: SequenceType): Unit

}
