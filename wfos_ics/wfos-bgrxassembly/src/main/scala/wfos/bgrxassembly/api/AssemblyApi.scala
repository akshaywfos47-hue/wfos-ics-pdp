package wfos.bgrxassembly.api

import wfos.bgrxassembly.models.SequenceCommandRequest

trait AssemblyApi {

  def executeSequence(request: SequenceCommandRequest): Unit

}
