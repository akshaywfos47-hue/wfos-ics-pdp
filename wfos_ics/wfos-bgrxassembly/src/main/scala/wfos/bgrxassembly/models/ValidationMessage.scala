package wfos.bgrxassembly.models

enum ValidationMessage {
  case ValidationPassed

  // RGRIP
  case RgripHasNoGrating
  case RgripIsNotAtExchange

  // LGRIP
  case LgripNotInHome
  case LgripNotInExchange

  // LGM
  case LgmNotInHome
  case LgmNotAtTarget
  case LgmMySlotNotMatchInGratingId
  case LgmEmptySlotNotAvailable

  // PACT
  case PactNotIn
  case PactNotOut
}
