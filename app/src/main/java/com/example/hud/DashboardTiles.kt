package com.example.hud

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.EyeOverlay
import com.example.eye.EyeTrackingStatus
import com.example.gesture.DashboardPage
import com.example.mental.CognitiveLevel
import com.example.mental.EmergencyDrill
import com.example.mental.StressLevel
import com.example.nasa.Mars24
import com.example.nasa.SpaceWeatherLevel
import com.example.radiation.RadiationModel
import com.example.radiation.RadiationStatus
import com.example.radiation.ShelterStatus
import com.example.suit.AxemuProfile
import kotlin.math.roundToInt

private val TilePadding = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)

/** NASA ISS reference sleep (Barger et al. 2014, Lancet Neurology): ~6.1 h/night vs 8.5 h scheduled. */
private const val NASA_ISS_SLEEP_H = 6.1f
private const val MARS_SOL_DRIFT_MIN = 39.6f

// ===================================================================== H1 · RADIATION (mind)

@Composable
internal fun RadiationTile(uiState: HudUiState) {
  val r = uiState.radiation
  val sw = uiState.spaceWeather
  val color =
    when {
      r.status == RadiationStatus.STORM || sw.level == SpaceWeatherLevel.SEP -> Ios.Red
      r.status == RadiationStatus.ELEVATED || sw.level == SpaceWeatherLevel.ACTIVE -> Ios.Orange
      else -> Ios.Green
    }
  Column(TilePadding.fillMaxSize().testTag("dashboard_page_radiation"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TileHeader(DashboardPage.RADIATION, r.status.label, color, "radiation_status_pill")
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Dose rate", style = IosType.caption)
        Row(verticalAlignment = Alignment.Bottom) {
          Text(fmt("%.1f", r.doseRateUsvPerHour), style = IosType.largeNumber.copy(color = color), modifier = Modifier.testTag("radiation_dose_rate_text"))
          Spacer(Modifier.width(4.dp))
          Text("µSv/h", style = IosType.footnote, modifier = Modifier.padding(bottom = 5.dp))
        }
        val ratio = r.doseRateUsvPerHour / RadiationModel.GCR_BASELINE_USV_PER_H
        LevelBar((0.25f + 0.25f * kotlin.math.log2(ratio.coerceAtLeast(0.25f))).coerceIn(0.03f, 1f), color)
        Text(fmt("Mars surface baseline %.0f µSv/h (Curiosity RAD)", RadiationModel.GCR_BASELINE_USV_PER_H), style = IosType.footnote)
        GroupedList {
          ListRow(
            "Space weather",
            when (sw.level) {
              SpaceWeatherLevel.UNKNOWN -> "Offline"
              else -> sw.level.label + (sw.strongestFlare?.let { " · $it" } ?: "")
            },
            if (sw.level == SpaceWeatherLevel.SEP) Ios.Red else Ios.Label,
            dot = Ios.Yellow,
            valueTag = "space_weather_text",
          )
          ListRow("SEP risk 48 h", sw.sepRiskPercent?.let { "$it%" } ?: "—", dot = Ios.Orange)
          ListRow("EVA dose", fmt("%.1f µSv", r.evaDoseUsv), dot = Ios.Blue)
          ListRow("Career limit used", fmt("%.4f%%", r.careerLimitFraction * 100f), dot = Ios.Purple, separator = false)
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Brain & motor watch", style = IosType.caption)
        GroupedList {
          ListRow(
            "Hand steadiness",
            uiState.motorSteadiness?.let { "$it / 100" } ?: "Raise a hand",
            when (val m = uiState.motorSteadiness) {
              null -> Ios.Secondary
              in 0..59 -> Ios.Orange
              else -> Ios.Green
            },
            dot = Ios.Mint,
            valueTag = "motor_steadiness_text",
          )
          ListRow("Cognitive load", "${uiState.cognitive.index}", levelColor(uiState.cognitive.level), dot = Ios.Indigo, separator = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          BaseBearingArrow(
            SpatialLayout.angleDelta(r.bearingToBaseDeg, uiState.headingDegrees),
            r.shelter == ShelterStatus.EXPOSED,
            color,
            Modifier.size(40.dp),
          )
          Spacer(Modifier.width(8.dp))
          Column {
            Text(
              if (r.shelter == ShelterStatus.SHELTERED) "At base · shielded" else fmt("%.0f m · %03.0f°", r.distanceToBaseMeters, r.bearingToBaseDeg),
              style = IosType.headline,
              modifier = Modifier.testTag("radiation_base_text"),
            )
            Text(fmt("ETA %s · +%.1f µSv", formatDuration(r.etaToBaseSec), r.returnDoseUsv), style = IosType.footnote)
          }
        }
        Text(r.advice, style = hudText(9.sp, color, FontWeight.Medium), maxLines = 2)
      }
    }
  }
}

@Composable
private fun BaseBearingArrow(relativeDeg: Float, active: Boolean, color: Color, modifier: Modifier) {
  val animated by animateFloatAsState(relativeDeg, tween(250, easing = FastOutSlowInEasing), label = "base_arrow")
  Box(modifier.testTag("base_bearing_arrow").clip(RoundedCornerShape(50)).background(Ios.Grouped), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize().padding(8.dp).rotate(if (active) animated else 0f)) {
      val c = Offset(size.width / 2f, size.height / 2f)
      val l = size.minDimension * 0.5f
      if (!active) {
        drawCircle(Ios.Green, l * 0.4f, c)
        return@Canvas
      }
      drawPath(
        Path().apply {
          moveTo(c.x, c.y - l)
          lineTo(c.x + l * 0.55f, c.y + l * 0.7f)
          lineTo(c.x, c.y + l * 0.35f)
          lineTo(c.x - l * 0.55f, c.y + l * 0.7f)
          close()
        },
        color,
      )
    }
  }
}

// ===================================================================== H2 · ISOLATION & CONFINEMENT (mind) — cognitive hub

@Composable
internal fun IsolationTile(uiState: HudUiState, eyePreview: ImageBitmap?, eyeOverlay: EyeOverlay?) {
  val cog = uiState.cognitive
  val fc = uiState.cognitiveForecast
  val color = levelColor(cog.level)
  Column(TilePadding.fillMaxSize().testTag("dashboard_page_mental"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TileHeader(DashboardPage.ISOLATION, cog.level.label.lowercase().replaceFirstChar { it.uppercase() }, color, "cognitive_level_pill")
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      // Hero gauge
      Column(Modifier.width(118.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
          LoadGauge(cog.index, color, Modifier.fillMaxSize(), forecast = fc.projectedIn10Min)
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${cog.index}", style = IosType.largeNumber.copy(fontSize = 34.sp), modifier = Modifier.testTag("cognitive_load_text"))
            Text("Cognitive load", style = IosType.caption)
          }
        }
        Text(cog.topDriver.lowercase().replaceFirstChar { it.uppercase() }, style = hudText(10.sp, color), modifier = Modifier.testTag("cognitive_driver_text"))
        Spacer(Modifier.height(4.dp))
        if (cog.level != CognitiveLevel.NOMINAL) BreathingPacer(color)
        else Text(cog.recommendation, style = IosType.footnote, maxLines = 2)
      }
      // Forecast + eyes
      Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Next 10 min", style = IosType.headline, modifier = Modifier.weight(1f))
          Text(forecastInsight(uiState), style = hudText(9.sp, if ((fc.minutesToCaution ?: 99.0) < 10.0) Ios.Yellow else Ios.Secondary, FontWeight.Medium), modifier = Modifier.testTag("cognitive_forecast_text"))
        }
        TrendChart(fc.history.ifEmpty { listOf(cog.index, cog.index) }, fc.projectedIn10Min, color, Modifier.fillMaxWidth().weight(1f).testTag("cognitive_trend_chart"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          EyePreviewBox(uiState, eyePreview, eyeOverlay, Modifier.width(78.dp).height(58.dp))
          Column(Modifier.weight(1f)) {
            val eye = uiState.eye
            MetricLine("Blinks", "${eye.totalBlinks}", valueTag = "eye_blink_count_text")
            MetricLine("Rate", eye.blinkRatePerMin?.let { fmt("%.0f/min (you %.0f)", it, eye.effectiveBaseline) } ?: "Learning…")
            MetricLine("Eyelids", cog.ocularPattern.lowercase().replaceFirstChar { it.uppercase() }, if (cog.ocularPattern == "NORMAL") Ios.Green else Ios.Yellow)
          }
        }
      }
      // Sleep, rhythm, body
      Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ms = uiState.mentalState
        val sleep = ms.sleepTracker
        GroupedList {
          ListRow(
            "Stress",
            if (ms.stressLevel == StressLevel.UNKNOWN) "—" else "${ms.stressPercent}%",
            if (ms.stressPercent >= 58) Ios.Orange else Ios.Label,
            dot = Ios.Purple,
            valueTag = "cognitive_stress_text",
          )
          ListRow("Heart rate", if (uiState.isBiometricSensorLost) "—" else "${uiState.heartRateBpm} bpm", Ios.Pink, dot = Ios.Pink)
          ListRow(
            "Sleep",
            fmt("%.1f h · %d%%", sleep.durationHours, sleep.qualityPercent),
            if (sleep.durationHours <= 5f) Ios.Orange else Ios.Label,
            dot = Ios.Indigo,
            valueTag = "cognitive_sleep_text",
          )
          val mars = uiState.marsTime
          ListRow("Mars time", mars?.let { "Sol ${it.marsSolDate.toInt()} · ${Mars24.formatHours(it.localMeanSolarTimeHours)}" } ?: "—", dot = Ios.Orange, valueTag = "mars_time_text")
          ListRow("Body clock drift", fmt("+%.0f min/sol", MARS_SOL_DRIFT_MIN), dot = Ios.Teal, separator = false)
        }
        Text(
          fmt("NASA: ISS crews average %.1f h sleep; ≤5 h impairs sustained attention.", NASA_ISS_SLEEP_H),
          style = hudText(7.5.sp, Ios.Tertiary, FontWeight.Normal),
          maxLines = 2,
        )
      }
    }
  }
}

private fun forecastInsight(s: HudUiState): String {
  val fc = s.cognitiveForecast
  val slope = fc.slopePerMin
  val toCaution = fc.minutesToCaution
  return when {
    fc.projectedIn10Min == null -> "Collecting trend…"
    s.cognitive.index >= 70 && slope < -0.3 -> fmt("Recovering %.1f/min", -slope)
    toCaution != null && toCaution in 0.1..30.0 -> fmt("Caution in ~%.0f min", toCaution)
    slope > 0.3 -> fmt("Rising %.1f/min", slope)
    slope < -0.3 -> fmt("Easing %.1f/min", -slope)
    else -> "Stable"
  }
}

@Composable
private fun EyePreviewBox(uiState: HudUiState, bitmap: ImageBitmap?, overlay: EyeOverlay?, modifier: Modifier) {
  Box(modifier.testTag("eye_preview_box").clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
    val status = uiState.eyeTrackingStatus
    if (uiState.eyePreviewEnabled && bitmap != null) {
      Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      if (overlay != null) {
        Canvas(Modifier.fillMaxSize()) {
          val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
          val dw = bitmap.width * scale
          val dh = bitmap.height * scale
          val ox = (size.width - dw) / 2f
          val oy = (size.height - dh) / 2f
          fun pt(p: Pair<Float, Float>) = Offset(ox + p.first * dw, oy + p.second * dh)
          listOf(overlay.leftIris, overlay.rightIris).forEach { drawCircle(Ios.Cyan, 2.4.dp.toPx(), pt(it), style = Stroke(1.2.dp.toPx())) }
        }
      }
    } else {
      val text =
        when {
          status == EyeTrackingStatus.UNSUPPORTED_DEVICE -> "Eye tracking\nunsupported"
          status == EyeTrackingStatus.MODEL_ERROR -> "Eye model\nunavailable"
          status == EyeTrackingStatus.OFF -> "Eyes off"
          !uiState.eyePreviewEnabled -> "Tracking\npreview hidden"
          !uiState.eye.faceDetected -> "Finding eyes…"
          else -> "Tracking…"
        }
      Text(text, style = hudText(8.sp, Ios.Secondary, FontWeight.Medium), maxLines = 2)
    }
    if (uiState.eye.calibrationProgress < 1f && status == EyeTrackingStatus.ACTIVE) {
      LevelBar(uiState.eye.calibrationProgress, Ios.Cyan, Modifier.align(Alignment.BottomCenter).padding(4.dp), height = 3.dp)
    }
  }
}

/** Box breathing (4 s in / hold / out / hold) when load reaches caution. */
@Composable
private fun BreathingPacer(color: Color) {
  val t by rememberInfiniteTransition(label = "breath").animateFloat(0f, 4f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "breath_t")
  val phase = t.toInt().coerceIn(0, 3)
  val frac = t - phase
  val r =
    when (phase) {
      0 -> 0.35f + 0.65f * frac
      1 -> 1f
      2 -> 1f - 0.65f * frac
      else -> 0.35f
    }
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("breathing_pacer")) {
    Canvas(Modifier.size(20.dp)) {
      drawCircle(color.copy(alpha = 0.2f), size.minDimension / 2f)
      drawCircle(color, size.minDimension / 2f * r)
    }
    Spacer(Modifier.width(6.dp))
    Text(listOf("Breathe in", "Hold", "Breathe out", "Hold")[phase], style = hudText(10.sp, color))
  }
}

// ===================================================================== H3 · DISTANCE FROM EARTH (mind)

@Composable
internal fun DistanceTile(uiState: HudUiState) {
  val link = uiState.earthLink
  val c = uiState.consumables
  val turnBackColor =
    when {
      c.turnBackInMinutes <= 0 -> Ios.Red
      c.turnBackInMinutes <= 30 -> Ios.Orange
      else -> Ios.Green
    }
  val drill = uiState.activeDrill
  Column(TilePadding.fillMaxSize().testTag("dashboard_page_distance"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TileHeader(
      DashboardPage.DISTANCE,
      if (link.solarConjunction) "Comms blackout" else "Act locally",
      if (link.solarConjunction) Ios.Red else Ios.Teal,
      "distance_status_pill",
    )
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Signal to Earth", style = IosType.caption)
        Row(verticalAlignment = Alignment.Bottom) {
          Text(fmt("%.1f", link.oneWayDelaySec / 60.0), style = IosType.largeNumber.copy(color = Ios.Teal), modifier = Modifier.testTag("earth_delay_text"))
          Spacer(Modifier.width(4.dp))
          Text("min one way", style = IosType.footnote, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(fmt("Round trip %.0f min · %.2f AU", link.oneWayDelaySec / 30.0, link.distanceAu), style = IosType.footnote, maxLines = 1)
        Text("Turn back in", style = IosType.caption, modifier = Modifier.padding(top = 4.dp))
        Text(
          if (c.turnBackInMinutes <= 0) "Now" else formatMinutes(c.turnBackInMinutes),
          style = IosType.largeNumber.copy(fontSize = 22.sp, color = turnBackColor),
          modifier = Modifier.testTag("turn_back_text"),
        )
        Text(
          "${c.limitingFactor} limits · ${AxemuProfile.RETURN_RESERVE_MIN.toInt()} min reserve",
          style = IosType.footnote,
          maxLines = 1,
        )
      }
      Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupedList {
          ListRow("Oxygen left", formatMinutes(c.oxygenMinutesLeft), if (uiState.suit.isOxygenLow) Ios.Red else Ios.Label, dot = Ios.Cyan)
          ListRow("Battery left", formatMinutes(c.batteryMinutesLeft), if (uiState.suit.isLowPower) Ios.Red else Ios.Label, dot = Ios.Green)
          ListRow("AxEMU 8 h rating", formatMinutes(c.ratedEvaMinutesLeft), dot = Ios.Blue)
          ListRow("Walk back", formatDuration(uiState.radiation.etaToBaseSec), dot = Ios.Orange, separator = false)
        }
        GroupedList {
          if (drill == EmergencyDrill.NONE) {
            ListRow("Emergencies", "None active", Ios.Green, dot = Ios.Red, separator = false)
          } else {
            ListRow(drill.code, drill.title, Ios.Red, dot = Ios.Red)
            checklistFor(drill).take(2).forEachIndexed { i, step -> ListRow("${i + 1}.", step, separator = i < 1) }
          }
        }
        Text("Earth can't answer in time. Decide here, then report.", style = IosType.footnote)
      }
    }
  }
}

/** Short self-sufficiency prompts for training drills (not official NASA procedures). */
internal fun checklistFor(drill: EmergencyDrill): List<String> =
  when (drill) {
    EmergencyDrill.EMERGENCY_1 -> listOf("Stop work, stay calm", "Watch suit pressure trend", "Head to base now")
    EmergencyDrill.EMERGENCY_2 -> listOf("Slow breathing", "Switch to secondary O₂", "Head to base now")
    EmergencyDrill.EMERGENCY_3 -> listOf("Follow base arrow", "Use terrain walls", "Shelter until clear")
    EmergencyDrill.EMERGENCY_4 -> listOf("Rest to cut CO₂", "Check fan & scrubber", "Return if > 6 mmHg")
    EmergencyDrill.EMERGENCY_5 -> listOf("Shed non-essential load", "Lights low", "Return on reserve")
    EmergencyDrill.EMERGENCY_6 -> listOf("Pause the task", "Box-breathe 2 min", "Re-check load")
    EmergencyDrill.EMERGENCY_7 -> listOf("Face away from gusts", "Slow your pace", "Cool down, hydrate")
    EmergencyDrill.EMERGENCY_8 -> listOf("Drink now", "Reduce exertion", "Re-check core temp")
    EmergencyDrill.EMERGENCY_9 -> listOf("Re-seat biosensor", "Report status by voice", "Buddy check")
    EmergencyDrill.EMERGENCY_10 -> listOf("Stop, stabilise", "Secondary O₂", "Return to base now")
    EmergencyDrill.NONE -> emptyList()
  }

// ===================================================================== H4 · GRAVITY (body maintenance)

@Composable
internal fun GravityTile(uiState: HudUiState) {
  val b = uiState.body
  val distFrac = (uiState.distanceMeters / uiState.distanceGoalMeters.coerceAtLeast(100f)).coerceIn(0f, 1f)
  val riskColor =
    when (b.atrophyRisk) {
      "On track" -> Ios.Green
      "Moderate" -> Ios.Yellow
      else -> Ios.Orange
    }
  Column(TilePadding.fillMaxSize().testTag("dashboard_page_gravity"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TileHeader(DashboardPage.GRAVITY, b.atrophyRisk, riskColor, "gravity_status_pill")
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(118.dp).testTag("gravity_rings"), contentAlignment = Alignment.Center) {
        ActivityRing(b.loadGoalFraction, Ios.Pink, Modifier.fillMaxSize(), 11.dp)
        ActivityRing(b.exerciseGoalFraction, Ios.Green, Modifier.fillMaxSize().padding(14.dp), 11.dp)
        ActivityRing(distFrac, Ios.Cyan, Modifier.fillMaxSize().padding(28.dp), 11.dp)
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupedList {
          ListRow("Earth-equivalent load", "${b.earthEquivalentLoadSteps} / 7,500", Ios.Pink, dot = Ios.Pink, valueTag = "gravity_load_text")
          ListRow("Exercise", fmt("%.0f / 150 min", b.exerciseMinutes), Ios.Green, dot = Ios.Green)
          ListRow("Distance", fmt("%.2f / %.1f km", uiState.distanceMeters / 1000f, uiState.distanceGoalMeters / 1000f), Ios.Cyan, dot = Ios.Cyan)
          ListRow("Steps on Mars", "${b.steps}", dot = Ios.Orange, separator = false)
        }
        Text(b.advice, style = hudText(9.5.sp, riskColor, FontWeight.Medium), maxLines = 2)
        Text(
          "At 0.38 g each step loads ~38% of Earth body weight. ISS crews exercise ~2.5 h/day and still lose ~1%/month of spine bone density.",
          style = hudText(7.5.sp, Ios.Tertiary, FontWeight.Normal),
          maxLines = 2,
        )
      }
    }
  }
}

// ===================================================================== H5 · HOSTILE / CLOSED ENVIRONMENT (live)

@Composable
internal fun EnvironmentTile(uiState: HudUiState, onRequestLocationPermission: () -> Unit) {
  val h = uiState.habitability
  val s = uiState.suit
  val alarm = !s.isPressureNominal || s.helmetCo2MmHg >= 6f || s.isOxygenLow
  Column(TilePadding.fillMaxSize().testTag("dashboard_page_environment"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TileHeader(
      DashboardPage.ENVIRONMENT,
      if (alarm) "Check suit" else if (uiState.hasGpsFix) "Live" else "Acquiring",
      if (alarm) Ios.Red else if (uiState.hasGpsFix) Ios.Green else Ios.Yellow,
      "environment_status_pill",
    )
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarsMolaMapCanvas(
          trackPoints = uiState.marsTrackPoints,
          headingDegrees = uiState.headingDegrees,
          hasGpsFix = uiState.hasGpsFix,
          mapZoom = uiState.mapZoom,
          mapTiltDegrees = uiState.mapTiltDegrees,
          mapPanEastDeg = uiState.mapPanEastDeg,
          mapPanNorthDeg = uiState.mapPanNorthDeg,
          isMapPinching = uiState.isMapPinching,
          modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          val d = uiState.distanceMeters.coerceAtLeast(0f)
          Text(if (d >= 1000f) fmt("%.2f", d / 1000f) else "${d.roundToInt()}", style = IosType.headline, modifier = Modifier.testTag("dashboard_distance_text"))
          Text(if (d >= 1000f) "KM" else "METERS", style = IosType.caption, modifier = Modifier.testTag("dashboard_distance_unit_text"))
          Text(fmt("%.1f m/s", uiState.speedMps.coerceAtLeast(0f)), style = hudText(10.sp, Ios.Cyan), modifier = Modifier.testTag("dashboard_speed_text"))
          Spacer(Modifier.weight(1f))
          if (!uiState.hasLocationPermission) {
            Box(
              Modifier.testTag("location_permission_pill")
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(50))
                .background(Ios.Blue)
                .clickable(onClick = onRequestLocationPermission)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
              Text("Enable GPS", style = hudText(10.sp, Color.White))
            }
          }
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Outside · Jezero (Perseverance MEDA model)", style = IosType.caption, maxLines = 1)
        GroupedList {
          ListRow("Mars air", fmt("%.0f °C · %.0f Pa", h.outside.airTempC, h.outside.pressurePa), dot = Ios.Orange, valueTag = "outside_air_text")
          ListRow(
            "Wind · sun",
            fmt("%.1f m/s · %s", uiState.windSpeedMps, if (h.outside.isDaylight) "day" else "night"),
            if (uiState.windSpeedMps >= 15f) Ios.Orange else Ios.Label,
            dot = Ios.Mint,
            separator = false,
          )
        }
        Text("Inside · ${AxemuProfile.NAME} suit", style = IosType.caption)
        GroupedList {
          ListRow("Pressure", fmt("%.2f psi", s.suitPressureTotalPsi), if (s.isPressureNominal) Ios.Green else Ios.Red, dot = Ios.Blue, valueTag = "suit_pressure_text")
          ListRow(
            "Helmet CO₂",
            fmt("%.1f mmHg", s.helmetCo2MmHg),
            when {
              s.helmetCo2MmHg >= 6f -> Ios.Red
              s.helmetCo2MmHg >= 3f -> Ios.Orange
              else -> Ios.Green
            },
            dot = Ios.Purple,
            valueTag = "suit_helmet_co2_text",
          )
          ListRow("Temperature", fmt("%.1f °C", s.suitTempC), dot = Ios.Red)
          ListRow(
            "Light · noise",
            (h.lightLux?.let { fmt("%.0f lx", it) } ?: if (h.helmetLightsAdvised) "Lights on" else "—") + " · " + h.noiseLabel.lowercase(),
            if (h.helmetLightsAdvised) Ios.Yellow else Ios.Label,
            dot = Ios.Yellow,
          )
          ListRow("Cold soak", fmt("%.0f / %.0f min", h.coldSoakMinutes, AxemuProfile.RATED_COLD_SOAK_MIN), if (h.coldSoakMinutes > AxemuProfile.RATED_COLD_SOAK_MIN * 0.75) Ios.Orange else Ios.Label, dot = Ios.Cyan, separator = false)
        }
      }
    }
  }
}

// ===================================================================== DRILLS (look down)

@Composable
internal fun DrillsTile(activeDrill: EmergencyDrill) {
  val drills = EmergencyDrill.activeDrills
  val pillColor =
    when {
      activeDrill == EmergencyDrill.NONE -> Ios.Secondary
      activeDrill.isCritical -> Ios.Red
      else -> Ios.Orange
    }
  Column(TilePadding.fillMaxSize().testTag("dashboard_page_drills"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TileHeader(DashboardPage.DRILLS, if (activeDrill == EmergencyDrill.NONE) "Standby" else "${activeDrill.code} ACTIVE", pillColor, "drills_status_pill")
    val rows = (drills.size + 1) / 2
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (col in 0..1) {
        GroupedList(Modifier.weight(1f)) {
          for (i in 0 until rows) {
            val d = drills.getOrNull(i + col * rows) ?: continue
            val active = d == activeDrill
            Box(Modifier.testTag("drill_item_${d.number}")) {
              ListRow(
                "E${d.number}  ${d.title}",
                d.impactSummary,
                if (active) (if (d.isCritical) Ios.Red else Ios.Orange) else Ios.Secondary,
                dot = if (active) Ios.Red else Ios.Fill,
                separator = i < rows - 1,
              )
            }
          }
        }
      }
    }
    Text("Say \"emergency 1-10\" to rehearse · thumbs down to clear", style = IosType.footnote)
  }
}
