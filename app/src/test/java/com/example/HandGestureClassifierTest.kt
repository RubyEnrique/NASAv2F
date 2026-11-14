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
  fun onePalmHeldFor800Ms_opensQuickHold_andDropsWhenPalmLowered() {
    val classifier = HandGestureClassifier()
    val palmCenter = createOpenPalmUp()

    val u0 = classifier.processFrames(listOf(palmCenter), 1000L, DashboardMode.CLOSED)
    assertEquals(0f, u0.palmHoldProgress, 0.01f)
    assertNull(u0.openMode)

    val uMid = classifier.processFrames(listOf(palmCenter), 1400L, DashboardMode.CLOSED)
    assertEquals(0.5f, uMid.palmHoldProgress, 0.02f)
    assertNull(uMid.openMode)

    val uDone = classifier.processFrames(listOf(palmCenter), 1800L, DashboardMode.CLOSED)
    assertEquals(DashboardMode.QUICK_HOLD, uDone.openMode)

    // Stays open while hand remains raised
    val held = classifier.processFrames(listOf(palmCenter), 2300L, DashboardMode.QUICK_HOLD)
    assertFalse(held.shouldCloseDashboard)

    // Brief frame flicker (<150ms) does not close; drops cleanly after 150ms lowered
    val briefFlicker = classifier.processFrames(emptyList(), 2400L, DashboardMode.QUICK_HOLD)
    assertFalse(briefFlicker.shouldCloseDashboard)

    val lowered = classifier.processFrames(emptyList(), 2500L, DashboardMode.QUICK_HOLD)
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

  private fun createTiltedOpenHand(offsetX: Float = 0f, offsetY: Float = 0f): List<HandLandmarkPoint> {
    // Hand tilted sideways ~45 deg where pinky/index fail strict upright tip.y < mcp.y check.
    return listOf(
      HandLandmarkPoint(0.50f + offsetX, 0.70f + offsetY), // 0 wrist
      HandLandmarkPoint(0.46f + offsetX, 0.65f + offsetY), // 1 thumb CMC
      HandLandmarkPoint(0.44f + offsetX, 0.59f + offsetY), // 2 thumb MCP
      HandLandmarkPoint(0.43f + offsetX, 0.55f + offsetY), // 3 thumb IP
      HandLandmarkPoint(0.42f + offsetX, 0.52f + offsetY), // 4 thumb TIP
      HandLandmarkPoint(0.56f + offsetX, 0.58f + offsetY), // 5 index MCP
      HandLandmarkPoint(0.62f + offsetX, 0.56f + offsetY), // 6 index PIP
      HandLandmarkPoint(0.66f + offsetX, 0.55f + offsetY), // 7 index DIP
      HandLandmarkPoint(0.70f + offsetX, 0.54f + offsetY), // 8 index TIP
      HandLandmarkPoint(0.57f + offsetX, 0.62f + offsetY), // 9 middle MCP
      HandLandmarkPoint(0.64f + offsetX, 0.61f + offsetY), // 10 middle PIP
      HandLandmarkPoint(0.69f + offsetX, 0.61f + offsetY), // 11 middle DIP
      HandLandmarkPoint(0.74f + offsetX, 0.62f + offsetY), // 12 middle TIP (y == mcp.y)
      HandLandmarkPoint(0.56f + offsetX, 0.67f + offsetY), // 13 ring MCP
      HandLandmarkPoint(0.62f + offsetX, 0.68f + offsetY), // 14 ring PIP
      HandLandmarkPoint(0.66f + offsetX, 0.69f + offsetY), // 15 ring DIP
      HandLandmarkPoint(0.70f + offsetX, 0.70f + offsetY), // 16 ring TIP (y > mcp.y)
      HandLandmarkPoint(0.54f + offsetX, 0.71f + offsetY), // 17 pinky MCP
      HandLandmarkPoint(0.59f + offsetX, 0.73f + offsetY), // 18 pinky PIP
      HandLandmarkPoint(0.62f + offsetX, 0.75f + offsetY), // 19 pinky DIP
      HandLandmarkPoint(0.65f + offsetX, 0.77f + offsetY), // 20 pinky TIP (y > mcp.y)
    )
  }

  @Test
  fun permanentDashboard_openHandSwipeNavigatesBetweenVitalsMapAndSuitStatus() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.OPEN_PERMANENT)
    assertEquals(DashboardMode.PERMANENT, vm.uiState.value.dashboardMode)
    assertEquals(com.example.gesture.DashboardPage.MAP, vm.uiState.value.currentPage)

    // Swipe hand left (decreasing X) -> move left to Vitals (even with a tilted hand and 2nd resting fist)
    val restingSecondHand = createClosedFist(offsetX = -0.35f, offsetY = 0.15f)
    var t = 1000L
    vm.onHandsDetected(listOf(createTiltedOpenHand(offsetX = 0.12f), restingSecondHand), t)
    t += 120L
    vm.onHandsDetected(listOf(createTiltedOpenHand(offsetX = 0.04f), restingSecondHand), t)
    t += 120L
    vm.onHandsDetected(listOf(createTiltedOpenHand(offsetX = -0.04f), restingSecondHand), t)
    assertEquals(com.example.gesture.DashboardPage.VITALS, vm.uiState.value.currentPage)
    assertEquals(com.example.gesture.SwipeDirection.PREVIOUS, vm.uiState.value.lastSwipeDirection)
    assertEquals(1L, vm.uiState.value.swipeCueId)

    // Cooldown prevents accidental double trigger immediately
    t += 150L
    vm.onHandsDetected(listOf(createTiltedOpenHand(offsetX = 0.25f)), t)
    assertEquals(com.example.gesture.DashboardPage.VITALS, vm.uiState.value.currentPage)

    // After cooldown, swipe hand right (increasing X) -> Vitals -> Map -> Suit Status
    t += 700L
    vm.onHandsDetected(listOf(createTiltedOpenHand(offsetX = -0.10f)), t)
    t += 120L
    vm.onHandsDetected(listOf(createTiltedOpenHand(offsetX = 0.06f)), t)
    assertEquals(com.example.gesture.DashboardPage.MAP, vm.uiState.value.currentPage)
    assertEquals(com.example.gesture.SwipeDirection.NEXT, vm.uiState.value.lastSwipeDirection)

    t += 700L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = -0.08f)), t)
    t += 120L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = 0.08f)), t)
    assertEquals(com.example.gesture.DashboardPage.SUIT_STATUS, vm.uiState.value.currentPage)

    // Swiping right at Suit Status moves to Cognitive -> Drills -> clamps cleanly at Drills
    t += 700L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = -0.08f)), t)
    t += 120L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = 0.08f)), t)
    assertEquals(com.example.gesture.DashboardPage.COGNITIVE, vm.uiState.value.currentPage)

    t += 700L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = -0.08f)), t)
    t += 120L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = 0.08f)), t)
    assertEquals(com.example.gesture.DashboardPage.DRILLS, vm.uiState.value.currentPage)

    t += 700L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = -0.08f)), t)
    t += 120L
    vm.onHandsDetected(listOf(createOpenPalmUp(offsetX = 0.08f)), t)
    assertEquals(com.example.gesture.DashboardPage.DRILLS, vm.uiState.value.currentPage)
  }

  @Test
  fun voicePageCommands_switchPagesImmediatelyWhenDashboardOpen() {
    assertEquals(VoiceCommand.NAVIGATE_VITALS, VoiceCommandRecognizer.parseVoiceCommand("vitals"))
    assertEquals(VoiceCommand.NAVIGATE_MAP, VoiceCommandRecognizer.parseVoiceCommand("map"))
    assertEquals(VoiceCommand.NAVIGATE_MAP, VoiceCommandRecognizer.parseVoiceCommand("Meter"))
    assertEquals(VoiceCommand.NAVIGATE_SUIT, VoiceCommandRecognizer.parseVoiceCommand("suit"))
    assertEquals(VoiceCommand.NAVIGATE_SUIT, VoiceCommandRecognizer.parseVoiceCommand("suit status"))
    assertEquals(VoiceCommand.NAVIGATE_COGNITIVE, VoiceCommandRecognizer.parseVoiceCommand("cognitive"))
    assertEquals(VoiceCommand.NAVIGATE_COGNITIVE, VoiceCommandRecognizer.parseVoiceCommand("readiness"))

    val vm = HudViewModel(enablePeriodicDecay = false)
    // Ignored when dashboard is closed
    vm.onVoiceCommand(VoiceCommand.NAVIGATE_VITALS)
    assertEquals(com.example.gesture.DashboardPage.MAP, vm.uiState.value.currentPage)

    // Works anytime dashboard is open (quick voice or permanent)
    vm.onVoiceCommand(VoiceCommand.TOGGLE_QUICK)
    assertEquals(DashboardMode.QUICK_VOICE, vm.uiState.value.dashboardMode)

    vm.onVoiceCommand(VoiceCommand.NAVIGATE_VITALS)
    assertEquals(com.example.gesture.DashboardPage.VITALS, vm.uiState.value.currentPage)

    vm.onVoiceCommand(VoiceCommand.NAVIGATE_SUIT)
    assertEquals(com.example.gesture.DashboardPage.SUIT_STATUS, vm.uiState.value.currentPage)

    vm.onVoiceCommand(VoiceCommand.NAVIGATE_COGNITIVE)
    assertEquals(com.example.gesture.DashboardPage.COGNITIVE, vm.uiState.value.currentPage)

    vm.onVoiceCommand(VoiceCommand.NAVIGATE_MAP)
    assertEquals(com.example.gesture.DashboardPage.MAP, vm.uiState.value.currentPage)
  }

  @Test
  fun gpsIndoorFallback_acceptsModerateAccuracyMoveWhileFilteringJitter() {
    val indoorJitter =
      GpsDistanceMonitor.filterLocationDelta(
        rawDistanceMeters = 12f,
        elapsedSec = 2f,
        accuracyMeters = 45f,
        reportedSpeedMps = 0f,
        allowIndoorFallback = true,
      )
    assertFalse(indoorJitter.acceptedAsAnchor)

    val indoorRealMove =
      GpsDistanceMonitor.filterLocationDelta(
        rawDistanceMeters = 32f,
        elapsedSec = 8f,
        accuracyMeters = 45f,
        reportedSpeedMps = 1.6f,
        allowIndoorFallback = true,
      )
    assertTrue(indoorRealMove.acceptedAsAnchor)
    assertEquals(32f, indoorRealMove.deltaMeters, 0.01f)
  }

  @Test
  fun marsMolaTileCoords_andGpsVectorTrackUpdate() {
    val jezeroTile = com.example.hud.MarsMolaTileCoords.latLonToTile(18.38, 77.58, zoom = 4)
    assertEquals(4, jezeroTile.key.z)
    assertEquals(6, jezeroTile.key.row)
    assertEquals(22, jezeroTile.key.col)
    assertEquals(
      "mars_mola_tiles/5/12/45.jpg",
      com.example.hud.MarsMolaTileCoords.assetPath(5, 12, 45),
    )
    assertEquals(4, com.example.hud.MarsMolaTileCoords.selectTileZoom(1.2f))
    assertEquals(5, com.example.hud.MarsMolaTileCoords.selectTileZoom(2.1f))
    assertEquals(6, com.example.hud.MarsMolaTileCoords.selectTileZoom(3.4f))

    val vm = HudViewModel(enablePeriodicDecay = false)
    assertEquals(1, vm.uiState.value.marsTrackPoints.size)
    vm.onGpsVectorDelta(deltaEastMeters = 12.5f, deltaNorthMeters = -4.0f)
    vm.onGpsVectorDelta(deltaEastMeters = 5.0f, deltaNorthMeters = 9.0f)

    val track = vm.uiState.value.marsTrackPoints
    assertEquals(3, track.size)
    assertEquals(17.5f, track.last().eastMeters, 0.01f)
    assertEquals(5.0f, track.last().northMeters, 0.01f)
  }

  private fun createPinchedHand(
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    scale: Float = 1f,
    zDepth: Float = 0f,
  ): List<HandLandmarkPoint> {
    val base = createOpenPalmUp()
    val cx = 0.50f
    val cy = 0.60f
    return base.mapIndexed { idx, pt ->
      // Bring thumb tip (4) and index tip (8) together into a tight pinch
      val (rawX, rawY) =
        when (idx) {
          3 -> 0.41f to 0.45f
          4 -> 0.43f to 0.39f
          7 -> 0.43f to 0.40f
          8 -> 0.435f to 0.392f
          else -> pt.x to pt.y
        }
      HandLandmarkPoint(
        x = cx + (rawX - cx) * scale + offsetX,
        y = cy + (rawY - cy) * scale + offsetY,
        z = zDepth,
      )
    }
  }

  @Test
  fun marsMapPinchForwardTiltZoomsAndLateralMotionPansCamera() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.OPEN_PERMANENT)
    assertEquals(com.example.gesture.DashboardPage.MAP, vm.uiState.value.currentPage)
    assertEquals(1.0f, vm.uiState.value.mapZoom, 0.01f)
    assertEquals(0f, vm.uiState.value.mapTiltDegrees, 0.01f)

    // Pinch start anchor
    val pinchStart = createPinchedHand(offsetX = 0f, offsetY = 0f, scale = 1.0f, zDepth = 0.0f)
    vm.onHandsDetected(listOf(pinchStart), 1000L)
    assertTrue(vm.uiState.value.isMapPinching)

    // Move pinch forward (larger apparent scale + closer negative Z) and move laterally right
    val pinchForwardRight = createPinchedHand(offsetX = 0.10f, offsetY = -0.05f, scale = 1.24f, zDepth = -0.06f)
    vm.onHandsDetected(listOf(pinchForwardRight), 1120L)

    assertTrue("Expected pinch forward to zoom in", vm.uiState.value.mapZoom > 1.5f)
    assertTrue("Expected pinch forward to tilt into 3D relief", vm.uiState.value.mapTiltDegrees > 8f)
    assertTrue("Expected lateral pinch movement to pan east", vm.uiState.value.mapPanEastDeg > 0.1f)
    assertTrue("Expected upward lateral pinch movement to pan north", vm.uiState.value.mapPanNorthDeg > 0.05f)
    // Lateral pan while pinching must NOT trigger page swipe away from MAP
    assertEquals(com.example.gesture.DashboardPage.MAP, vm.uiState.value.currentPage)

    val zoomed = vm.uiState.value.mapZoom
    // Pull pinch back -> zooms out and flattens tilt (sustained over 2 frames with smoothing)
    val pinchPullBack1 = createPinchedHand(offsetX = 0.10f, offsetY = -0.05f, scale = 0.88f, zDepth = 0.03f)
    vm.onHandsDetected(listOf(pinchPullBack1), 1240L)
    val pinchPullBack2 = createPinchedHand(offsetX = 0.10f, offsetY = -0.05f, scale = 0.80f, zDepth = 0.05f)
    vm.onHandsDetected(listOf(pinchPullBack2), 1360L)
    assertTrue("Expected pinch backward to zoom out", vm.uiState.value.mapZoom < zoomed)
  }

  @Test
  fun molaFallbackCandidates_andVoiceZoomRecenterWorkCleanly() {
    val edgeTile = com.example.hud.MarsTileKey(z = 6, row = 24, col = 89)
    val candidates = com.example.hud.MarsMolaTileCoords.fallbackCandidates(edgeTile)
    assertEquals(3, candidates.size)
    assertEquals(com.example.hud.MarsTileKey(6, 24, 89), candidates[0].key)
    assertEquals(com.example.hud.MarsTileKey(5, 12, 44), candidates[1].key)
    assertEquals(2, candidates[1].scaleDivisor)
    assertEquals(1, candidates[1].subTileX)
    assertEquals(0, candidates[1].subTileY)
    assertEquals(com.example.hud.MarsTileKey(4, 6, 22), candidates[2].key)
    assertEquals(4, candidates[2].scaleDivisor)

    assertEquals(VoiceCommand.MAP_ZOOM_IN, VoiceCommandRecognizer.parseVoiceCommand("zoom in"))
    assertEquals(VoiceCommand.MAP_ZOOM_OUT, VoiceCommandRecognizer.parseVoiceCommand("zoom out"))
    assertEquals(VoiceCommand.MAP_RECENTER, VoiceCommandRecognizer.parseVoiceCommand("recenter"))

    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.OPEN_PERMANENT)
    vm.onVoiceCommand(VoiceCommand.MAP_ZOOM_IN)
    val zoomedIn = vm.uiState.value.mapZoom
    assertTrue(zoomedIn > 1.7f)
    assertTrue(vm.uiState.value.mapTiltDegrees > 5f)

    vm.onVoiceCommand(VoiceCommand.MAP_ZOOM_OUT)
    assertEquals(1.0f, vm.uiState.value.mapZoom, 0.01f)
    assertEquals(0f, vm.uiState.value.mapTiltDegrees, 0.01f)

    vm.onVoiceCommand(VoiceCommand.MAP_ZOOM_IN)
    vm.onVoiceCommand(VoiceCommand.MAP_RECENTER)
    assertEquals(1.0f, vm.uiState.value.mapZoom, 0.01f)
    assertEquals(0f, vm.uiState.value.mapPanEastDeg, 0.01f)
    assertEquals(0f, vm.uiState.value.mapPanNorthDeg, 0.01f)
  }

  @Test
  fun vitalsBpmSynchronizesWithCognitiveMentalStateUnderExertion() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    val initialMental = vm.uiState.value.mentalState
    assertEquals(vm.uiState.value.heartRateBpm.toDouble(), initialMental.heartRate ?: -1.0, 0.1)
    assertEquals(com.example.mental.StressLevel.LOW, initialMental.stressLevel)
    assertEquals("RESTED", initialMental.sleepTracker.sleepStatus)

    var ts = 10_000L
    repeat(8) {
      vm.onLocationSample(deltaMeters = 5.0f, measuredSpeedMps = 3.2f, timestampMs = ts)
      ts += 2_000L
    }

    val exertedState = vm.uiState.value
    assertTrue("Expected elevated Vitals BPM", exertedState.heartRateBpm > 105)
    assertEquals(
      exertedState.heartRateBpm.toDouble(),
      exertedState.mentalState.heartRate ?: -1.0,
      0.1,
    )
    assertTrue(exertedState.mentalState.stressPercent > initialMental.stressPercent)
  }

  @Test
  fun voiceEmergencyCommands_parseNumbersAndWords_andMutateTelemetry() {
    assertEquals(VoiceCommand.NAVIGATE_DRILLS, VoiceCommandRecognizer.parseVoiceCommand("drills"))
    assertEquals(VoiceCommand.NAVIGATE_DRILLS, VoiceCommandRecognizer.parseVoiceCommand("emergencies"))
    assertEquals(VoiceCommand.RunEmergency(1), VoiceCommandRecognizer.parseVoiceCommand("emergency 1"))
    assertEquals(VoiceCommand.RunEmergency(10), VoiceCommandRecognizer.parseVoiceCommand("emergency 10"))
    assertEquals(VoiceCommand.RunEmergency(10), VoiceCommandRecognizer.parseVoiceCommand("emergency ten"))
    assertEquals(VoiceCommand.RunEmergency(6), VoiceCommandRecognizer.parseVoiceCommand("Emergency Six"))
    assertEquals(VoiceCommand.RunEmergency(9), VoiceCommandRecognizer.parseVoiceCommand("emergency 9"))
    assertEquals(VoiceCommand.RunEmergency(0), VoiceCommandRecognizer.parseVoiceCommand("clear drill"))
    assertEquals(VoiceCommand.RunEmergency(0), VoiceCommandRecognizer.parseVoiceCommand("emergency zero"))

    val vm = HudViewModel(enablePeriodicDecay = false)
    val baselineHr = vm.uiState.value.heartRateBpm

    // Runs even while dashboard is CLOSED
    vm.onVoiceCommand(VoiceCommand.RunEmergency(1))
    assertEquals(com.example.mental.EmergencyDrill.EMERGENCY_1, vm.uiState.value.activeDrill)
    assertEquals(2.9f, vm.uiState.value.suitPressurePsi, 0.01f)
    assertEquals(baselineHr + 38, vm.uiState.value.heartRateBpm)
    assertEquals(vm.uiState.value.heartRateBpm.toDouble(), vm.uiState.value.mentalState.heartRate ?: -1.0, 0.1)

    // Emergency 4: CO2 scrubber fault drives ppCO2 and stress up
    vm.onVoiceCommand(VoiceCommand.RunEmergency(4))
    assertEquals(8.4f, vm.uiState.value.ppCo2MmHg, 0.01f)
    assertEquals("CO2 LOAD", vm.uiState.value.mentalState.stressDriver)
    assertTrue(vm.uiState.value.mentalState.stressPercent >= 70)

    // Emergency 6: Acute stress elevates BPM across Vitals+Cognitive and updates sleep tracker
    vm.onVoiceCommand(VoiceCommand.RunEmergency(6))
    assertEquals(baselineHr + 46, vm.uiState.value.heartRateBpm)
    assertEquals(vm.uiState.value.heartRateBpm.toDouble(), vm.uiState.value.mentalState.heartRate ?: -1.0, 0.1)
    assertEquals(5.8f, vm.uiState.value.mentalState.sleepTracker.durationHours, 0.01f)
    assertEquals("FATIGUED", vm.uiState.value.mentalState.sleepTracker.sleepStatus)

    // Emergency 9: Sensor dropout nulls cognitive HR and flags biometric loss
    vm.onVoiceCommand(VoiceCommand.RunEmergency(9))
    assertTrue(vm.uiState.value.isBiometricSensorLost)
    assertNull(vm.uiState.value.mentalState.heartRate)
    assertEquals(com.example.mental.StressLevel.UNKNOWN, vm.uiState.value.mentalState.stressLevel)

    // Emergency 10: Compound decompression hits multi-fault metrics and sleep deprivation
    vm.onVoiceCommand(VoiceCommand.RunEmergency(10))
    assertFalse(vm.uiState.value.isBiometricSensorLost)
    assertEquals(3.1f, vm.uiState.value.suitPressurePsi, 0.01f)
    assertEquals(19, vm.uiState.value.oxygenPercent)
    assertEquals(18, vm.uiState.value.suitPowerPercent)
    assertEquals("DEPRIVED", vm.uiState.value.mentalState.sleepTracker.sleepStatus)
    assertEquals(com.example.mental.StressLevel.CRITICAL, vm.uiState.value.mentalState.stressLevel)

    // Clear drill restores nominal telemetry
    vm.onVoiceCommand(VoiceCommand.RunEmergency(0))
    assertEquals(com.example.mental.EmergencyDrill.NONE, vm.uiState.value.activeDrill)
    assertEquals(4.3f, vm.uiState.value.suitPressurePsi, 0.01f)
    assertEquals(baselineHr, vm.uiState.value.heartRateBpm)
    assertEquals(com.example.mental.StressLevel.LOW, vm.uiState.value.mentalState.stressLevel)

    // Navigate to Drills via voice when dashboard open
    vm.onVoiceCommand(VoiceCommand.TOGGLE_QUICK)
    vm.onVoiceCommand(VoiceCommand.NAVIGATE_DRILLS)
    assertEquals(com.example.gesture.DashboardPage.DRILLS, vm.uiState.value.currentPage)
  }
}
