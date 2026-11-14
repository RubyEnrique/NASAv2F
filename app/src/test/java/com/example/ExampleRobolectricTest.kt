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
  fun permanentDashboardOpen_displaysGpsDistanceRingMetersSpeedAndReactiveVitals() {
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
  }
}
