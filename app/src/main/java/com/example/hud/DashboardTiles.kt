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
import com.example.mental.EmergencyDrill
import com.example.mental.StressLevel
import com.example.nasa.SpaceWeatherLevel
import com.example.radiation.RadiationModel
import com.example.radiation.RadiationStatus
import com.example.radiation.ShelterStatus
import com.example.suit.AxemuProfile
import kotlin.math.roundToInt

/*
 * Bento-grid page bodies for the visionOS-style window. Each hazard shows at most ~6 small cards;
 * the window frame (VisionWindow) supplies the header, the persistent mind sidebar and ornaments.
 *
 * Thresholds referenced from NASA: helmet CO2 amber at 3 mmHg (NASA-STD-3001 Vol 2 Rev D 1 h
 * average limit for habitable volumes), red at 6 mmHg; suit pressure nominal 4.3 psi (EMU/xEMU).
 */

private val Gap = 7.dp

/** Header status for each page (also used by the small "peek" cards of neighbouring hazards). */
internal fun pageStatus(page: DashboardPage, s: HudUiState): Pair<String, Color> =
  when (page) {
    DashboardPage.RADIATION -> {
      val sw = s.spaceWeather.level
      when {
        s.radiation.status == RadiationStatus.STORM -> s.radiation.status.label to Ios.Red
        sw == SpaceWeatherLevel.SEP -> "SEP alert" to Ios.Red
        s.radiation.status == RadiationStatus.ELEVATED || sw == SpaceWeatherLevel.ACTIVE -> "Elevated" to Ios.Orange
        else -> "Nominal" to Ios.Green
      }
    }
    DashboardPage.ISOLATION -> s.cognitive.level.label.lowercase().replaceFirstChar { it.uppercase() } to levelColor(s.cognitive.level)
    DashboardPage.DISTANCE ->
      when {
        s.earthLink.solarConjunction -> "Comms blackout" to Ios.Red
        s.consumables.turnBackInMinutes <= 0 -> "Turn back" to Ios.Red
        else -> "Act locally" to Color.White
      }
    DashboardPage.GRAVITY ->
      s.body.atrophyRisk to
        when (s.body.atrophyRisk) {
          "On track" -> Ios.Green
          "Moderate" -> Ios.Yellow
          else -> Ios.Orange
        }
    DashboardPage.ENVIRONMENT -> {
      val suit = s.suit
      when {
        !suit.isPressureNominal || suit.helmetCo2MmHg >= 6f || suit.isOxygenLow -> "Check suit" to Ios.Red
        s.hasGpsFix -> "Live" to Ios.Green
        else -> "Acquiring" to Ios.Yellow
      }
    }
    DashboardPage.DRILLS ->
      if (s.activeDrill == EmergencyDrill.NONE) "Standby" to Vision.Text2
      else "${s.activeDrill.code} ACTIVE" to (if (s.activeDrill.isCritical) Ios.Red else Ios.Yellow)
  }

internal fun pageStatusTag(page: DashboardPage): String =
  if (page == DashboardPage.ISOLATION) "cognitive_level_pill" else "${page.name.lowercase()}_status_pill"

/** One-line headline for a page, shown on its peek card. */
internal fun pageHeadline(page: DashboardPage, s: HudUiState): String =
  when (page) {
    DashboardPage.RADIATION -> fmt("%.0f µSv/h", s.radiation.doseRateUsvPerHour)
    DashboardPage.ISOLATION -> "Load ${s.cognitive.index}"
    DashboardPage.DISTANCE -> fmt("%.0f min to Earth", s.earthLink.oneWayDelaySec / 60.0)
    DashboardPage.GRAVITY -> "${s.body.earthEquivalentLoadSteps} load steps"
    DashboardPage.ENVIRONMENT -> fmt("%.0f °C outside", s.habitability.outside.airTempC)
    DashboardPage.DRILLS -> if (s.activeDrill == EmergencyDrill.NONE) "Standby" else s.activeDrill.title
  }

@Composable
internal fun PageBody(page: DashboardPage, s: HudUiState, eyePreview: ImageBitmap?, eyeOverlay: EyeOverlay?, onRequestLocationPermission: () -> Unit) {
  when (page) {
    DashboardPage.RADIATION -> RadiationBody(s)
    DashboardPage.ISOLATION -> IsolationBody(s, eyePreview, eyeOverlay)
    DashboardPage.DISTANCE -> DistanceBody(s)
    DashboardPage.GRAVITY -> GravityBody(s)
    DashboardPage.ENVIRONMENT -> EnvironmentBody(s, onRequestLocationPermission)
    DashboardPage.DRILLS -> DrillsBody(s.activeDrill)
  }
}

// ===================================================================== H1 · Radiation (mind)

@Composable
private fun RadiationBody(s: HudUiState) {
  val r = s.radiation
  val (_, color) = pageStatus(DashboardPage.RADIATION, s)
  Row(Modifier.fillMaxSize().testTag("dashboard_page_radiation"), horizontalArrangement = Arrangement.spacedBy(Gap)) {
    Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
      BentoCard("Dose rate", Modifier.fillMaxWidth().weight(1f), tint = if (color == Ios.Green) null else color) {
        BigValue(fmt("%.1f", r.doseRateUsvPerHour), "µSv/h", color, 24.sp, "radiation_dose_rate_text")
        val ratio = r.doseRateUsvPerHour / RadiationModel.GCR_BASELINE_USV_PER_H
        LevelBar((0.25f + 0.25f * kotlin.math.log2(ratio.coerceAtLeast(0.25f))).coerceIn(0.03f, 1f), color, height = 3.dp)
        SmallNote(fmt("Mars baseline %.0f µSv/h · Curiosity RAD", RadiationModel.GCR_BASELINE_USV_PER_H))
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        val sw = s.spaceWeather
        BentoCard("Sun · NASA DONKI", Modifier.weight(1f)) {
          BigValue(if (sw.level == SpaceWeatherLevel.UNKNOWN) "Offline" else sw.level.label, size = 13.sp, tag = "space_weather_text")
          SmallNote(sw.sepRiskPercent?.let { "SEP risk 48 h · $it%" } ?: "Syncs when online")
        }
        BentoCard("Hand steadiness", Modifier.weight(1f)) {
          BigValue(
            s.motorSteadiness?.let { "$it" } ?: "Raise a hand",
            if (s.motorSteadiness != null) "/100" else null,
            if ((s.motorSteadiness ?: 100) < 60) Ios.Orange else Ios.Label,
            13.sp,
            "motor_steadiness_text",
          )
          SmallNote("Motor watch for CNS effects")
        }
      }
    }
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
      BentoCard("Shelter route", Modifier.fillMaxWidth().weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          BaseBearingArrow(SpatialLayout.angleDelta(r.bearingToBaseDeg, s.headingDegrees), r.shelter == ShelterStatus.EXPOSED, color, Modifier.size(34.dp))
          Spacer(Modifier.width(8.dp))
          Column {
            Text(
              if (r.shelter == ShelterStatus.SHELTERED) "At base" else fmt("%.0f m · %03.0f°", r.distanceToBaseMeters, r.bearingToBaseDeg),
              style = hudText(13.sp),
              modifier = Modifier.testTag("radiation_base_text"),
            )
            SmallNote(fmt("ETA %s · +%.1f µSv", formatDuration(r.etaToBaseSec), r.returnDoseUsv))
          }
        }
        SmallNote(r.advice, color, maxLines = 2)
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        BentoCard("EVA dose", Modifier.weight(1f)) { BigValue(fmt("%.1f", r.evaDoseUsv), "µSv", size = 14.sp) }
        BentoCard("Career used", Modifier.weight(1f)) { BigValue(fmt("%.3f", r.careerLimitFraction * 100f), "%", size = 14.sp) }
      }
    }
  }
}

@Composable
private fun BaseBearingArrow(relativeDeg: Float, active: Boolean, color: Color, modifier: Modifier) {
  val animated by animateFloatAsState(relativeDeg, tween(250, easing = FastOutSlowInEasing), label = "base_arrow")
  Box(modifier.testTag("base_bearing_arrow").clip(RoundedCornerShape(50)).background(Vision.Well), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize().padding(7.dp).rotate(if (active) animated else 0f)) {
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

// ===================================================================== H2 · Isolation (cognitive)

@Composable
private fun IsolationBody(s: HudUiState, eyePreview: ImageBitmap?, eyeOverlay: EyeOverlay?) {
  val cog = s.cognitive
  val fc = s.cognitiveForecast
  val color = levelColor(cog.level)
  val ms = s.mentalState
  Row(Modifier.fillMaxSize().testTag("dashboard_page_mental"), horizontalArrangement = Arrangement.spacedBy(Gap)) {
    Column(Modifier.weight(1.5f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
      BentoCard("Cognitive load · next 10 min", Modifier.fillMaxWidth().weight(1f)) {
        Text(
          forecastInsight(s),
          style = hudText(10.sp, if ((fc.minutesToCaution ?: 99.0) < 10.0) Ios.Yellow else Ios.Label),
          modifier = Modifier.testTag("cognitive_forecast_text"),
        )
        TrendChart(
          fc.history.ifEmpty { listOf(cog.index, cog.index) },
          fc.projectedIn10Min,
          color,
          Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp).testTag("cognitive_trend_chart"),
        )
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        BentoCard("Stress", Modifier.weight(1f)) {
          BigValue(if (ms.stressLevel == StressLevel.UNKNOWN) "—" else "${ms.stressPercent}", "%", if (ms.stressPercent >= 58) Ios.Orange else Ios.Label, 16.sp, "cognitive_stress_text")
        }
        BentoCard("Sleep", Modifier.weight(1f)) {
          BigValue(fmt("%.1f", ms.sleepTracker.durationHours), "h", if (ms.sleepTracker.durationHours <= 5f) Ios.Orange else Ios.Label, 16.sp, "cognitive_sleep_text")
        }
        BentoCard("Heart rate", Modifier.weight(1f)) {
          BigValue(if (s.isBiometricSensorLost) "—" else "${s.heartRateBpm}", null, Ios.Pink, 16.sp)
        }
      }
    }
    BentoCard("Eyes · front camera", Modifier.weight(1f).fillMaxHeight()) {
      EyePreviewBox(s, eyePreview, eyeOverlay, Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp))
      val eye = s.eye
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
          SmallNote("Blinks")
          Text("${eye.totalBlinks}", style = hudText(13.sp), modifier = Modifier.testTag("eye_blink_count_text"))
        }
        Column(horizontalAlignment = Alignment.End) {
          SmallNote(eye.blinkRatePerMin?.let { fmt("you %.0f/min", eye.effectiveBaseline) } ?: "learning")
          Text(eye.blinkRatePerMin?.let { fmt("%.0f/min", it) } ?: "—", style = hudText(13.sp))
        }
      }
      SmallNote(cog.ocularPattern.lowercase().replaceFirstChar { it.uppercase() }, if (cog.ocularPattern == "NORMAL") Ios.Green else Ios.Yellow)
    }
  }
}

internal fun forecastInsight(s: HudUiState): String {
  val fc = s.cognitiveForecast
  val slope = fc.slopePerMin
  val toCaution = fc.minutesToCaution
  return when {
    fc.projectedIn10Min == null -> "Collecting trend…"
    s.cognitive.index >= 70 && slope < -0.3 -> fmt("Recovering %.1f/min", -slope)
    toCaution != null && toCaution in 0.1..30.0 -> fmt("Caution in ~%.0f min", toCaution)
    slope > 0.3 -> fmt("Rising %.1f/min", slope)
    slope < -0.3 -> fmt("Easing %.1f/min", -slope)
    else -> "Stable · ${fc.projectedIn10Min} expected"
  }
}

@Composable
private fun EyePreviewBox(s: HudUiState, bitmap: ImageBitmap?, overlay: EyeOverlay?, modifier: Modifier) {
  Box(modifier.testTag("eye_preview_box").clip(RoundedCornerShape(12.dp)).background(Vision.Well), contentAlignment = Alignment.Center) {
    val status = s.eyeTrackingStatus
    if (s.eyePreviewEnabled && bitmap != null) {
      Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      if (overlay != null) {
        Canvas(Modifier.fillMaxSize()) {
          val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
          val dw = bitmap.width * scale
          val dh = bitmap.height * scale
          val ox = (size.width - dw) / 2f
          val oy = (size.height - dh) / 2f
          listOf(overlay.leftIris, overlay.rightIris).forEach {
            drawCircle(Ios.Cyan, 2.4.dp.toPx(), Offset(ox + it.first * dw, oy + it.second * dh), style = Stroke(1.2.dp.toPx()))
          }
        }
      }
    } else {
      val text =
        when {
          status == EyeTrackingStatus.UNSUPPORTED_DEVICE -> "Eye tracking unsupported"
          status == EyeTrackingStatus.MODEL_ERROR -> "Eye model unavailable"
          status == EyeTrackingStatus.OFF -> "Eyes off"
          !s.eyePreviewEnabled -> "Tracking · preview hidden"
          !s.eye.faceDetected -> "Finding eyes…"
          else -> "Tracking…"
        }
      SmallNote(text, Vision.Text2, maxLines = 2)
    }
    if (s.eye.calibrationProgress < 1f && status == EyeTrackingStatus.ACTIVE) {
      LevelBar(s.eye.calibrationProgress, Ios.Cyan, Modifier.align(Alignment.BottomCenter).padding(4.dp), height = 3.dp)
    }
  }
}

/** Box breathing (4 s in / hold / out / hold) when load reaches caution. */
@Composable
internal fun BreathingPacer(color: Color, modifier: Modifier = Modifier) {
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
  Row(modifier.testTag("breathing_pacer"), verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(14.dp)) {
      drawCircle(color.copy(alpha = 0.22f), size.minDimension / 2f)
      drawCircle(color, size.minDimension / 2f * r)
    }
    Spacer(Modifier.width(5.dp))
    Text(listOf("Breathe in", "Hold", "Breathe out", "Hold")[phase], style = hudText(8.5.sp, color))
  }
}

// ===================================================================== H3 · Distance (mind)

@Composable
private fun DistanceBody(s: HudUiState) {
  val c = s.consumables
  val link = s.earthLink
  val turnColor =
    when {
      c.turnBackInMinutes <= 0 -> Ios.Red
      c.turnBackInMinutes <= 30 -> Ios.Orange
      else -> Ios.Green
    }
  Row(Modifier.fillMaxSize().testTag("dashboard_page_distance"), horizontalArrangement = Arrangement.spacedBy(Gap)) {
    Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
      BentoCard("Turn back in", Modifier.fillMaxWidth().weight(1f), tint = if (turnColor == Ios.Green) null else turnColor) {
        BigValue(if (c.turnBackInMinutes <= 0) "Now" else formatMinutes(c.turnBackInMinutes), color = turnColor, size = 24.sp, tag = "turn_back_text")
        SmallNote("${c.limitingFactor} limits · ${AxemuProfile.RETURN_RESERVE_MIN.toInt()} min reserve · ${if (c.usesObservedTrend) "observed trend" else "suit model"}")
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        BentoCard("Oxygen", Modifier.weight(1f)) { BigValue(formatDuration((c.oxygenMinutesLeft * 60).toInt()), color = if (s.suit.isOxygenLow) Ios.Red else Ios.Label, size = 12.sp) }
        BentoCard("Battery", Modifier.weight(1f)) { BigValue(formatDuration((c.batteryMinutesLeft * 60).toInt()), color = if (s.suit.isLowPower) Ios.Red else Ios.Label, size = 12.sp) }
        BentoCard("Walk back", Modifier.weight(1f)) { BigValue(formatDuration(s.radiation.etaToBaseSec), size = 12.sp) }
      }
    }
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
      BentoCard("Signal to Earth", Modifier.fillMaxWidth()) {
        BigValue(fmt("%.1f", link.oneWayDelaySec / 60.0), "min one way", Ios.Teal, 18.sp, "earth_delay_text")
        SmallNote(fmt("Round trip %.0f min · %.2f AU · JPL", link.oneWayDelaySec / 30.0, link.distanceAu))
      }
      val drill = s.activeDrill
      BentoCard(
        if (drill == EmergencyDrill.NONE) "Self-sufficiency" else "${drill.code} · ${drill.title}",
        Modifier.fillMaxWidth().weight(1f),
        tint = if (drill == EmergencyDrill.NONE) null else Ios.Red,
      ) {
        if (drill == EmergencyDrill.NONE) {
          SmallNote("Earth can't answer in time. Decide here, then report.", Vision.Text2, maxLines = 2)
        } else {
          checklistFor(drill).forEachIndexed { i, step -> SmallNote("${i + 1}. $step", Ios.Label) }
        }
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

// ===================================================================== H4 · Gravity (body)

@Composable
private fun GravityBody(s: HudUiState) {
  val b = s.body
  val distFrac = (s.distanceMeters / s.distanceGoalMeters.coerceAtLeast(100f)).coerceIn(0f, 1f)
  val (_, riskColor) = pageStatus(DashboardPage.GRAVITY, s)
  Row(Modifier.fillMaxSize().testTag("dashboard_page_gravity"), horizontalArrangement = Arrangement.spacedBy(Gap)) {
    BentoCard("Activity · 0.38 g", Modifier.weight(1.35f).fillMaxHeight()) {
      Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).testTag("gravity_rings"), contentAlignment = Alignment.Center) {
          ActivityRing(b.loadGoalFraction, Color.White, Modifier.fillMaxSize(), 9.dp)
          ActivityRing(b.exerciseGoalFraction, Color.White.copy(alpha = 0.72f), Modifier.fillMaxSize().padding(11.dp), 9.dp)
          ActivityRing(distFrac, Color.White.copy(alpha = 0.48f), Modifier.fillMaxSize().padding(22.dp), 9.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
          RingLegend("Load", "${b.earthEquivalentLoadSteps} / 7,500", Ios.Pink, "gravity_load_text")
          RingLegend("Exercise", fmt("%.0f / 150 min", b.exerciseMinutes), Vision.Text2)
          RingLegend("Distance", fmt("%.2f / %.1f km", s.distanceMeters / 1000f, s.distanceGoalMeters / 1000f), Vision.Text3)
        }
      }
      SmallNote(b.advice, riskColor, maxLines = 2)
    }
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
      BentoCard("Steps on Mars", Modifier.fillMaxWidth()) { BigValue("${b.steps}", "steps", size = 18.sp) }
      BentoCard("Bone & muscle", Modifier.fillMaxWidth().weight(1f)) {
        BigValue(b.atrophyRisk, color = riskColor, size = 14.sp)
        SmallNote("Each step loads ~38% of Earth weight. ISS crews exercise ~2.5 h/day and still lose ~1%/month spine bone.", maxLines = 3)
      }
    }
  }
}

@Composable
private fun RingLegend(label: String, value: String, color: Color, tag: String? = null) {
  Column {
    Text(label, style = hudText(8.sp, color, FontWeight.Medium))
    Text(value, style = hudText(11.sp), modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
  }
}

// ===================================================================== H5 · Environment (live)

@Composable
private fun EnvironmentBody(s: HudUiState, onRequestLocationPermission: () -> Unit) {
  val h = s.habitability
  val suit = s.suit
  Row(Modifier.fillMaxSize().testTag("dashboard_page_environment"), horizontalArrangement = Arrangement.spacedBy(Gap)) {
    BentoCard("Live map · Jezero", Modifier.weight(1.3f).fillMaxHeight()) {
      MarsMolaMapCanvas(
        trackPoints = s.marsTrackPoints,
        headingDegrees = s.headingDegrees,
        hasGpsFix = s.hasGpsFix,
        mapZoom = s.mapZoom,
        mapTiltDegrees = s.mapTiltDegrees,
        mapPanEastDeg = s.mapPanEastDeg,
        mapPanNorthDeg = s.mapPanNorthDeg,
        isMapPinching = s.isMapPinching,
        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp),
      )
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val d = s.distanceMeters.coerceAtLeast(0f)
        Text(if (d >= 1000f) fmt("%.2f", d / 1000f) else "${d.roundToInt()}", style = hudText(11.sp), modifier = Modifier.testTag("dashboard_distance_text"))
        Text(if (d >= 1000f) "KM" else "METERS", style = hudText(7.5.sp, Vision.Text2), modifier = Modifier.testTag("dashboard_distance_unit_text"))
        Text(fmt("%.1f m/s", s.speedMps.coerceAtLeast(0f)), style = hudText(9.sp, Ios.Cyan), modifier = Modifier.testTag("dashboard_speed_text"))
        Spacer(Modifier.weight(1f))
        if (!s.hasLocationPermission) {
          Box(
            Modifier.testTag("location_permission_pill")
              .minimumInteractiveComponentSize()
              .clip(RoundedCornerShape(50))
              .background(Ios.Blue)
              .clickable(onClick = onRequestLocationPermission)
              .padding(horizontal = 8.dp, vertical = 3.dp),
          ) {
            Text("Enable GPS", style = hudText(9.sp, Color.White))
          }
        }
      }
    }
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        BentoCard("Air °C", Modifier.weight(1f)) {
          BigValue(fmt("%.0f", h.outside.airTempC), size = 15.sp)
          SmallNote(fmt("%.0f Pa", h.outside.pressurePa), tag = "outside_air_text")
        }
        BentoCard("Suit psi", Modifier.weight(1f)) {
          BigValue(fmt("%.2f", suit.suitPressureTotalPsi), null, if (suit.isPressureNominal) Ios.Label else Ios.Red, 15.sp, "suit_pressure_text")
          SmallNote(fmt("%.0f °C in", suit.suitTempC))
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        BentoCard("CO₂ mmHg", Modifier.weight(1f)) {
          BigValue(
            fmt("%.1f", suit.helmetCo2MmHg),
            null,
            when {
              suit.helmetCo2MmHg >= 6f -> Ios.Red
              suit.helmetCo2MmHg >= 3f -> Ios.Orange
              else -> Ios.Label
            },
            15.sp,
            "suit_helmet_co2_text",
          )
        }
        BentoCard("Cold min", Modifier.weight(1f)) {
          BigValue(fmt("%.0f", h.coldSoakMinutes), "/ ${AxemuProfile.RATED_COLD_SOAK_MIN.toInt()}", size = 15.sp)
        }
      }
      BentoCard("Light · noise · ${AxemuProfile.NAME}", Modifier.fillMaxWidth().weight(1f)) {
        SmallNote(
          listOfNotNull(
              h.lightLux?.let { fmt("%.0f lx", it) },
              if (h.helmetLightsAdvised) "helmet lights on" else null,
              h.noiseLabel.takeIf { it != "—" }?.lowercase(),
              if (h.outside.isDaylight) "daylight" else "night",
            )
            .joinToString(" · "),
          Ios.Label,
        )
      }
    }
  }
}

// ===================================================================== Drills (look down / + button)

@Composable
private fun DrillsBody(activeDrill: EmergencyDrill) {
  val drills = EmergencyDrill.activeDrills
  val rows = (drills.size + 1) / 2
  Column(Modifier.fillMaxSize().testTag("dashboard_page_drills"), verticalArrangement = Arrangement.spacedBy(Gap)) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Gap)) {
      for (col in 0..1) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          for (i in 0 until rows) {
            val d = drills.getOrNull(i + col * rows) ?: continue
            val active = d == activeDrill
            val accent = if (d.isCritical) Ios.Red else Ios.Orange
            Row(
              Modifier.fillMaxWidth()
                .weight(1f)
                .testTag("drill_item_${d.number}")
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) accent.copy(alpha = 0.28f) else Vision.Card)
                .padding(horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("E${d.number}", style = hudText(9.sp, if (active) accent else Vision.Text2), modifier = Modifier.width(24.dp))
              Text(d.title, style = hudText(9.sp), maxLines = 1, modifier = Modifier.weight(1f))
              Text(d.impactSummary, style = hudText(7.5.sp, Vision.Text3, FontWeight.Normal), maxLines = 1)
            }
          }
        }
      }
    }
    SmallNote("Say \"emergency 1-10\" to rehearse · thumbs down to clear")
  }
}
