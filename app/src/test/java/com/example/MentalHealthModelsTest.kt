package com.example

import com.example.eye.BlinkAnalyzer
import com.example.eye.EyeMetrics
import com.example.eye.EyeSample
import com.example.eye.EyeTrackingStatus
import com.example.hud.HudEvent
import com.example.hud.HudViewModel
import com.example.mental.CognitiveLevel
import com.example.mental.CognitiveLoadInputs
import com.example.mental.CognitiveLoadModel
import com.example.mental.EmergencyDrill
import com.example.radiation.RadiationModel
import com.example.radiation.RadiationStatus
import com.example.radiation.ShelterStatus
import com.example.suit.SuitTelemetry
import com.example.suit.SuitTelemetryModel
import com.example.voice.MicCalibrator
import com.example.voice.VoiceCommand
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MentalHealthModelsTest {

  /** Feeds 30 fps of synthetic eyelid data with a blink every [periodMs] lasting [blinkMs]. */
  private fun simulateEyes(
    analyzer: BlinkAnalyzer,
    startMs: Long,
    durationMs: Long,
    periodMs: Long,
    blinkMs: Long,
    sink: (EyeSample) -> Unit = analyzer::process,
  ): Long {
    var t = startMs
    while (t < startMs + durationMs) {
      val phase = (t - startMs) % periodMs
      val closure = if (phase < blinkMs) 0.95f else 0.05f
      sink(EyeSample(t, true, closure, closure, gazeX = 0.02f, gazeY = -0.01f))
      t += 33L
    }
    return t
  }

  // ------------------------------------------------------------ Blinks

  @Test
  fun blinkAnalyzer_countsBlinksRateDurationAndLearnsBaseline() {
    val a = BlinkAnalyzer()
    // ~17 blinks/min (period 3.53 s), 150 ms blinks, for 2.5 minutes.
    val end = simulateEyes(a, 0L, 150_000L, 3_530L, 150L)
    val m = a.metrics()
    assertTrue("counted ${m.totalBlinks}", m.totalBlinks in 41..44)
    assertEquals(17f, m.blinkRatePerMin ?: 0f, 1.5f)
    assertEquals(150f, m.meanBlinkDurationMs ?: 0f, 40f)
    assertEquals("baseline learned after 2 min", 17f, m.baselineBlinkRate ?: 0f, 1.5f)
    assertEquals(1f, m.calibrationProgress, 0.001f)
    assertTrue("PERCLOS stays low for normal blinks", (m.perclos ?: 1f) < 0.08f)
    assertTrue(m.isReliable)
    assertTrue(end > 150_000L)
  }

  @Test
  fun blinkAnalyzer_ignoresFaceLossAndTooShortOrTooLongClosures() {
    val a = BlinkAnalyzer()
    a.process(EyeSample(0, true, 0.9f, 0.9f))
    a.process(EyeSample(20, true, 0.1f, 0.1f)) // 20 ms -> noise, not a blink
    a.process(EyeSample(100, true, 0.9f, 0.9f))
    a.process(EyeSample(2_000, true, 0.1f, 0.1f)) // 1.9 s -> closure, not a blink
    a.process(EyeSample(2_100, true, 0.9f, 0.9f))
    a.process(EyeSample(2_200, false)) // face lost mid-blink
    a.process(EyeSample(2_300, true, 0.1f, 0.1f))
    assertEquals(0, a.metrics().totalBlinks)
    assertNull(a.metrics().blinkRatePerMin)
  }

  // ------------------------------------------------------------ Cognitive load

  private fun inputs(
    eye: EyeMetrics? = null,
    hr: Double? = 86.0,
    stress: Int? = 12,
    co2: Double = 1.8,
    temp: Double = 37.1,
    sleep: Int = 88,
  ) = CognitiveLoadInputs(eye, hr, 86.0, stress, co2, temp, sleep)

  @Test
  fun cognitiveLevels_matchRequestedThresholds() {
    assertEquals(CognitiveLevel.NOMINAL, CognitiveLoadModel.levelFor(69))
    assertEquals(CognitiveLevel.CAUTION, CognitiveLoadModel.levelFor(70))
    assertEquals(CognitiveLevel.CAUTION, CognitiveLoadModel.levelFor(89))
    assertEquals(CognitiveLevel.WARNING, CognitiveLoadModel.levelFor(90))
    assertEquals(CognitiveLevel.WARNING, CognitiveLoadModel.levelFor(94))
    assertEquals(CognitiveLevel.EMERGENCY, CognitiveLoadModel.levelFor(95))
  }

  @Test
  fun cognitiveLoad_nominalIsLow_eyeSuppressionRaisesIt_andRecoveryDecaysSlowly() {
    val model = CognitiveLoadModel()
    val calm = model.evaluate(0L, inputs())
    assertEquals(CognitiveLevel.NOMINAL, calm.level)
    assertTrue("calm load ${calm.index}", calm.index < 20)
    assertFalse(calm.usesEyeData)

    val suppressed =
      EyeMetrics(faceDetected = true, blinkRatePerMin = 6.8f, baselineBlinkRate = 17f, faceCoverage = 0.9f, perclos = 0.02f, meanBlinkDurationMs = 120f)
    val busy = CognitiveLoadModel().evaluate(0L, inputs(eye = suppressed))
    assertTrue(busy.usesEyeData)
    assertEquals("BLINK SUPPRESSION", busy.ocularPattern)
    assertEquals(100, busy.ocular)
    assertTrue("eye load alone stays below caution: ${busy.index}", busy.index in 40..69)

    val drowsy = EyeMetrics(faceDetected = true, blinkRatePerMin = 24f, baselineBlinkRate = 17f, faceCoverage = 0.9f, perclos = 0.18f, meanBlinkDurationMs = 420f)
    val fatigue = CognitiveLoadModel().evaluate(0L, inputs(eye = drowsy, sleep = 55))
    assertEquals("FATIGUE", fatigue.ocularPattern)
    assertEquals("EYE FATIGUE", fatigue.topDriver)

    // Crisis rises immediately, then decays gradually instead of snapping back.
    val crisis = model.evaluate(1_000L, inputs(hr = 140.0, stress = 99, co2 = 7.6, temp = 38.2, sleep = 48))
    assertEquals(CognitiveLevel.EMERGENCY, crisis.level)
    val recovering = model.evaluate(2_000L, inputs())
    assertTrue("decays slowly: ${recovering.index}", recovering.index in 70 until crisis.index)
  }

  @Test
  fun viewModel_drillsDriveCognitiveLevels_andEmergencySpeaksPulseEveryCycleUntilAcknowledged() = runTest {
    val vm = HudViewModel(enablePeriodicDecay = false)
    val spoken = mutableListOf<String>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { if (it is HudEvent.Speak) spoken.add(it.text) } }

    assertEquals(CognitiveLevel.NOMINAL, vm.uiState.value.cognitive.level)

    vm.onVoiceCommand(VoiceCommand.RunEmergency(10))
    val s = vm.uiState.value
    assertEquals(CognitiveLevel.EMERGENCY, s.cognitive.level)
    assertTrue(s.cognitive.index >= 95)
    assertTrue("spoke: $spoken", spoken.any { it.startsWith("Emergency") && it.contains("Pulse ${s.heartRateBpm} beats per minute") })

    vm.tick(100_000L)
    val count = spoken.count { it.startsWith("Emergency") }
    vm.tick(110_000L)
    assertEquals("not repeated within the cycle", count, spoken.count { it.startsWith("Emergency") })
    vm.tick(100_000L + HudViewModel.ALERT_CYCLE_MS)
    assertEquals("repeated every cycle", count + 1, spoken.count { it.startsWith("Emergency") })

    vm.acknowledgeAlerts(125_000L)
    vm.tick(125_000L + HudViewModel.ALERT_CYCLE_MS)
    assertEquals("silenced after acknowledge", count + 1, spoken.count { it.startsWith("Emergency") })
    assertTrue(vm.uiState.value.alertAcknowledged)

    vm.onVoiceCommand(VoiceCommand.RunEmergency(6))
    assertTrue(vm.uiState.value.cognitive.level.ordinal >= CognitiveLevel.CAUTION.ordinal)
    job.cancel()
  }

  @Test
  fun viewModel_usesEyeDataOnlyWhenTrackingActive() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onEyeTrackingStatus(EyeTrackingStatus.STARTING)
    // 6 blinks/min sustained -> suppression.
    simulateEyes(BlinkAnalyzer(), 0L, 70_000L, 10_000L, 150L, sink = vm::onEyeSample)
    assertEquals(EyeTrackingStatus.ACTIVE, vm.uiState.value.eyeTrackingStatus)
    vm.stepMentalVitals(200_000L)
    val c = vm.uiState.value.cognitive
    assertTrue(c.usesEyeData)
    assertNotNull(c.ocular)
    assertTrue("ocular ${c.ocular}", (c.ocular ?: 0) >= 80)
    assertTrue(vm.uiState.value.eye.totalBlinks >= 6)
  }

  // ------------------------------------------------------------ Radiation

  @Test
  fun radiation_baselineMatchesCuriosityRad_integratesDose_andGuidesToBase() {
    val m = RadiationModel()
    val atBase = m.step(3600.0, 0f, 0f, 0f, 1f, tSec = 0.0)
    assertEquals(ShelterStatus.SHELTERED, atBase.shelter)
    assertEquals(27.9f * 0.4f, atBase.doseRateUsvPerHour, 0.2f)

    val m2 = RadiationModel()
    val open = m2.step(3600.0, 100f, 0f, 0f, 1f, tSec = 0.0)
    assertEquals(27.9f, open.doseRateUsvPerHour, 0.2f)
    assertEquals(27.9f, open.evaDoseUsv, 0.3f)
    assertEquals("base is due west", 270f, open.bearingToBaseDeg, 0.5f)
    assertEquals(100, open.etaToBaseSec)
    assertEquals(RadiationStatus.NOMINAL, open.status)

    val storm = m2.step(1.0, 0f, -200f, 0f, EmergencyDrill.EMERGENCY_3.doseRateMultiplier, tSec = 0.0)
    assertEquals(RadiationStatus.STORM, storm.status)
    assertEquals("base is due north", 0f, storm.bearingToBaseDeg, 0.5f)
    assertTrue(storm.advice.contains("Return to base"))
  }

  // ------------------------------------------------------------ Suit (NASA SUITS model)

  @Test
  fun suitTelemetry_followsSuitsFormulasAndDrillOverrides() {
    val m = SuitTelemetryModel()
    val s = m.step(1.0, 120.0, EmergencyDrill.NONE, 0.0)
    assertEquals(0.1 + 0.0001 * 120, s.oxyConsumptionPsiMin.toDouble(), 1e-4)
    assertEquals(0.11 + 0.0002 * 120, s.co2ProductionPsiMin.toDouble(), 1e-4)
    assertEquals(s.oxyPriStoragePct * 30f, s.oxyPriPressurePsi, 0.01f)
    assertEquals(s.suitPressureOxyPsi + s.suitPressureCo2Psi + s.suitPressureOtherPsi, s.suitPressureTotalPsi, 0.001f)
    assertEquals(s.suitPressureCo2Psi * 1.5f, s.helmetPressureCo2Psi, 0.0001f)
    assertTrue(s.isPressureNominal)
    assertTrue(s.oxyTimeLeftSec > 3600)

    val scrubberFault = m.step(0.0, 108.0, EmergencyDrill.EMERGENCY_4, 0.0)
    assertEquals(8.4f, scrubberFault.helmetCo2MmHg, 0.01f)
    assertTrue(scrubberFault.isFanFault)

    val decomp = m.step(0.0, 138.0, EmergencyDrill.EMERGENCY_10, 0.0)
    assertEquals(3.1f, decomp.suitPressureTotalPsi, 0.001f)
    assertFalse(decomp.isPressureNominal)
    assertTrue(decomp.isOxygenLow)
    assertTrue(decomp.isLowPower)
    assertEquals(51.715f, SuitTelemetry.PSI_TO_MMHG, 0.001f)
  }

  // ------------------------------------------------------------ Microphone calibration

  private fun frame(amplitude: Double, n: Int = 320, freq: Double = 440.0): ShortArray =
    ShortArray(n) { i -> (amplitude * sin(2 * PI * freq * i / 16000.0)).toInt().toShort() }

  @Test
  fun micCalibrator_learnsNoiseFloor_gatesSpeech_andAppliesGain() {
    val cal = MicCalibrator(sensitivity = 3)
    var t = 0L
    val noise = frame(60.0) // ~ -58 dBFS
    while (cal.calibrating) {
      cal.process(noise, noise.size, t)
      t += 20
    }
    assertEquals(-57.7f, cal.noiseFloorDb, 1.5f)
    assertEquals(cal.noiseFloorDb + 9f, cal.gateDb, 0.01f)

    // Noise alone never opens the gate.
    repeat(50) {
      assertFalse(cal.process(noise, noise.size, t).gateOpen)
      t += 20
    }
    val speech = frame(1600.0) // ~ -29 dBFS, quiet talker
    assertEquals(MicCalibrator.Event.NONE, cal.process(speech, speech.size, t).event)
    t += 20
    assertEquals(MicCalibrator.Event.SPEECH_START, cal.process(speech, speech.size, t).event)
    repeat(20) {
      cal.process(speech, speech.size, t)
      t += 20
    }
    var end: MicCalibrator.Decision? = null
    repeat(40) {
      val d = cal.process(noise, noise.size, t)
      t += 20
      if (d.event == MicCalibrator.Event.SPEECH_END) end = d
    }
    assertNotNull("gate closes after hangover", end)
    assertTrue("quiet speech gets boosted: ${cal.currentGain()}", cal.currentGain() > 3f)

    assertTrue(MicCalibrator.marginDb(1) > MicCalibrator.marginDb(5))
    cal.recalibrate()
    assertTrue(cal.calibrating)
  }
}
