package wfos.bgrxassembly

import csw.params.events.{EventKey, EventName, Event, SystemEvent}
import csw.logging.client.scaladsl.LoggerFactory
import csw.logging.api.scaladsl.Logger
import csw.params.events.EventName

import wfos.lgriphcd.{LgripInfo, LgripState}
import wfos.rgriphcd.{RgripInfo, RgripState}
import wfos.lgmhcd.{LgmInfo, LgmState}
import wfos.pacthcd.{PactInfo, PactState}
import wfos.bgrxassembly.models.{BarrierMode, SequenceState}

class TelemetryManager(
    loggerFactory: LoggerFactory,
    assemblyState: AssemblyState,
//    isSequenceRunning: () => Boolean,
//    isCurrentStepValid: () => Boolean,
//    getCurrentStep: () => Option[Step],
    sequenceState: SequenceState,
    publishGratingTransferEvent: (String, String) => Unit,
    completeReturnTransfer: () => Unit,
    completePickupTransfer: () => Unit,
    stateUpdateCompleted: Step => Unit
) {
  def apply(): Unit = {
    println("telementry manager 555")
  }

  // logger factory context by assembly
  private val log = loggerFactory.getLogger

//sequence context initialization
  // val sequenceContext = getSequenceContext()

  private def isEventAllowed(currentStep: Step, event: SystemEvent): Boolean = {
    if (!sequenceState.context.isSequenceRunning) {
      return false
    }

    val source = event.source.toString.toLowerCase

    sequenceState.context.barrierMode match {
      case BarrierMode.Recovery =>
        sequenceState.context.expectedTelemetryStep.exists {
          case Step.RGRIP => source.contains("rgriphcd")
          case Step.LGRIP => source.contains("lgriphcd")
          case Step.LGM   => source.contains("lgmhcd")
          case Step.PACT  => source.contains("pacthcd")
          case _          => false
        }

      case BarrierMode.Normal =>
        currentStep match {
          case Step.PACT =>
            source.contains("pacthcd") ||
            (source.contains("lgmhcd") && event.eventName == EventName("LgmStatusEvent")) ||
            (source.contains("rgriphcd") && event.eventName == EventName("rgripStateEvent"))
          case Step.LGM   => source.contains("lgmhcd")
          case Step.LGRIP => source.contains("lgriphcd")
          case Step.RGRIP => source.contains("rgriphcd")
          case _          => false
        }
      case BarrierMode.NotSet => false
    }
  }

  private def markTelemetryCompleted(step: Step): Unit = {
    val isPactTransferStep =
      sequenceState.context.currentStep.contains(Step.PACT) &&
        (sequenceState.context.currentSequence.contains(SequenceType.RETURN) ||
          sequenceState.context.currentSequence.contains(SequenceType.PICKUP))

    if (
      isPactTransferStep &&
      sequenceState.context.requiredTelemetrySteps.contains(step)
    ) {
      sequenceState.context = sequenceState.context.copy(
        completedTelemetrySteps = sequenceState.context.completedTelemetrySteps + step
      )
    }
  }

  def eventServiceHelper(event: Event): Unit = {
    // log.info(s"TelemetryManager received event now : ${event.eventName}")
    if (!sequenceState.context.isSequenceRunning) {
      return
    }

    if (!sequenceState.context.isCurrentStepValid) {
      return
    }

    event match {
      case sysEvent: SystemEvent =>
        val currentStep = sequenceState.context.currentStep match {
          case Some(step) => step
          case None       => return
        }

        if (!isEventAllowed(currentStep, sysEvent)) {
          log.debug(s"Ignoring event ${sysEvent.eventName} from ${sysEvent.source} for step $currentStep")
          return
        }
        sysEvent.eventName match {

          case EventName("RgripRotationEvent") =>
            // log.info(" recived rgripRotation event in telementry manager")
            handleRgripRotationEvent(sysEvent)

          case EventName("rgripStateEvent") =>
            // log.info(" recived rgripstatus event in telementry manager")
            handleRgripStateEvent(sysEvent)

          // lgrip events
          case EventName("lgripMoveToExchangePositionEvent") =>
            // log.info(" recived lgripMoveToExchangePositionEventevent in telementry manager")
            handlelgripMoveToExchangePositionEvent(sysEvent)

          case EventName("lgripMoveToHomePositionEvent") =>
            // log.info(" recived lgripMoveToHomePositionEvent in telementry manager")
            handlelgripMoveToHomePositionEvent(sysEvent)

          case EventName("lgripStateEvent") =>
            // log.info(" recived lgripStateEvent event in telementry manager")
            handlelgripStateEvent(sysEvent)

          // lgm events
          case EventName("LgmMovementEvent") =>
            // log.info(" recived LgmMovementEvent event in telementry manager")
            handleLgmMovementEvent(sysEvent)

          case EventName("LgmStatusEvent") =>
            // og.info("recived LgmStatusEvent event in telementry manager")
            handleLgmStatusEvent(sysEvent)

          // pact events
          case EventName("PactMovementEvent") =>
            // log.info(" recived PactMovementEvent event in telementry manager")
            handlePactMovementEvent(sysEvent)

          case EventName("PactStatusEvent") =>
            // log.info("pact status event is recived in telementry")
            handlePactStatusEvent(sysEvent)

          case EventName("pactPushPullEvent") =>
            // log.info(" recived pactPushPullEvent event in telementry manager")
            handlepactPushPullEvent(sysEvent)

          case name =>
            log.warn(s"Unknown event: $name")
        }
      case _ =>
    }
  }

  private def handleRgripRotationEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val angle = sysEvent(RgripInfo.angleKey).head
        assemblyState.rgripState = assemblyState.rgripState.copy(currentAngle = angle)
        log.info(s"Assembly [RgripRotationEvent]: rgrip at $angle°")
      case _ =>
    }
  }
  private def handleRgripStateEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val angle      = sysEvent(RgripInfo.currentAngleKey).head
        val hasGrating = sysEvent(RgripInfo.hasGratingKey).head
        val gratingId  = sysEvent(RgripInfo.gratingIdKey).head
        val operation  = sysEvent(RgripInfo.operationKey).head

        log.info(
          s"[StateEvent] received angle=$angle, oldAngle=${assemblyState.rgripState.currentAngle}"
        )
        assemblyState.rgripState = RgripState(
          currentAngle = angle,
          hasGrating = hasGrating,
          gratingId = if (hasGrating && gratingId.nonEmpty) Some(gratingId) else None,
          operation = operation
        )
        log.info(s" Telementry Assembly [rgripStateEvent]: angle=$angle°, hasGrating=$hasGrating, gratingId=$gratingId")
        markTelemetryCompleted(Step.RGRIP)
        stateUpdateCompleted(Step.RGRIP)

      case _ =>
    }
  }

  private def handlelgripMoveToExchangePositionEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val pos = sysEvent(LgripInfo.currentPositionKey).head
        assemblyState.lgripState = assemblyState.lgripState.copy(currentPosition = pos)
        log.info(s" Telementry Assembly [lgripMoveToExchangePositionEvent]: lgrip at $pos mm")
      case _ =>
    }
  }

  private def handlelgripMoveToHomePositionEvent(event: SystemEvent): Unit = {

    log.info(s"lgripmovetohomepositionevent currrent position  ")
    event match {
      case sysEvent: SystemEvent =>
        val pos = sysEvent(LgripInfo.currentPositionKey).head
        log.info(s"lgripmovetohomepositionevent currrent position = $pos ")
        assemblyState.lgripState = assemblyState.lgripState.copy(currentPosition = pos)
        log.info(s" Telementry  Assembly [lgripMoveToHomePositionEvent]: lgrip at $pos mm")
      case _ =>
    }
  }

  private def handlelgripStateEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val pos       = sysEvent(LgripInfo.currentPositionKey).head
        val operation = sysEvent(LgripInfo.operationKey).head
        assemblyState.lgripState = LgripState(currentPosition = pos, operation = operation)
        log.info(s" Telementry Assembly [lgripStateEvent]: pos=$pos mm, op=$operation")
        stateUpdateCompleted(Step.LGRIP)
      case _ =>
    }
  }

  private def handleLgmMovementEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val pos = sysEvent(LgmInfo.currentPositionKey).head
        assemblyState.lgmState = assemblyState.lgmState.copy(currentPosition = pos)
        log.info(s" Telementry Assembly [LgmMovementEvent]: lgm at $pos mm")
      case _ =>
    }
  }

  private def handleLgmStatusEvent(event: SystemEvent): Unit = {
    log.info(s"LgmStatusEvent received: $event")
    log.info(s"ParameterSet: ${event.paramSet}")
    event match {
      case sysEvent: SystemEvent =>
        val pos            = sysEvent(LgmInfo.currentPositionKey).head
        val operation      = sysEvent(LgmInfo.operationKey).head
        val emptySlot      = sysEvent(LgmInfo.emptySlotKey).head
        val emptySlotIndex = sysEvent(LgmInfo.emptySlotIndexKey).head
        val emptySlotBgid  = sysEvent(LgmInfo.emptySlotBgidKey).head
        assemblyState.lgmState = assemblyState.lgmState.copy(
          currentPosition = pos,
          operation = operation,
          emptySlot = emptySlot,
          emptySlotIndex = if (emptySlot && emptySlotIndex >= 0) Some(emptySlotIndex) else None,
          emptySlotBgid = if (emptySlot && emptySlotBgid.nonEmpty) Some(emptySlotBgid) else None
        )
        log.info(
          s" Telementry Assembly [LgmStatusEvent]: pos=$pos mm, emptySlot=$emptySlot, emptySlotBgid=$emptySlotBgid"
        )

        // LgmStatusEvent is the last event in the gratingTransferEvent chain
        // (lgmHcd publishes it after updating its gratingMap). At this point
        // both lgmHcd and rgripHcd have fully processed the transfer.
        // Execute any pending continuation that was waiting for this confirmation.

//        onReturnTransferComplete.foreach { continuation =>
//          onReturnTransferComplete = None
//          log.info(" Telementry Assembly [LgmStatusEvent]: gratingTransfer(pull) confirmed – executing return continuation")
//          // Delay so any in-flight rgripStateEvent settles before the next step reads rgripState
//          ctx.system.scheduler.scheduleOnce(Duration.ofMillis(10), () => continuation(), ec)
//        }
        // *** this above code is moved to assembly and instead of full code we are calling assembly function
        completeReturnTransfer()

//        onPickupTransferComplete.foreach { continuation =>
//          onPickupTransferComplete = None
//          log.info("Telementry Assembly [LgmStatusEvent]: gratingTransfer(push) confirmed – executing pickup continuation")
//          // Delay so any in-flight rgripStateEvent settles before the next step reads rgripState
//          ctx.system.scheduler.scheduleOnce(Duration.ofMillis(10), () => continuation(), ec)
//        }
        // this code also moved to assembly instead we are calling below function
        completePickupTransfer()
        markTelemetryCompleted(Step.LGM)
        stateUpdateCompleted(Step.LGM)

      case _ =>
    }
  }

  private def handlePactMovementEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val pos = sysEvent(PactInfo.currentPositionKey).head
        assemblyState.pactState = assemblyState.pactState.copy(currentPosition = pos)
        log.info(s" Telementry Assembly [PactMovementEvent]: pact at $pos mm")
      case _ =>
    }
  }

  private def handlePactStatusEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val pos       = sysEvent(PactInfo.currentPositionKey).head
        val operation = sysEvent(PactInfo.operationKey).head
        assemblyState.pactState = assemblyState.pactState.copy(currentPosition = pos, operation = operation)
        log.info(s" Telementry Assembly [PactStatusEvent]: pos=$pos mm, op=$operation")
        markTelemetryCompleted(Step.PACT)
        stateUpdateCompleted(Step.PACT)

      case _ =>
    }

  }

  private def handlepactPushPullEvent(event: SystemEvent): Unit = {
    event match {
      case sysEvent: SystemEvent =>
        val operation = sysEvent(PactInfo.operationKey).head
        val pos       = sysEvent(PactInfo.currentPositionKey).head
        assemblyState.pactState = assemblyState.pactState.copy(currentPosition = pos, operation = operation)

        operation match {
          case "push" =>
            val slotLinearDist = LgmInfo.gratingExchangePosition.head - assemblyState.lgmState.currentPosition
            val slotIndex      = LgmInfo.gratingLinearDistance.indexWhere(d => math.abs(d - slotLinearDist) <= 0.01)
            val bgid           = if (slotIndex >= 0) LgmInfo.indexToBgid(slotIndex) else ""
            if (bgid.nonEmpty) {
              log.info(s" Te Assembly [pactPushPullEvent/push]: bgid=$bgid – publishing gratingTransferEvent(push)")
              publishGratingTransferEvent("push", bgid)
            }
            else {
              log.error(" Te Assembly [pactPushPullEvent/push]: could not determine bgid from lgm position")
            }

          case "pull" =>
            val bgid = assemblyState.rgripState.gratingId.getOrElse("")
            if (bgid.nonEmpty) {
              log.info(s" Te Assembly [pactPushPullEvent/pull]: bgid=$bgid – publishing gratingTransferEvent(pull)")
              publishGratingTransferEvent("pull", bgid)
            }
            else {
              log.error(" Te Assembly [pactPushPullEvent/pull]: rgripState.gratingId is empty")
            }

          case other =>
            log.warn(s" Te Assembly [pactPushPullEvent]: unknown operation '$other'")
        }
      case _ =>
    }
  }
} //telementry manager class closing
