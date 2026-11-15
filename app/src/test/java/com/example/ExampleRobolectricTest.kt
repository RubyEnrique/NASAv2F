package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.gesture.DashboardMode
import com.example.gesture.DashboardPage
import com.example.gesture.GestureAction
import com.example.gesture.HandPose
import com.example.hud.GameGradientCompass
import com.example.hud.GestureFeedback
import com.example.hud.HudUiState
import com.example.hud.HudViewModel
import com.example.hud.SpatialHudScreen
import com.example.hud.SpatialLayout
import com.example.mental.EmergencyDrill
import com.example.ui.theme.SpatialHudTheme
import com.example.voice.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w800dp-h360dp-land")
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** A dashboard state with [page] anchored straight ahead, as the ViewModel would produce. */
  private fun openOn(page: DashboardPage, base: HudUiState = HudUiState()): HudUiState =
    base.copy(
      hasCameraPermission = true,
      dashboardMode = DashboardMode.PERMANENT,
      currentPage = page,
      anchorYawDegrees = SpatialLayout.anchorYawFor(page, base.headingDegrees),
      anchorPitchDegrees = if (page == DashboardPage.DRILLS) -SpatialLayout.DRILLS_PITCH_OFFSET_DEG else 0f,
    )

  private fun show(state: HudUiState, onLocation: () -> Unit = {}, onTap: () -> Unit = {}) {
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState = state,
          onRequestPermission = {},
          onRequestLocationPermission = onLocation,
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = onTap,
          enableLensEffect = false,
        )
      }
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun appName_matchesApp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("app", context.getString(R.string.app_name))
  }

  @Test
  fun invisibleExitZone_andCompass_visibleWithoutCamera() {
    var taps = 0
    show(HudUiState(hasCameraPermission = false, batteryPercent = 84), onTap = { taps++ })
    composeTestRule.onNodeWithTag("permission_trigger_area").assertIsDisplayed()
    composeTestRule.onNodeWithTag("compass_battery_text").assertTextEquals("84%")
    repeat(5) { composeTestRule.onNodeWithTag("invisible_exit_zone").performClick() }
    assertEquals(5, taps)
    composeTestRule.onNodeWithTag("spatial_tile_isolation").assertDoesNotExist()
  }

  @Test
  fun gameGradientCompass_displays24HrTimeAndLowBattery() {
    composeTestRule.setContent { SpatialHudTheme { GameGradientCompass(headingDegrees = 12f, batteryPercent = 15, timeTextOverride = "14:32") } }
    composeTestRule.onNodeWithTag("compass_time_text").assertTextEquals("14:32")
    composeTestRule.onNodeWithTag("compass_battery_text").assertTextEquals("15%")
  }

  @Test
  fun ambientHud_showsGlanceStripAndLoadHaloOnly() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    show(vm.uiState.value.copy(hasCameraPermission = true))
    composeTestRule.onNodeWithTag("glance_strip").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vitals_bpm_text").assertTextEquals("86")
    composeTestRule.onNodeWithTag("glance_rad_text").assertTextEquals("11") // shielded at base: 0.4 x 28
    composeTestRule.onNodeWithTag("glance_load_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("load_halo").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mic_meter").assertDoesNotExist()
    composeTestRule.onNodeWithTag("spatial_tile_isolation").assertDoesNotExist()
  }

  @Test
  fun cognitiveHub_isInFront_withGaugeForecastEyesAndMarsClock() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    show(openOn(DashboardPage.ISOLATION, vm.uiState.value))
    composeTestRule.onNodeWithTag("spatial_tile_isolation").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_page_mental").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cognitive_level_pill").assertTextEquals("Nominal")
    composeTestRule.onNodeWithTag("cognitive_trend_chart").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cognitive_forecast_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mars_time_text").assertTextContains("Sol ", substring = true)
    composeTestRule.onNodeWithTag("cognitive_load_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("eye_blink_count_text").assertTextEquals("0")
    composeTestRule.onNodeWithTag("eye_preview_box").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_page_indicator").assertIsDisplayed()
  }

  @Test
  fun emergencyDrill_showsAlertBannerBreathingPacerAndEmergencyLevel() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(10))
    show(openOn(DashboardPage.ISOLATION, vm.uiState.value))
    composeTestRule.onNodeWithTag("cognitive_level_pill").assertTextEquals("Emergency")
    composeTestRule.onNodeWithTag("alert_banner").assertIsDisplayed().assertTextContains("Emergency", substring = true)
    composeTestRule.onNodeWithTag("alert_banner").assertTextContains("pulse ${vm.uiState.value.heartRateBpm}", substring = true)
    composeTestRule.onNodeWithTag("breathing_pacer").assertIsDisplayed()
  }

  @Test
  fun environmentTile_showsLiveMapAxemuHabitabilityAndMedaOutside() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(4))
    show(openOn(DashboardPage.ENVIRONMENT, vm.uiState.value.copy(distanceMeters = 1240f, speedMps = 2.1f)))
    composeTestRule.onNodeWithTag("dashboard_page_environment").assertIsDisplayed()
    composeTestRule.onNodeWithTag("environment_status_pill").assertTextEquals("Check suit")
    composeTestRule.onNodeWithTag("suit_helmet_co2_text").assertTextEquals("8.4")
    composeTestRule.onNodeWithTag("suit_pressure_text").assertTextEquals("4.30")
    composeTestRule.onNodeWithTag("outside_air_text").assertTextContains("Pa", substring = true)
    composeTestRule.onNodeWithTag("mars_mola_map_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_distance_text").assertTextEquals("1.24")
    composeTestRule.onNodeWithTag("dashboard_speed_text").assertTextEquals("2.1 m/s")
  }

  @Test
  fun distanceTile_showsEarthDelayAndTurnBackForecast() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(2))
    show(openOn(DashboardPage.DISTANCE, vm.uiState.value))
    composeTestRule.onNodeWithTag("dashboard_page_distance").assertIsDisplayed()
    composeTestRule.onNodeWithTag("earth_delay_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("turn_back_text").assertIsDisplayed()
  }

  @Test
  fun gravityTile_showsActivityRingsAndMarsLoad() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    repeat(1000) { vm.onStep() }
    vm.tick(5_000L)
    show(openOn(DashboardPage.GRAVITY, vm.uiState.value))
    composeTestRule.onNodeWithTag("dashboard_page_gravity").assertIsDisplayed()
    composeTestRule.onNodeWithTag("gravity_rings").assertIsDisplayed()
    composeTestRule.onNodeWithTag("gravity_load_text").assertTextEquals("380 / 7,500")
    composeTestRule.onNodeWithTag("spatial_peek_isolation").assertExists()
    composeTestRule.onNodeWithTag("gesture_ornament").assertIsDisplayed()
  }

  @Test
  fun donkiParser_readsFlaresAndSeps() {
    val flr = "[{\"flrID\":\"x\",\"beginTime\":\"2026-11-13T10:00Z\",\"peakTime\":\"2026-11-13T10:12Z\",\"classType\":\"X1.4\"}]"
    val sep = "[{\"sepID\":\"y\",\"eventTime\":\"2026-11-13T14:00Z\"}]"
    val events = com.example.nasa.SpaceWeatherForecaster.parse("FLR", flr) + com.example.nasa.SpaceWeatherForecaster.parse("SEP", sep)
    assertEquals(2, events.size)
    assertEquals("X1.4", events[0].classType)
    val now = com.example.nasa.SpaceWeatherForecaster.parseTime("2026-11-14T10:00Z")!!
    val state = com.example.nasa.SpaceWeatherForecaster.evaluate(events, now, now)
    assertEquals(com.example.nasa.SpaceWeatherLevel.SEP, state.level)
    assertEquals(true, (state.sepRiskPercent ?: 0) >= 90)
  }

  @Test
  fun radiationTile_showsDoseRateAndShelterRoute() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onGpsVectorDelta(120f, 0f)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(3))
    show(openOn(DashboardPage.RADIATION, vm.uiState.value))
    composeTestRule.onNodeWithTag("dashboard_page_radiation").assertIsDisplayed()
    composeTestRule.onNodeWithTag("radiation_status_pill").assertTextEquals("SPE STORM")
    composeTestRule.onNodeWithTag("space_weather_text").assertTextEquals("Offline")
    composeTestRule.onNodeWithTag("motor_steadiness_text").assertTextEquals("Raise a hand")
    composeTestRule.onNodeWithTag("radiation_base_text").assertTextEquals("120 m · 270°")
    composeTestRule.onNodeWithTag("base_bearing_arrow").assertIsDisplayed()
  }

  @Test
  fun drillsTile_belowRing_listsAllTenAndSensorDropout() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(9))
    show(openOn(DashboardPage.DRILLS, vm.uiState.value))
    composeTestRule.onNodeWithTag("dashboard_page_drills").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drills_status_pill").assertTextEquals("E-09 ACTIVE")
    composeTestRule.onNodeWithTag("vitals_bpm_text").assertTextEquals("--")
    for (n in 1..10) composeTestRule.onNodeWithTag("drill_item_$n").assertIsDisplayed()
  }

  @Test
  fun gestureChip_showsPendingHoldAction() {
    show(
      HudUiState(
        hasCameraPermission = true,
        gesture = GestureFeedback(activePose = HandPose.OPEN_PALM, pendingAction = GestureAction.OPEN_DASHBOARD, holdProgress = 0.5f),
      )
    )
    composeTestRule.onNodeWithTag("gesture_chip").assertIsDisplayed()
  }

  @Test
  fun offlineBundledAssets_existForMapTilesAndNewModels() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    listOf(
        com.example.hud.MarsMolaTileCoords.assetPath(4, 6, 22),
        com.example.hud.MarsMolaTileCoords.assetPath(6, 25, 91),
        "face_landmarker.task",
        "gesture_recognizer.task",
      )
      .forEach { path -> assertEquals(path, true, context.assets.open(path).use { it.readBytes().size > 1000 }) }
    assertEquals(EmergencyDrill.EMERGENCY_3, EmergencyDrill.fromNumber(3))
  }
}
