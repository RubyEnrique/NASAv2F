package com.example

import com.example.gesture.DashboardMode
import com.example.gesture.HandGestureClassifier
import com.example.gesture.HandLandmarkPoint
import com.example.hud.HudEvent
import com.example.hud.HudViewModel
import com.example.sensor.GpsDistanceMonitor
import com.example.sensor.HeadingSensorMonitor
import com.example.voice.VoiceCommand
import com.example.voice.VoiceCommandRecognizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandGestureClassifierTest {

  private fun createOpenPalmUp(offsetX: Float = 0f, offsetY: Float = 0f): List<HandLandmarkPoint> {
    return listOf(
      HandLandmarkPoint(0.50f + offsetX, 0.70f + offsetY), // 0 wrist
      HandLandmarkPoint(0.44f + offsetX, 0.65f + offsetY), // 1 thumb CMC
      HandLandmarkPoint(0.40f + offsetX, 0.58f + offsetY), // 2 thumb MCP
      HandLandmarkPoint(0.36f + offsetX, 0.52f + offsetY), // 3 thumb IP
      HandLandmarkPoint(0.33f + offsetX, 0.46f + offsetY), // 4 thumb TIP
      HandLandmarkPoint(0.44f + offsetX, 0.52f + offsetY), // 5 index MCP
      HandLandmarkPoint(0.43f + offsetX, 0.42f + offsetY), // 6 index PIP
      HandLandmarkPoint(0.42f + offsetX, 0.35f + offsetY), // 7 index DIP
      HandLandmarkPoint(0.42f + offsetX, 0.28f + offsetY), // 8 index TIP
      HandLandmarkPoint(0.50f + offsetX, 0.50f + offsetY), // 9 middle MCP
      HandLandmarkPoint(0.50f + offsetX, 0.39f + offsetY), // 10 middle PIP
      HandLandmarkPoint(0.50f + offsetX, 0.31f + offsetY), // 11 middle DIP
      HandLandmarkPoint(0.50f + offsetX, 0.24f + offsetY), // 12 middle TIP
      HandLandmarkPoint(0.56f + offsetX, 0.52f + offsetY), // 13 ring MCP
      HandLandmarkPoint(0.57f + offsetX, 0.41f + offsetY), // 14 ring PIP
      HandLandmarkPoint(0.58f + offsetX, 0.34f + offsetY), // 15 ring DIP
      HandLandmarkPoint(0.59f + offsetX, 0.27f + offsetY), // 16 ring TIP
      HandLandmarkPoint(0.62f + offsetX, 0.56f + offsetY), // 17 pinky MCP
      HandLandmarkPoint(0.64f + offsetX, 0.47f + offsetY), // 18 pinky PIP
      HandLandmarkPoint(0.66f + offsetX, 0.41f + offsetY), // 19 pinky DIP
      HandLandmarkPoint(0.67f + offsetX, 0.35f + offsetY), // 20 pinky TIP
    )
  }

  private fun createClosedFist(offsetX: Float = 0f, offsetY: Float = 0f): List<HandLandmarkPoint> {
    return listOf(
      HandLandmarkPoint(0.50f + offsetX, 0.70f + offsetY),
      HandLandmarkPoint(0.45f + offsetX, 0.65f + offsetY),
      HandLandmarkPoint(0.42f + offsetX, 0.59f + offsetY),
      HandLandmarkPoint(0.45f + offsetX, 0.55f + offsetY),
      HandLandmarkPoint(0.49f + offsetX, 0.55f + offsetY),
      HandLandmarkPoint(0.44f + offsetX, 0.52f + offsetY),
      HandLandmarkPoint(0.44f + offsetX, 0.48f + offsetY),
      HandLandmarkPoint(0.45f + offsetX, 0.54f + offsetY),
      HandLandmarkPoint(0.46f + offsetX, 0.57f + offsetY),
      HandLandmarkPoint(0.50f + offsetX, 0.50f + offsetY),
      HandLandmarkPoint(0.50f + offsetX, 0.46f + offsetY),
      HandLandmarkPoint(0.50f + offsetX, 0.53f + offsetY),
      HandLandmarkPoint(0.50f + offsetX, 0.56f + offsetY),
      HandLandmarkPoint(0.56f + offsetX, 0.52f + offsetY),
      HandLandmarkPoint(0.56f + offsetX, 0.48f + offsetY),
      HandLandmarkPoint(0.55f + offsetX, 0.54f + offsetY),
      HandLandmarkPoint(0.55f + offsetX, 0.57f + offsetY),
      HandLandmarkPoint(0.61f + offsetX, 0.56f + offsetY),
      HandLandmarkPoint(0.61f + offsetX, 0.52f + offsetY),
      HandLandmarkPoint(0.60f + offsetX, 0.57f + offsetY),
      HandLandmarkPoint(0.59f + offsetX, 0.59f + offsetY),
    )
  }

  @Test
  fun onePalmHeldFor800Ms_opensQuickHold_andDropsAfter150Ms() {
    val classifier = HandGestureClassifier()
    val palm = createOpenPalmUp()

    val u0 = classifier.processFrames(listOf(palm), 1000L, DashboardMode.CLOSED)
    assertEquals(0f, u0.palmHoldProgress, 0.01f)
    assertNull(u0.openMode)

    val uDone = classifier.processFrames(listOf(palm), 1800L, DashboardMode.CLOSED)
    assertEquals(DashboardMode.QUICK_HOLD, uDone.openMode)

    val held = classifier.processFrames(listOf(palm), 2100L, DashboardMode.QUICK_HOLD)
    assertFalse(held.shouldCloseDashboard)

    val briefFlicker = classifier.processFrames(emptyList(), 2200L, DashboardMode.QUICK_HOLD)
    assertFalse(briefFlicker.shouldCloseDashboard)

    val lowered = classifier.processFrames(emptyList(), 2300L, DashboardMode.QUICK_HOLD)
    assertTrue(lowered.shouldCloseDashboard)
  }

  @Test
  fun twoPalmsHeldFor800Ms_opensPermanent_andStaysOpenWhenHandsLeave() {
    val classifier = HandGestureClassifier()
    val leftPalm = createOpenPalmUp(offsetX = -0.20f)
    val rightPalm = createOpenPalmUp(offsetX = 0.20f)

    classifier.processFrames(listOf(leftPalm, rightPalm), 1000L, DashboardMode.CLOSED)
    val mid = classifier.processFrames(listOf(leftPalm, rightPalm), 1400L, DashboardMode.CLOSED)
    assertEquals(0.5f, mid.palmHoldProgress, 0.02f)

    val opened = classifier.processFrames(listOf(leftPalm, rightPalm), 1800L, DashboardMode.CLOSED)
    assertEquals(DashboardMode.PERMANENT, opened.openMode)

    // Moving or removing hands keeps PERMANENT dashboard open.
    val handsGone = classifier.processFrames(emptyList(), 3500L, DashboardMode.PERMANENT)
    assertFalse(handsGone.shouldCloseDashboard)
    assertFalse(handsGone.isTwoFistsReady)
  }

  @Test
  fun twoFistsLoweredTogether_armsDualCueAndClosesPermanentDashboard() {
    val classifier = HandGestureClassifier()
    val leftFistHigh = createClosedFist(offsetX = -0.20f, offsetY = -0.20f)
    val rightFistHigh = createClosedFist(offsetX = 0.20f, offsetY = -0.20f)

    // Stationary 2 fists illuminate dual-ring cue without closing yet
    val armed = classifier.processFrames(listOf(leftFistHigh, rightFistHigh), 2000L, DashboardMode.PERMANENT)
    assertTrue(armed.isTwoFistsReady)
    assertFalse(armed.shouldCloseDashboard)

    val stillArmed = classifier.processFrames(listOf(leftFistHigh, rightFistHigh), 2150L, DashboardMode.PERMANENT)
    assertTrue(stillArmed.isTwoFistsReady)
    assertFalse(stillArmed.shouldCloseDashboard)

    // Lowering only 1 fist does NOT close permanent dashboard
    val leftFistLow = createClosedFist(offsetX = -0.20f, offsetY = -0.02f)
    val oneLowered = classifier.processFrames(listOf(leftFistLow, rightFistHigh), 2250L, DashboardMode.PERMANENT)
    assertFalse(oneLowered.shouldCloseDashboard)

    // Lowering both fists together closes permanent dashboard
    val rightFistLow = createClosedFist(offsetX = 0.20f, offsetY = -0.02f)
    val bothLowered = classifier.processFrames(listOf(leftFistLow, rightFistLow), 2320L, DashboardMode.PERMANENT)
    assertTrue(bothLowered.shouldCloseDashboard)
    assertFalse(bothLowered.isTwoFistsReady)
  }

  @Test
  fun twoFistsStaggeredExit_stillTracksBothDescentsAndClosesPermanentDashboard() {
    val classifier = HandGestureClassifier()
    val leftFistHigh = createClosedFist(offsetX = -0.20f, offsetY = -0.15f)
    val rightFistHigh = createClosedFist(offsetX = 0.20f, offsetY = -0.15f)

    val armed = classifier.processFrames(listOf(leftFistHigh, rightFistHigh), 2000L, DashboardMode.PERMANENT)
    assertTrue(armed.isTwoFistsReady)
    assertFalse(armed.shouldCloseDashboard)

    // Left fist drops out of frame first; right fist stays visible then drops too
    val leftFistLow = createClosedFist(offsetX = -0.20f, offsetY = -0.02f)
    val midDescent = classifier.processFrames(listOf(leftFistLow, rightFistHigh), 2150L, DashboardMode.PERMANENT)
    assertFalse(midDescent.shouldCloseDashboard)
    assertTrue(midDescent.isTwoFistsReady)

    // Only right fist remains visible as left fist exited frame; right fist descends -> closes cleanly
    val rightFistLow = createClosedFist(offsetX = 0.20f, offsetY = -0.02f)
    val closed = classifier.processFrames(listOf(rightFistLow), 2280L, DashboardMode.PERMANENT)
    assertTrue(closed.shouldCloseDashboard)
    assertFalse(closed.isTwoFistsReady)
  }

  @Test
  fun voiceCommands_parseAndDriveDashboardModes() {
    assertEquals(VoiceCommand.TOGGLE_QUICK, VoiceCommandRecognizer.parseVoiceCommand("Dashboard"))
    assertEquals(
      VoiceCommand.OPEN_PERMANENT,
      VoiceCommandRecognizer.parseVoiceCommand("dashboard-open"),
    )
    assertEquals(
      VoiceCommand.OPEN_PERMANENT,
      VoiceCommandRecognizer.parseVoiceCommand("dashboard open"),
    )
    assertEquals(
      VoiceCommand.CLOSE_PERMANENT,
      VoiceCommandRecognizer.parseVoiceCommand("dashboard-close"),
    )
    assertEquals(
      VoiceCommand.CLOSE_PERMANENT,
      VoiceCommandRecognizer.parseVoiceCommand("dashboard close"),
    )
    assertNull(VoiceCommandRecognizer.parseVoiceCommand("hello world"))

    assertEquals(
      "dashboard open",
      VoiceCommandRecognizer.extractTranscript("{\"partial\" : \"dashboard open\"}", "partial"),
    )
    assertEquals(
      "dashboard close",
      VoiceCommandRecognizer.extractTranscript("{\"text\" : \"dashboard close\"}", "text"),
    )

    val vm = HudViewModel(enablePeriodicDecay = false)
    assertEquals(DashboardMode.CLOSED, vm.uiState.value.dashboardMode)

    vm.onVoiceCommand(VoiceCommand.TOGGLE_QUICK)
    assertEquals(DashboardMode.QUICK_VOICE, vm.uiState.value.dashboardMode)

    vm.onVoiceCommand(VoiceCommand.TOGGLE_QUICK)
    assertEquals(DashboardMode.CLOSED, vm.uiState.value.dashboardMode)

    vm.onVoiceCommand(VoiceCommand.OPEN_PERMANENT)
    assertEquals(DashboardMode.PERMANENT, vm.uiState.value.dashboardMode)

    vm.onVoiceCommand(VoiceCommand.CLOSE_PERMANENT)
    assertEquals(DashboardMode.CLOSED, vm.uiState.value.dashboardMode)
  }

  @Test
  fun gpsDistanceTracking_filtersJitterComputesSpeedAndReactiveHeartRate() {
    // Low accuracy fix rejected
    val lowAcc = GpsDistanceMonitor.filterLocationDelta(12f, 2f, 40f, 2f)
    assertFalse(lowAcc.acceptedAsAnchor)
    assertEquals(0f, lowAcc.deltaMeters, 0.01f)

    // Small stationary jitter rejected
    val jitter = GpsDistanceMonitor.filterLocationDelta(1.8f, 1.5f, 8f, 0.1f)
    assertFalse(jitter.acceptedAsAnchor)
    assertEquals(0f, jitter.deltaMeters, 0.01f)

    // Real movement accepted
    val moving = GpsDistanceMonitor.filterLocationDelta(6.0f, 2.0f, 6f, 2.4f)
    assertTrue(moving.acceptedAsAnchor)
    assertEquals(6.0f, moving.deltaMeters, 0.01f)
    assertEquals(2.4f, moving.speedMps, 0.01f)

    val vm = HudViewModel(enablePeriodicDecay = false)
    assertEquals(0f, vm.uiState.value.distanceMeters, 0.01f)
    assertEquals(0f, vm.uiState.value.speedMps, 0.01f)
    assertEquals(86, vm.uiState.value.heartRateBpm)

    // Simulate brisk movement at 2.4 m/s over several GPS updates
    var ts = 10_000L
    repeat(6) {
      vm.onLocationSample(deltaMeters = 4.8f, measuredSpeedMps = 2.4f, timestampMs = ts)
      ts += 2_000L
    }

    assertEquals(28.8f, vm.uiState.value.distanceMeters, 0.1f)
    assertTrue("Expected positive smoothed GPS speed", vm.uiState.value.speedMps > 2.0f)
    assertTrue("Expected HR to climb above 86 BPM baseline", vm.uiState.value.heartRateBpm > 100)

    // After prolonged stationary period, speed decays to 0 and HR decays toward 86 BPM
    val activeHr = vm.uiState.value.heartRateBpm
    repeat(12) { idx ->
      vm.refreshLocationDerivedMetrics(nowMs = ts + 6_000L + idx * 1_500L)
    }
    assertEquals(0f, vm.uiState.value.speedMps, 0.01f)
    assertTrue("Expected HR to settle down when stationary", vm.uiState.value.heartRateBpm < activeHr)
  }

  @Test
  fun cameraHeadingDegrees_projectsRearCameraAxisCorrectly() {
    val northR = floatArrayOf(0f, 1f, 0f, 0f, 0f, -1f, -1f, 0f, 0f)
    assertEquals(0f, HeadingSensorMonitor.computeCameraHeadingDegrees(northR), 0.1f)

    val eastR = floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f)
    assertEquals(90f, HeadingSensorMonitor.computeCameraHeadingDegrees(eastR), 0.1f)

    val westR = floatArrayOf(0f, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f)
    assertEquals(270f, HeadingSensorMonitor.computeCameraHeadingDegrees(westR), 0.1f)
  }

  @Test
  fun invisibleExitTap_requiresFiveTapsWithinTwoSeconds() = runTest {
    val viewModel = HudViewModel(enablePeriodicDecay = false)
    val emitted = mutableListOf<HudEvent>()
    val collectJob =
      launch(UnconfinedTestDispatcher(testScheduler)) {
        viewModel.events.collect { emitted.add(it) }
      }

    viewModel.onInvisibleExitZoneTap(1000L)
    viewModel.onInvisibleExitZoneTap(3500L)
    viewModel.onInvisibleExitZoneTap(3600L)
    viewModel.onInvisibleExitZoneTap(3700L)
    viewModel.onInvisibleExitZoneTap(3800L)
    assertTrue(emitted.isEmpty())

    viewModel.onInvisibleExitZoneTap(3900L)
    assertEquals(listOf(HudEvent.ExitApp), emitted)

    collectJob.cancel()
  }
}
