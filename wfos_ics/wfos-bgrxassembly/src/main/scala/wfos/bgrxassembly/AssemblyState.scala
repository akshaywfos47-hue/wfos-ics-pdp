package wfos.bgrxassembly

import wfos.rgriphcd.RgripState
import wfos.lgriphcd.LgripState
import wfos.lgmhcd.LgmState
import wfos.pacthcd.PactState

class AssemblyState {

  var rgripState: RgripState = RgripState.initial
  var lgripState: LgripState = LgripState.initial
  var lgmState: LgmState     = LgmState.initial
  var pactState: PactState   = PactState.initial
}
