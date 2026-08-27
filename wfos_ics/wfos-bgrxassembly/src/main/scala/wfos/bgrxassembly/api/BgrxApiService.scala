package wfos.bgrxassembly.api
import wfos.bgrxassembly.models.SequenceCommandRequest

import wfos.bgrxassembly.{BgrxassemblyHandlers, SequenceType}

class BgrxApiService(
    assembly: AssemblyApi
) {

  def executeSequence(request: SequenceCommandRequest): Unit = {
    assembly.executeSequence(request)
  }
}
