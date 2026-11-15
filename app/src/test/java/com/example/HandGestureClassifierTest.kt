package com.example

import com.example.gesture.DashboardMode
import com.example.gesture.DashboardPage
import com.example.gesture.GestureAction
import com.example.gesture.HandGestureClassifier
import com.example.gesture.HandLandmarkPoint
import com.example.gesture.HandObservation
import com.example.gesture.HandPose
import com.example.gesture.SwipeDirection
import com.example.hud.HudEvent
import com.example.hud.HudViewModel
import com.example.hud.SpatialLayout
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

  private fun openPalm(dx: Float = 0f, dy: Float = 0f): List<HandLandmarkPoint> =
    listOf(
      0.50f to 0.70f, 0.44f to 0.65f, 0.40f to 0.58f, 0.36f to 0.52f, 0.33f to 0.46f,
      0.44f to 0.52f, 0.43f to 0.42f, 0.42f to 0.35f, 0.42f to 0.28f,
      0.50f to 0.50f, 0.50f to 0.39f, 0.50f to 0.31f, 0.50f to 0.24f,
      0.56f to 0.52f, 0.57f to 0.41f, 0.58f to 0.34f, 0.59f to 0.27f,
      0.62f to 0.56f, 0.64f to 0.47f, 0.66f to 0.41f, 0.67f to 0.35f,
    ).map { (x, y) -> HandLandmarkPoint(x + dx, y + dy) }

  private fun closedFist(dx: Float = 0f, dy: Float = 0f): List<HandLandmarkPoint> =
    listOf(
      0.50f to 0.70f, 0.45f to 0.65f, 0.42f to 0.59f, 0.45f to 0.55f, 0.49f to 0.55f,
      0.44f to 0.52f, 0.44f to 0.48f, 0.45f to 0.54f, 0.46f to 0.57f,
      0.50f to 0.50f, 0.50f to 0.46f, 0.50f to 0.53f, 0.50f to 0.56f,
      0.56f to 0.52f, 0.56f to 0.48f, 0.55f to 0.54f, 0.55f to 0.57f,
      0.61f to 0.56f, 0.61f to 0.52f, 0.60f to 0.57f, 0.59f to 0.59f,
    ).map { (x, y) -> HandLandmarkPoint(x + dx, y + dy) }

  private fun obs(lm: List<HandLandmarkPoint>, pose: HandPose = HandPose.NONE, score: Float = 0.9f) =
    HandObservation(lm, pose, if (pose == HandPose.NONE) 0f else score)

  private fun pinchedHand(dx: Float = 0f, dy: Float = 0f, scale: Float = 1f, z: Float = 0f): List<HandLandmarkPoint> =
    openPalm().mapIndexed { idx, pt ->
      val (rx, ry) =
        when (idx) {
          3 -> 0.41f to 0.45f
          4 -> 0.43f to 0.39f
          7 -> 0.43f to 0.40f
          8 -> 0.435f to 0.392f
          else -> pt.x to pt.y
        }
      HandLandmarkPoint(0.5f + (rx - 0.5f) * scale + dx, 0.6f + (ry - 0.6f) * scale + dy, z)
    }

  /** Feeds the same hands at ~30 fps from [from] to [to] (inclusive) and returns the final update. */
  private fun hold(c: HandGestureClassifier, hands: List<HandObservation>, from: Long, to: Long, mode: DashboardMode) =
    (from..to step 33L).map { c.process(hands, it, mode) }.last().let { if ((to - from) % 33L != 0L) c.process(hands, to, mode) else it }

  // ------------------------------------------------------------ Gestures

  @Test
  fun openPalmHold_opensDashboard_fistHold_closesIt_andPoseMustBeReleased() {
    val c = HandGestureClassifier()
    val palm = listOf(obs(openPalm(), HandPose.OPEN_PALM))
    assertEquals(GestureAction.OPEN_DASHBOARD, c.process(palm, 1000L, DashboardMode.CLOSED).pendingAction)
    val mid = c.process(palm, 1300L, DashboardMode.CLOSED)
    assertEquals(0.5f, mid.holdProgress, 0.02f)
    assertEquals(GestureAction.OPEN_DASHBOARD, c.process(palm, 1600L, DashboardMode.CLOSED).action)

    // Holding the same pose does not fire again until released.
    assertNull(c.process(palm, 2400L, DashboardMode.CLOSED).action)
    assertNull(c.process(palm, 3200L, DashboardMode.CLOSED).action)

    val fist = listOf(obs(closedFist(), HandPose.CLOSED_FIST))
    hold(c, fist, 4000L, 4800L, DashboardMode.PERMANENT)
    assertEquals(GestureAction.CLOSE_DASHBOARD, c.process(fist, 4920L, DashboardMode.PERMANENT).action)
  }

  @Test
  fun palmHold_togglesOpenAndClosed_butSwipingHandDoesNotClose() {
    val c = HandGestureClassifier()
    val palm = listOf(obs(openPalm(), HandPose.OPEN_PALM))
    hold(c, palm, 0L, 560L, DashboardMode.CLOSED)
    assertEquals(GestureAction.OPEN_DASHBOARD, c.process(palm, 610L, DashboardMode.CLOSED).action)
    c.process(emptyList(), 900L, DashboardMode.PERMANENT) // hand lowered -> release
    hold(c, palm, 1_600L, 2_400L, DashboardMode.PERMANENT)
    assertNull("needs the longer 1 s hold", c.process(palm, 2_500L, DashboardMode.PERMANENT).action)
    assertEquals(GestureAction.CLOSE_DASHBOARD, c.process(palm, 2_620L, DashboardMode.PERMANENT).action)

    val moving = HandGestureClassifier()
    var closed = false
    for (i in 0..40) {
      val u = moving.process(listOf(obs(openPalm(dx = -0.15f + (i % 10) * 0.03f), HandPose.OPEN_PALM)), 10_000L + i * 40L, DashboardMode.PERMANENT)
      if (u.action == GestureAction.CLOSE_DASHBOARD) closed = true
    }
    assertFalse("a sweeping palm never closes the window", closed)
  }

  /** Index extended, the other three curled: the shape that used to be misread as a fist. */
  private fun pointingHand(): List<HandLandmarkPoint> =
    closedFist().mapIndexed { i, p ->
      when (i) {
        6 -> HandLandmarkPoint(0.43f, 0.40f)
        7 -> HandLandmarkPoint(0.425f, 0.32f)
        8 -> HandLandmarkPoint(0.42f, 0.24f)
        else -> p
      }
    }

  @Test
  fun pointingIsNeverReadAsFist_andCannotCloseTheDashboard() {
    val c = HandGestureClassifier()
    val point = pointingHand()
    assertFalse(c.isClosedFist(point))
    assertEquals(HandPose.POINTING_UP, c.resolvePose(obs(point)))
    var closed = false
    for (t in 0L..2_000L step 33L) if (c.process(listOf(obs(point)), t, DashboardMode.PERMANENT).action == GestureAction.CLOSE_DASHBOARD) closed = true
    assertFalse(closed)
    // A real fist still closes, but only after the longer 0.9 s hold.
    val f = HandGestureClassifier()
    val fist = listOf(obs(closedFist(), HandPose.CLOSED_FIST))
    hold(f, fist, 0L, 800L, DashboardMode.PERMANENT)
    assertNull(f.process(fist, 850L, DashboardMode.PERMANENT).action)
    assertEquals(GestureAction.CLOSE_DASHBOARD, f.process(fist, 920L, DashboardMode.PERMANENT).action)
  }

  @Test
  fun heuristicFallback_recognisesPalmWithoutClassifierCategory() {
    val c = HandGestureClassifier()
    assertEquals(HandPose.OPEN_PALM, c.resolvePose(obs(openPalm())))
    assertEquals(HandPose.CLOSED_FIST, c.resolvePose(obs(closedFist())))
    // Low-confidence ML label is ignored in favour of geometry.
    assertEquals(HandPose.OPEN_PALM, c.resolvePose(HandObservation(openPalm(), HandPose.THUMB_UP, 0.2f)))
    hold(c, listOf(obs(openPalm())), 0L, 560L, DashboardMode.CLOSED)
    assertEquals(GestureAction.OPEN_DASHBOARD, c.process(listOf(obs(openPalm())), 610L, DashboardMode.CLOSED).action)
  }

  @Test
  fun heldPoseResets_whenHandTravelsTooFar() {
    val c = HandGestureClassifier()
    c.process(listOf(obs(openPalm(), HandPose.OPEN_PALM)), 0L, DashboardMode.CLOSED)
    val moved = c.process(listOf(obs(openPalm(dx = 0.2f), HandPose.OPEN_PALM)), 400L, DashboardMode.CLOSED)
    assertEquals(0f, moved.holdProgress, 0.01f)
    assertNull(hold(c, listOf(obs(openPalm(dx = 0.2f), HandPose.OPEN_PALM)), 433L, 700L, DashboardMode.CLOSED).action)
  }

  @Test
  fun namedPoses_mapToAcknowledgeRecenterEyePreviewAndClearDrill() {
    fun fire(pose: HandPose, mode: DashboardMode, hold: Long): GestureAction? {
      val c = HandGestureClassifier()
      val hands = listOf(obs(closedFist(), pose))
      hold(c, hands, 0L, hold - 40L, mode)
      return c.process(hands, hold, mode).action
    }
    assertEquals(GestureAction.ACKNOWLEDGE, fire(HandPose.THUMB_UP, DashboardMode.CLOSED, 420L))
    assertEquals(GestureAction.TOGGLE_CENTER_LOCK, fire(HandPose.POINTING_UP, DashboardMode.PERMANENT, 720L))
    assertEquals(GestureAction.TOGGLE_EYE_PREVIEW, fire(HandPose.VICTORY, DashboardMode.PERMANENT, 620L))
    assertEquals(GestureAction.CLEAR_DRILL, fire(HandPose.THUMB_DOWN, DashboardMode.PERMANENT, 920L))
    assertNull(fire(HandPose.POINTING_UP, DashboardMode.CLOSED, 2000L))
  }

  @Test
  fun swipe_movesAroundTheRingAndWrapsFromMapBackToMental() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.OPEN_PERMANENT)
    assertEquals("opens on the cognitive hub", DashboardPage.ISOLATION, vm.uiState.value.currentPage)

    var t = 1000L
    fun swipe(fromX: Float, toX: Float) {
      vm.onHandsDetected(listOf(obs(openPalm(dx = fromX), HandPose.OPEN_PALM)), t)
      t += 120L
      vm.onHandsDetected(listOf(obs(openPalm(dx = toX), HandPose.OPEN_PALM)), t)
      t += 800L
    }
    swipe(-0.08f, 0.08f)
    assertEquals(DashboardPage.DISTANCE, vm.uiState.value.currentPage)
    assertEquals(SwipeDirection.NEXT, vm.uiState.value.lastSwipeDirection)
    swipe(-0.08f, 0.08f)
    assertEquals(DashboardPage.GRAVITY, vm.uiState.value.currentPage)
    swipe(-0.08f, 0.08f)
    assertEquals(DashboardPage.ENVIRONMENT, vm.uiState.value.currentPage)
    swipe(-0.08f, 0.08f)
    assertEquals("Ring wraps hazard 5 -> hazard 1", DashboardPage.RADIATION, vm.uiState.value.currentPage)
    swipe(0.08f, -0.08f)
    assertEquals(DashboardPage.ENVIRONMENT, vm.uiState.value.currentPage)
    assertEquals(5L, vm.uiState.value.swipeCueId)
  }

  @Test
  fun mapPinch_zoomsTiltsAndPans_onlyOnMapTile() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.OPEN_PERMANENT)
    vm.onVoiceCommand(VoiceCommand.NAVIGATE_ENVIRONMENT)
    vm.onHandsDetected(listOf(obs(pinchedHand())), 1000L)
    assertTrue(vm.uiState.value.isMapPinching)
    vm.onHandsDetected(listOf(obs(pinchedHand(dx = 0.10f, dy = -0.05f, scale = 1.24f, z = -0.06f))), 1120L)
    assertTrue(vm.uiState.value.mapZoom > 1.5f)
    assertTrue(vm.uiState.value.mapTiltDegrees > 8f)
    assertTrue(vm.uiState.value.mapPanEastDeg > 0.1f)
    assertEquals(DashboardPage.ENVIRONMENT, vm.uiState.value.currentPage)
  }

  // ------------------------------------------------------------ Head-locked spatial layout

  @Test
  fun spatialLayout_focusFollowsHeadTurnAndLookingDownShowsDrills() {
    val anchor = 100f
    assertEquals(DashboardPage.RADIATION, SpatialLayout.focusedPage(100f, 0f, anchor, 0f))
    assertEquals(DashboardPage.ISOLATION, SpatialLayout.focusedPage(141f, 0f, anchor, 0f))
    assertEquals(DashboardPage.DISTANCE, SpatialLayout.focusedPage(178f, 0f, anchor, 0f))
    assertEquals(DashboardPage.GRAVITY, SpatialLayout.focusedPage(222f, 0f, anchor, 0f))
    assertEquals(DashboardPage.ENVIRONMENT, SpatialLayout.focusedPage(262f, 0f, anchor, 0f))
    assertEquals(DashboardPage.DRILLS, SpatialLayout.focusedPage(150f, -30f, anchor, 0f))
    assertEquals(-20f, SpatialLayout.angleDelta(350f, 10f), 0.01f)

    val centred = SpatialLayout.project(50f, 0f, 50f, 0f)
    val side = SpatialLayout.project(90f, 0f, 50f, 0f)
    assertEquals(0f, centred.xDeg, 0.01f)
    assertTrue("fish-eye compresses off-axis angles", side.xDeg < 40f && side.xDeg > 30f)
    assertTrue("off-axis tiles shrink", side.scale < centred.scale)
    assertTrue("right-hand tiles turn toward the viewer", side.rotationY < 0f)

    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onHeadingChanged(200f)
    vm.onVoiceCommand(VoiceCommand.OPEN_PERMANENT)
    vm.onHeadingChanged(281f) // opened on H2 at 200; turn ~2 tiles right -> H4
    assertEquals(DashboardPage.GRAVITY, vm.uiState.value.currentPage)
    vm.onPitchChanged(-30f)
    assertEquals(DashboardPage.DRILLS, vm.uiState.value.currentPage)
    vm.onPitchChanged(0f)
    for (t in 0L..720L step 40L) vm.onHandsDetected(listOf(obs(closedFist(), HandPose.POINTING_UP)), t)
    assertTrue("point toggles center lock", vm.uiState.value.centerLocked)
    assertEquals(281f - 3 * SpatialLayout.TILE_SPACING_DEG, vm.uiState.value.anchorYawDegrees, 0.01f)
    // While locked, turning the head drags the window along instead of switching hazard.
    vm.onHeadingChanged(330f)
    assertEquals(DashboardPage.GRAVITY, vm.uiState.value.currentPage)
    assertEquals(330f - 3 * SpatialLayout.TILE_SPACING_DEG, vm.uiState.value.anchorYawDegrees, 0.01f)
    vm.onVoiceCommand(VoiceCommand.CENTER_LOCK)
    assertFalse(vm.uiState.value.centerLocked)
  }

  @Test
  fun cameraPitch_isElevationOfRearCameraAxis() {
    val level = floatArrayOf(0f, 1f, 0f, 0f, 0f, -1f, -1f, 0f, 0f)
    assertEquals(0f, HeadingSensorMonitor.computeCameraPitchDegrees(level), 0.1f)
    val lookingDown = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // screen up, camera down
    assertEquals(-90f, HeadingSensorMonitor.computeCameraPitchDegrees(lookingDown), 0.1f)
    val northR = floatArrayOf(0f, 1f, 0f, 0f, 0f, -1f, -1f, 0f, 0f)
    assertEquals(0f, HeadingSensorMonitor.computeCameraHeadingDegrees(northR), 0.1f)
  }

  // ------------------------------------------------------------ Voice

  @Test
  fun voiceCommands_parseNewVocabulary() {
    fun p(s: String) = VoiceCommandRecognizer.parseVoiceCommand(s)
    assertEquals(VoiceCommand.TOGGLE_QUICK, p("Dashboard"))
    assertEquals(VoiceCommand.OPEN_PERMANENT, p("dashboard-open"))
    assertEquals(VoiceCommand.CLOSE_PERMANENT, p("close dashboard"))
    assertEquals(VoiceCommand.NAVIGATE_ISOLATION, p("mental health"))
    assertEquals(VoiceCommand.NAVIGATE_ISOLATION, p("cognitive"))
    assertEquals(VoiceCommand.NAVIGATE_DISTANCE, p("earth"))
    assertEquals(VoiceCommand.NAVIGATE_GRAVITY, p("exercise"))
    assertEquals(VoiceCommand.NAVIGATE_ENVIRONMENT, p("suit status"))
    assertEquals(VoiceCommand.NAVIGATE_RADIATION, p("radiation"))
    assertEquals(VoiceCommand.NAVIGATE_ENVIRONMENT, p("map"))
    assertEquals(VoiceCommand.NAVIGATE_DRILLS, p("drills"))
    assertEquals(VoiceCommand.RECENTER_VIEW, p("recenter"))
    assertEquals(VoiceCommand.CENTER_LOCK, p("center"))
    assertEquals(VoiceCommand.END_ALL_EMERGENCIES, p("end all emergencies"))
    assertEquals(VoiceCommand.END_ALL_EMERGENCIES, p("all clear"))
    assertEquals(VoiceCommand.MAP_RECENTER, p("center map"))
    assertEquals(VoiceCommand.ACKNOWLEDGE, p("acknowledge"))
    assertEquals(VoiceCommand.TOGGLE_EYE_PREVIEW, p("show eyes"))
    assertEquals(VoiceCommand.CALIBRATE_MIC, p("calibrate microphone"))
    assertEquals(VoiceCommand.SENSITIVITY_UP, p("sensitivity up"))
    assertEquals(VoiceCommand.RunEmergency(10), p("emergency ten"))
    assertEquals(VoiceCommand.RunEmergency(0), p("clear drill"))
    assertNull(p("hello world"))

    assertEquals("radiation", VoiceCommandRecognizer.extractTranscript("{\"text\" : \"radiation\"}", "text"))
    val json = "{\"result\":[{\"conf\":0.9,\"word\":\"zoom\"},{\"conf\":0.5,\"word\":\"in\"}],\"text\":\"zoom in\"}"
    assertEquals(0.7f, VoiceCommandRecognizer.extractMeanConfidence(json) ?: 0f, 0.001f)
    assertTrue(VoiceCommandRecognizer.minConfidenceFor(1) > VoiceCommandRecognizer.minConfidenceFor(5))
  }

  @Test
  fun voiceNavigation_bringsRequestedTileInFront() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.NAVIGATE_GRAVITY)
    assertEquals("ignored while closed", DashboardPage.ISOLATION, vm.uiState.value.currentPage)
    vm.onVoiceCommand(VoiceCommand.TOGGLE_QUICK)
    assertEquals(DashboardMode.QUICK_VOICE, vm.uiState.value.dashboardMode)
    vm.onVoiceCommand(VoiceCommand.NAVIGATE_RADIATION)
    assertEquals(DashboardPage.RADIATION, vm.uiState.value.currentPage)
    assertEquals(0f, vm.uiState.value.anchorYawDegrees, 0.01f)
    vm.onVoiceCommand(VoiceCommand.NAVIGATE_DISTANCE)
    assertEquals(-2 * SpatialLayout.TILE_SPACING_DEG, vm.uiState.value.anchorYawDegrees, 0.01f)
    vm.onVoiceCommand(VoiceCommand.NAVIGATE_DRILLS)
    assertEquals(DashboardPage.DRILLS, vm.uiState.value.currentPage)
    vm.onVoiceCommand(VoiceCommand.TOGGLE_EYE_PREVIEW)
    assertFalse(vm.uiState.value.eyePreviewEnabled)
    vm.onVoiceCommand(VoiceCommand.TOGGLE_QUICK)
    assertEquals(DashboardMode.CLOSED, vm.uiState.value.dashboardMode)
  }

  // ------------------------------------------------------------ GPS / exit

  @Test
  fun gpsFilter_rejectsJitterAndDrivesReactiveHeartRate() {
    assertFalse(GpsDistanceMonitor.filterLocationDelta(12f, 2f, 40f, 2f).acceptedAsAnchor)
    assertFalse(GpsDistanceMonitor.filterLocationDelta(1.8f, 1.5f, 8f, 0.1f).acceptedAsAnchor)
    val moving = GpsDistanceMonitor.filterLocationDelta(6.0f, 2.0f, 6f, 2.4f)
    assertTrue(moving.acceptedAsAnchor)
    assertEquals(2.4f, moving.speedMps, 0.01f)

    val vm = HudViewModel(enablePeriodicDecay = false)
    var ts = 10_000L
    repeat(6) {
      vm.onLocationSample(4.8f, 2.4f, ts)
      ts += 2_000L
    }
    assertEquals(28.8f, vm.uiState.value.distanceMeters, 0.1f)
    assertTrue(vm.uiState.value.heartRateBpm > 100)
    assertEquals(vm.uiState.value.heartRateBpm.toDouble(), vm.uiState.value.mentalState.heartRate ?: -1.0, 0.1)
  }

  @Test
  fun invisibleExitTap_requiresFiveTapsWithinTwoSeconds() = runTest {
    val vm = HudViewModel(enablePeriodicDecay = false)
    val emitted = mutableListOf<HudEvent>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { emitted.add(it) } }
    listOf(1000L, 3500L, 3600L, 3700L, 3800L).forEach { vm.onInvisibleExitZoneTap(it) }
    assertTrue(emitted.none { it == HudEvent.ExitApp })
    vm.onInvisibleExitZoneTap(3900L)
    assertTrue(emitted.contains(HudEvent.ExitApp))
    job.cancel()
  }
}
