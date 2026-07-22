package wfos.bgrxassembly.api

import wfos.bgrxassembly.{BgrxassemblyHandlers, SequenceType}

class BgrxApiService(
    assembly: AssemblyApi
) {

  def home(): Unit = {
    assembly.executeSequence(SequenceType.HOME)
  }
  def returns(): Unit = {
    assembly.executeSequence(SequenceType.RETURN)
  }

  def park(): Unit = {
    assembly.executeSequence(SequenceType.PARK)
  }
}
