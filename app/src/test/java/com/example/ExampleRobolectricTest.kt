package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.hud.HudUiState
import com.example.hud.SpatialHudScreen
import com.example.hud.SuitTelemetryWidget
import com.example.hud.VitalsTelemetryWidget
import com.example.ui.theme.SpatialHudTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun appName_matchesApp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("app", appName)
  }

  @Test
  fun invisibleExitZone_triggersFiveTapCallback() {
    var tapCount = 0
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState = HudUiState(hasCameraPermission = false, headingDegrees = 245f),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = { tapCount++ },
        )
      }
    }

    val exitNode = composeTestRule.onNodeWithTag("invisible_exit_zone")
    exitNode.assertIsDisplayed()
    repeat(5) { exitNode.performClick() }
    assertEquals(5, tapCount)
  }

  @Test
  fun compassAlwaysVisible_showsTimeAndBatteryCapsules() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = false,
              headingDegrees = 245f,
              batteryPercent = 84,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("game_compass").assertIsDisplayed()
    composeTestRule.onNodeWithTag("compass_time_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("compass_battery_text").assertTextEquals("84%")
    composeTestRule.onNodeWithTag("permission_trigger_area").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vision_dashboard_panel").assertDoesNotExist()
  }

  @Test
  fun gameGradientCompass_displays24HrTimeAndLowBatteryLevel() {
    composeTestRule.setContent {
      SpatialHudTheme {
        com.example.hud.GameGradientCompass(
          headingDegrees = 12f,
          batteryPercent = 15,
          timeTextOverride = "14:32",
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("game_compass").assertIsDisplayed()
    composeTestRule.onNodeWithTag("compass_time_text").assertTextEquals("14:32")
    composeTestRule.onNodeWithTag("compass_battery_text").assertTextEquals("15%")
  }

  @Test
  fun vitalsTelemetryWidget_displaysHigherReactiveHeartRate() {
    composeTestRule.setContent {
      SpatialHudTheme {
        VitalsTelemetryWidget()
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("vitals_hud_widget").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vitals_bpm_text").assertTextEquals("86")
    composeTestRule.onNodeWithTag("vitals_bp_text").assertTextEquals("118/76")
    composeTestRule.onNodeWithTag("vitals_h2o_text").assertTextEquals("74%")
  }

  @Test
  fun suitTelemetryWidget_displaysNormalAndLowPowerStates() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SuitTelemetryWidget(initialBatteryLow = false)
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("suit_hud_widget").assertIsDisplayed()
    composeTestRule.onNodeWithTag("suit_pressure_text").assertTextEquals("4.3psi")
    composeTestRule.onNodeWithTag("suit_rad_text").assertTextEquals("0.24mSv")
    composeTestRule.onNodeWithTag("suit_eva_text").assertTextEquals("03:42")
    composeTestRule.onNodeWithTag("suit_pwr_percent_text").assertTextEquals("92%")
  }

  @Test
  fun suitTelemetryWidget_lowBatteryBelow20Percent_shows18Percent() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SuitTelemetryWidget(initialBatteryLow = true)
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("suit_hud_widget").assertIsDisplayed()
    composeTestRule.onNodeWithTag("suit_pwr_percent_text").assertTextEquals("18%")
  }

  @Test
  fun permanentDashboardOpen_displaysMarsMapGpsDistanceRingMetersSpeedAndReactiveVitals() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              headingDegrees = 45f,
              batteryPercent = 84,
              distanceMeters = 340f,
              speedMps = 1.4f,
              heartRateBpm = 107,
              dashboardMode = com.example.gesture.DashboardMode.PERMANENT,
              isTwoFistsReady = true,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("game_compass").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vitals_hud_widget").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vitals_bpm_text").assertTextEquals("107")
    composeTestRule.onNodeWithTag("suit_hud_widget").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vision_dashboard_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mars_mola_map_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_distance_ring").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_distance_text").assertTextEquals("340")
    composeTestRule.onNodeWithTag("dashboard_distance_unit_text").assertTextEquals("METERS")
    composeTestRule.onNodeWithTag("dashboard_speed_text").assertTextEquals("1.4 m/s")
    composeTestRule.onNodeWithTag("two_fists_dual_ring").assertIsDisplayed()
  }

  @Test
  fun permanentDashboardOpen_formatsKilometersAboveOneThousandMeters() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              headingDegrees = 90f,
              batteryPercent = 76,
              distanceMeters = 1240f,
              speedMps = 2.1f,
              heartRateBpm = 115,
              dashboardMode = com.example.gesture.DashboardMode.QUICK_VOICE,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_distance_text").assertTextEquals("1.24")
    composeTestRule.onNodeWithTag("dashboard_distance_unit_text").assertTextEquals("KM")
    composeTestRule.onNodeWithTag("dashboard_speed_text").assertTextEquals("2.1 m/s")
    composeTestRule.onNodeWithTag("dashboard_page_indicator").assertIsDisplayed()
  }

  @Test
  fun triDashboard_displaysMapPageAndLocationPermissionPill() {
    var locationPromptTapped = false
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              hasLocationPermission = false,
              dashboardMode = com.example.gesture.DashboardMode.PERMANENT,
              currentPage = com.example.gesture.DashboardPage.MAP,
            ),
          onRequestPermission = {},
          onRequestLocationPermission = { locationPromptTapped = true },
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_page_indicator").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mars_mola_map_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("location_permission_pill").assertIsDisplayed().performClick()
    assertEquals(true, locationPromptTapped)
  }

  @Test
  fun triDashboard_rendersInferredVitalsPage() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              hasLocationPermission = true,
              heartRateBpm = 116,
              systolicBp = 132,
              diastolicBp = 81,
              dashboardMode = com.example.gesture.DashboardMode.PERMANENT,
              currentPage = com.example.gesture.DashboardPage.VITALS,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_page_vitals").assertIsDisplayed()
    composeTestRule.onNodeWithTag("vitals_status_pill").assertTextEquals("ACTIVE")
  }

  @Test
  fun triDashboard_rendersInferredSuitStatusPageAndLowPowerAlert() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              hasLocationPermission = true,
              suitPowerPercent = 18,
              dashboardMode = com.example.gesture.DashboardMode.PERMANENT,
              currentPage = com.example.gesture.DashboardPage.SUIT_STATUS,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_page_suit").assertIsDisplayed()
    composeTestRule.onNodeWithTag("suit_status_pill").assertTextEquals("LOW POWER")
  }

  @Test
  fun zoomedMarsMap_rendersMolaZoomTiltPill() {
    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              dashboardMode = com.example.gesture.DashboardMode.PERMANENT,
              currentPage = com.example.gesture.DashboardPage.MAP,
              mapZoom = 2.5f,
              mapTiltDegrees = 21f,
              isMapPinching = true,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("mars_mola_map_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mola_zoom_tilt_pill").assertIsDisplayed()
  }

  @Test
  fun offlineBundledMolaAssets_existForAllZoomTiers() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val z4 = context.assets.open(com.example.hud.MarsMolaTileCoords.assetPath(4, 6, 22)).use { it.readBytes() }
    val z5 = context.assets.open(com.example.hud.MarsMolaTileCoords.assetPath(5, 12, 45)).use { it.readBytes() }
    val z6 = context.assets.open(com.example.hud.MarsMolaTileCoords.assetPath(6, 25, 91)).use { it.readBytes() }
    assertEquals(true, z4.size > 1000)
    assertEquals(true, z5.size > 1000)
    assertEquals(true, z6.size > 1000)
  }

  @Test
  fun cognitiveDashboardPage_displaysCalibratingAndReadyBandStatesOnly() {
    val calibratingState =
      com.example.mental.MentalState(
        heartRate = 79.4,
        o2Use = 0.11,
        helmetCo2MmHg = 1.2,
        score = null,
        band = com.example.mental.MentalBand.UNKNOWN,
        label = "[Readiness Low]",
        askOk = true,
        calibrated = false,
        calibration = 0.42f,
        needsWorkloadChange = false,
        expectedHr = null,
        additionalHr = null,
        hrPenalty = 0,
        co2Penalty = 0,
        scenario = com.example.mental.MentalScenario.BASELINE,
      )

    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              dashboardMode = com.example.gesture.DashboardMode.PERMANENT,
              currentPage = com.example.gesture.DashboardPage.COGNITIVE,
              mentalState = calibratingState,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_page_cognitive").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cognitive_status_pill").assertTextEquals("CALIBRATING")
    composeTestRule.onNodeWithTag("cognitive_score_text").assertTextEquals("--")
    composeTestRule.onNodeWithTag("cognitive_calibration_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cognitive_calibration_text").assertTextEquals("CALIBRATION 42%")
    composeTestRule.onNodeWithTag("cognitive_hr_text").assertTextEquals("79 BPM")
  }

  @Test
  fun cognitiveDashboardPage_displaysOkScoreWhenCalibrated() {
    val okState =
      com.example.mental.MentalState(
        heartRate = 84.2,
        o2Use = 0.13,
        helmetCo2MmHg = 1.4,
        score = 94,
        band = com.example.mental.MentalBand.OK,
        label = null,
        askOk = false,
        calibrated = true,
        calibration = 1.0f,
        needsWorkloadChange = false,
        expectedHr = 83.0,
        additionalHr = 1.2,
        hrPenalty = 0,
        co2Penalty = 0,
        scenario = com.example.mental.MentalScenario.BASELINE,
      )

    composeTestRule.setContent {
      SpatialHudTheme {
        SpatialHudScreen(
          uiState =
            HudUiState(
              hasCameraPermission = true,
              dashboardMode = com.example.gesture.DashboardMode.PERMANENT,
              currentPage = com.example.gesture.DashboardPage.COGNITIVE,
              mentalState = okState,
            ),
          onRequestPermission = {},
          onHandsDetected = { _, _ -> },
          onInvisibleCornerTap = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_page_cognitive").assertIsDisplayed()
    composeTestRule.onNodeWithTag("cognitive_status_pill").assertTextEquals("OK")
    composeTestRule.onNodeWithTag("cognitive_score_text").assertTextEquals("94")
    composeTestRule.onNodeWithTag("cognitive_calibration_text").assertTextEquals("CALIBRATED")
    composeTestRule.onNodeWithTag("cognitive_hr_text").assertTextEquals("84 BPM")
  }
}
