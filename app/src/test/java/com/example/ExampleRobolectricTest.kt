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
import com.example.hud.SuitTelemetryWidget
import com.example.hud.VitalsTelemetryWidget
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
    composeTestRule.onNodeWithTag("spatial_tile_mental").assertDoesNotExist()
  }

  @Test
  fun gameGradientCompass_displays24HrTimeAndLowBattery() {
    composeTestRule.setContent { SpatialHudTheme { GameGradientCompass(headingDegrees = 12f, batteryPercent = 15, timeTextOverride = "14:32") } }
    composeTestRule.onNodeWithTag("compass_time_text").assertTextEquals("14:32")
    composeTestRule.onNodeWithTag("compass_battery_text").assertTextEquals("15%")
  }

  @Test
  fun cornerVisorCards_showVitalsLoadAndSuitConsumables() {
    composeTestRule.setContent {
      SpatialHudTheme {
        androidx.compose.foundation.layout.Column {
          VitalsTelemetryWidget(cognitiveLoad = 42)
          SuitTelemetryWidget()
        }
      }
    }
    composeTestRule.onNodeWithTag("vitals_bpm_text").assertTextEquals("86")
    composeTestRule.onNodeWithTag("vitals_load_text").assertTextEquals("42")
    composeTestRule.onNodeWithTag("suit_widget_pwr_text").assertTextEquals("92%")
    composeTestRule.onNodeWithTag("suit_rad_text").assertTextEquals("28µSv/h")
    composeTestRule.onNodeWithTag("suit_widget_eva_text").assertTextEquals("03:42")
  }

  @Test
  fun mentalTile_isFirstInFront_withLoadGaugeEyeStatsAndPreviewPlaceholder() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    show(openOn(DashboardPage.MENTAL, vm.uiState.value))
    composeTestRule.onNodeWithTag("spatial_tile_mental").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_page_mental").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cognitive_level_pill").assertTextEquals("NOMINAL")
    composeTestRule.onNodeWithTag("cognitive_load_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("eye_blink_count_text").assertTextEquals("0")
    composeTestRule.onNodeWithTag("eye_preview_box").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_page_indicator").assertIsDisplayed()
  }

  @Test
  fun emergencyDrill_showsAlertBannerBreathingPacerAndEmergencyLevel() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(10))
    show(openOn(DashboardPage.MENTAL, vm.uiState.value))
    composeTestRule.onNodeWithTag("cognitive_level_pill").assertTextEquals("EMERGENCY")
    composeTestRule.onNodeWithTag("alert_banner").assertIsDisplayed().assertTextContains("EMERGENCY", substring = true)
    composeTestRule.onNodeWithTag("alert_banner").assertTextContains("PULSE ${vm.uiState.value.heartRateBpm}", substring = true)
    composeTestRule.onNodeWithTag("breathing_pacer").assertIsDisplayed()
  }

  @Test
  fun vitalsTile_showsCrewBiomedAndEmuTelemetry() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(4))
    show(openOn(DashboardPage.VITALS, vm.uiState.value))
    composeTestRule.onNodeWithTag("dashboard_page_vitals").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vitals_status_pill").assertTextEquals("CO₂ CRITICAL")
    composeTestRule.onNodeWithTag("suit_helmet_co2_text").assertTextEquals("8.4 mmHg")
    composeTestRule.onNodeWithTag("suit_pressure_text").assertTextContains("4.30 psi", substring = true)
  }

  @Test
  fun radiationTile_showsDoseRateAndShelterRoute() {
    val vm = HudViewModel(enablePeriodicDecay = false)
    vm.onGpsVectorDelta(120f, 0f)
    vm.onVoiceCommand(VoiceCommand.RunEmergency(3))
    show(openOn(DashboardPage.RADIATION, vm.uiState.value))
    composeTestRule.onNodeWithTag("dashboard_page_radiation").assertIsDisplayed()
    composeTestRule.onNodeWithTag("radiation_status_pill").assertTextEquals("SPE STORM")
    composeTestRule.onNodeWithTag("radiation_base_text").assertTextEquals("120 m · 270°")
    composeTestRule.onNodeWithTag("base_bearing_arrow").assertIsDisplayed()
  }

  @Test
  fun mapTile_showsDistanceSpeedAndLocationPrompt() {
    var tapped = false
    show(
      openOn(DashboardPage.MAP, HudUiState(distanceMeters = 1240f, speedMps = 2.1f, hasLocationPermission = false)),
      onLocation = { tapped = true },
    )
    composeTestRule.onNodeWithTag("mars_mola_map_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_distance_text").assertTextEquals("1.24")
    composeTestRule.onNodeWithTag("dashboard_distance_unit_text").assertTextEquals("KM")
    composeTestRule.onNodeWithTag("dashboard_speed_text").assertTextEquals("2.1 m/s")
    composeTestRule.onNodeWithTag("location_permission_pill").performClick()
    assertEquals(true, tapped)
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
    composeTestRule.onNodeWithTag("mic_meter").assertIsDisplayed()
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
