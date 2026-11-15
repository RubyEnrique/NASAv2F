package com.example.hud

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.EyeOverlay
import com.example.eye.EyeTrackingStatus
import com.example.mental.CognitiveLevel
import com.example.mental.EmergencyDrill
import com.example.mental.StressLevel
import com.example.radiation.RadiationModel
import com.example.radiation.RadiationStatus
import com.example.radiation.ShelterStatus
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfBlue
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.IosSfIndigo
import com.example.ui.theme.IosSfPink
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SpecularWhite
import kotlin.math.roundToInt

// ===================================================================== 1. MENTAL HEALTH

@Composable
internal fun MentalTile(uiState: HudUiState, eyePreview: ImageBitmap?, eyeOverlay: EyeOverlay?) {
  val cog = uiState.cognitive
  val color = levelColor(cog.level)
  val eye = uiState.eye
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).testTag("dashboard_page_mental"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    TileHeader("MENTAL HEALTH", "COGNITIVE LOAD · EYES · STRESS · SLEEP", cog.level.label, color, "cognitive_level_pill")
    Hairline()
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      // Load gauge + guidance
      Column(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp)) {
          LoadGauge(cog.index, color, Modifier.fillMaxSize())
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${cog.index}", style = hudText(26.sp, color), modifier = Modifier.testTag("cognitive_load_text"))
            Text("LOAD", style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium, 1.sp))
          }
        }
        Text(cog.topDriver, style = hudText(8.5.sp, color), modifier = Modifier.testTag("cognitive_driver_text"))
        Spacer(Modifier.height(3.dp))
        if (cog.level != CognitiveLevel.NOMINAL) {
          BreathingPacer(color)
        } else {
          Text(cog.recommendation, style = hudText(7.5.sp, IosLabelSecondary, FontWeight.Medium), maxLines = 2)
        }
      }
      // Hidden front camera (preview shown only on this tile)
      Column(modifier = Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        EyePreviewBox(uiState, eyePreview, eyeOverlay, Modifier.fillMaxWidth().height(70.dp))
        MetricLine("BLINKS", "${eye.totalBlinks}", valueTag = "eye_blink_count_text")
        MetricLine(
          "RATE",
          eye.blinkRatePerMin?.let { fmt("%.1f/min · base %.0f", it, eye.effectiveBaseline) } ?: "—",
          valueColor = if (cog.ocularPattern == "BLINK SUPPRESSION") IosSfAmber else IosLabelPrimary,
        )
        MetricLine(
          "PERCLOS · DUR",
          (eye.perclos?.let { fmt("%.0f%%", it * 100f) } ?: "—") + " · " + (eye.meanBlinkDurationMs?.let { fmt("%.0fms", it) } ?: "—"),
          valueColor = if (cog.ocularPattern == "FATIGUE") IosSfAmber else IosLabelPrimary,
        )
      }
      // Load composition
      Column(modifier = Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ComponentBar("OCULAR", cog.ocular, IosSfBlue)
        ComponentBar("PHYSIO", cog.physiological, IosSfPink)
        ComponentBar("ENVIRON", cog.environment, IosSfAmber)
        ComponentBar("SLEEP", cog.sleep, IosSfIndigo)
        Hairline()
        val ms = uiState.mentalState
        MetricLine(
          "STRESS",
          if (ms.stressLevel == StressLevel.UNKNOWN) "--" else "${ms.stressPercent}% ${ms.stressLevel.name}",
          valueColor = if (ms.stressPercent >= 58) IosSfAmber else IosLabelPrimary,
          valueTag = "cognitive_stress_text",
        )
        MetricLine(
          "SLEEP",
          fmt("%.1fh · %d%%", ms.sleepTracker.durationHours, ms.sleepTracker.qualityPercent),
          valueTag = "cognitive_sleep_text",
        )
      }
    }
  }
}

@Composable
private fun ComponentBar(label: String, value: Int?, color: Color) {
  Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium))
      Text(value?.toString() ?: "—", style = hudText(7.5.sp, if (value == null) IosLabelSecondary else IosLabelPrimary))
    }
    LevelBar((value ?: 0) / 100f, if (value == null) IosLabelSecondary else color)
  }
}

@Composable
private fun EyePreviewBox(uiState: HudUiState, bitmap: ImageBitmap?, overlay: EyeOverlay?, modifier: Modifier) {
  val shape = RoundedCornerShape(12.dp)
  Box(
    modifier =
      modifier.testTag("eye_preview_box").clip(shape).background(ObsidianBlack.copy(alpha = 0.55f)).border(0.6.dp, SpecularWhite.copy(alpha = 0.2f), shape),
    contentAlignment = Alignment.Center,
  ) {
    val status = uiState.eyeTrackingStatus
    if (uiState.eyePreviewEnabled && bitmap != null) {
      Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      if (overlay != null) {
        Canvas(Modifier.fillMaxSize()) {
          // Crop maps the bitmap's centre band; approximate overlay with the same aspect fill.
          val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
          val dw = bitmap.width * scale
          val dh = bitmap.height * scale
          val ox = (size.width - dw) / 2f
          val oy = (size.height - dh) / 2f
          fun pt(p: Pair<Float, Float>) = Offset(ox + p.first * dw, oy + p.second * dh)
          overlay.eyeContour.forEach { drawCircle(SpecularWhite.copy(alpha = 0.7f), 1.2.dp.toPx(), pt(it)) }
          listOf(overlay.leftIris, overlay.rightIris).forEach {
            drawCircle(IosSfBlue, 2.6.dp.toPx(), pt(it), style = Stroke(1.2.dp.toPx()))
          }
        }
      }
      Row(Modifier.align(Alignment.TopStart).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(IosSfRed, 5.dp)
        Spacer(Modifier.width(3.dp))
        Text("EYE CAM", style = hudText(6.5.sp))
      }
    } else {
      val text =
        when {
          status == EyeTrackingStatus.UNSUPPORTED_DEVICE -> "FRONT+REAR CAMERAS\nNOT CONCURRENT ON THIS DEVICE"
          status == EyeTrackingStatus.MODEL_ERROR -> "EYE MODEL UNAVAILABLE"
          status == EyeTrackingStatus.OFF -> "EYE TRACKING OFF"
          !uiState.eyePreviewEnabled -> "TRACKING · PREVIEW HIDDEN\nV-SIGN TO SHOW"
          !uiState.eye.faceDetected -> "LOOKING FOR EYES…"
          else -> "TRACKING…"
        }
      Text(text, style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium), maxLines = 2)
    }
    if (uiState.eye.calibrationProgress < 1f && status == EyeTrackingStatus.ACTIVE) {
      LevelBar(uiState.eye.calibrationProgress, IosSfBlue, Modifier.align(Alignment.BottomCenter).padding(4.dp))
    }
  }
}

/** Box-breathing pacer (4 s in / hold / out / hold) shown whenever load reaches CAUTION. */
@Composable
private fun BreathingPacer(color: Color) {
  val transition = rememberInfiniteTransition(label = "breath")
  val t by transition.animateFloat(0f, 4f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "breath_t")
  val phase = t.toInt().coerceIn(0, 3)
  val frac = t - phase
  val radius =
    when (phase) {
      0 -> 0.35f + 0.65f * frac
      1 -> 1f
      2 -> 1f - 0.65f * frac
      else -> 0.35f
    }
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("breathing_pacer")) {
    Canvas(Modifier.size(22.dp)) {
      drawCircle(color.copy(alpha = 0.18f), size.minDimension / 2f)
      drawCircle(color, size.minDimension / 2f * radius, style = Stroke(1.6.dp.toPx()))
    }
    Spacer(Modifier.width(5.dp))
    Text(listOf("BREATHE IN", "HOLD", "BREATHE OUT", "HOLD")[phase], style = hudText(8.sp, color))
  }
}

// ===================================================================== 2. VITALS (crew + EMU)

@Composable
internal fun VitalsTile(uiState: HudUiState) {
  val s = uiState.suit
  val lost = uiState.isBiometricSensorLost
  val (pill, pillColor) =
    when {
      lost -> "SIGNAL LOST" to IosSfRed
      !s.isPressureNominal -> "PRESSURE WARN" to IosSfRed
      s.isOxygenLow -> "O₂ LOW" to IosSfRed
      s.isLowPower -> "LOW POWER" to IosSfRed
      s.helmetCo2MmHg >= 6f -> "CO₂ CRITICAL" to IosSfRed
      s.isFanFault -> "FAN FAULT" to IosSfAmber
      uiState.heartRateBpm >= 135 || uiState.systolicBp >= 140 -> "ELEVATED" to IosSfAmber
      s.helmetCo2MmHg >= 3f -> "CO₂ HIGH" to IosSfAmber
      uiState.heartRateBpm >= 105 -> "ACTIVE" to IosSfBlue
      else -> "NOMINAL" to IosSfGreen
    }
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).testTag("dashboard_page_vitals"),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    TileHeader("VITALS", "CREW BIOMED · EMU LIFE SUPPORT (NASA SUITS TELEMETRY)", pill, pillColor, "vitals_status_pill")
    Hairline()
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Column(Modifier.weight(1f)) {
        Text("CREW", style = hudText(7.5.sp, IosSfPink, spacing = 1.sp))
        MetricLine("HEART RATE", if (lost) "--" else "${uiState.heartRateBpm} bpm", if (lost) IosLabelSecondary else IosSfPink, valueTag = "vitals_tile_hr_text")
        MetricLine("BLOOD PRESS.", if (lost) "--/--" else "${uiState.systolicBp}/${uiState.diastolicBp}", if (uiState.systolicBp >= 135 && !lost) IosSfAmber else IosLabelPrimary)
        MetricLine("SpO₂", if (lost) "--" else "${uiState.spo2Percent}%", if (!lost && uiState.spo2Percent < 93) IosSfRed else IosLabelPrimary)
        MetricLine("CORE TEMP", fmt("%.1f°C", uiState.coreTempCelsius), if (uiState.coreTempCelsius >= 38f) IosSfAmber else IosLabelPrimary)
        MetricLine("HYDRATION", fmt("%d%% · %.1fL", uiState.hydrationPercent, uiState.hydrationLiters), if (uiState.hydrationPercent < 35) IosSfRed else IosSfIndigo)
        MetricLine("O₂ USE", fmt("%.3f psi/min", s.oxyConsumptionPsiMin))
        MetricLine("CO₂ OUTPUT", fmt("%.3f psi/min", s.co2ProductionPsiMin))
        MetricLine("SUIT TEMP", fmt("%.1f°C", s.suitTempC))
      }
      Column(Modifier.weight(1.1f)) {
        Text("SUIT (EMU)", style = hudText(7.5.sp, IosSfBlue, spacing = 1.sp))
        MetricLine(
          "SUIT PRESS.",
          fmt("%.2f psi (O₂ %.2f)", s.suitPressureTotalPsi, s.suitPressureOxyPsi),
          if (s.isPressureNominal) IosSfGreen else IosSfRed,
          valueTag = "suit_pressure_text",
        )
        MetricLine(
          "HELMET CO₂",
          fmt("%.1f mmHg", s.helmetCo2MmHg),
          when {
            s.helmetCo2MmHg >= 6f -> IosSfRed
            s.helmetCo2MmHg >= 3f -> IosSfAmber
            else -> IosSfGreen
          },
          valueTag = "suit_helmet_co2_text",
        )
        MetricLine(
          "O₂ PRI · SEC",
          fmt("%.0f%% · %.0f%% · %s", s.oxyPriStoragePct, s.oxySecStoragePct, formatDuration(s.oxyTimeLeftSec)),
          if (s.isOxygenLow) IosSfRed else IosSfBlue,
        )
        MetricLine(
          "BATT PRI · SEC",
          fmt("%.0f%% · %.0f%% · %s", s.primaryBatteryPct, s.secondaryBatteryPct, formatDuration(s.batteryTimeLeftSec)),
          if (s.isLowPower) IosSfRed else IosSfGreen,
          valueTag = "suit_pwr_percent_text",
        )
        MetricLine(
          "SCRUBBER A · B",
          fmt("%.0f%% · %.0f%% (%c)", s.scrubberACo2StoragePct, s.scrubberBCo2StoragePct, s.activeScrubber),
          if (maxOf(s.scrubberACo2StoragePct, s.scrubberBCo2StoragePct) >= 90f) IosSfAmber else IosLabelPrimary,
        )
        MetricLine("FAN PRI · SEC", fmt("%.0fk · %.0fk rpm", s.fanPriRpm / 1000f, s.fanSecRpm / 1000f), if (s.isFanFault) IosSfRed else IosLabelPrimary)
        MetricLine("COOLANT", fmt("%.0f%% · %.0f psi", s.coolantStoragePct, s.coolantLiquidPressurePsi))
        MetricLine("EVA TIME", formatDuration(uiState.evaSeconds), valueTag = "suit_eva_text")
      }
    }
  }
}

// ===================================================================== 3. RADIATION & PROTECTION

@Composable
internal fun RadiationTile(uiState: HudUiState) {
  val r = uiState.radiation
  val statusColor =
    when (r.status) {
      RadiationStatus.NOMINAL -> IosSfGreen
      RadiationStatus.ELEVATED -> IosSfAmber
      RadiationStatus.STORM -> IosSfRed
    }
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).testTag("dashboard_page_radiation"),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    TileHeader("RADIATION & PROTECTION", "MODELLED FROM CURIOSITY RAD · GPS SHELTER ROUTE", r.status.label, statusColor, "radiation_status_pill")
    Hairline()
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("DOSE RATE", style = hudText(7.5.sp, IosLabelSecondary, FontWeight.Medium, 1.sp))
        Row(verticalAlignment = Alignment.Bottom) {
          Text(fmt("%.1f", r.doseRateUsvPerHour), style = hudText(24.sp, statusColor), modifier = Modifier.testTag("radiation_dose_rate_text"))
          Spacer(Modifier.width(4.dp))
          Text("µSv/h", style = hudText(8.sp, IosLabelSecondary), modifier = Modifier.padding(bottom = 4.dp))
        }
        // Log-ish scale: baseline sits at ~25%, 20x storm fills the bar.
        val ratio = r.doseRateUsvPerHour / RadiationModel.GCR_BASELINE_USV_PER_H
        LevelBar((0.25f + 0.25f * kotlin.math.log2(ratio.coerceAtLeast(0.25f))).coerceIn(0.03f, 1f), statusColor)
        MetricLine("MARS GCR BASE", fmt("%.1f µSv/h", RadiationModel.GCR_BASELINE_USV_PER_H))
        MetricLine("EVA DOSE", fmt("%.1f µSv", r.evaDoseUsv))
        MetricLine("CAREER LIMIT", fmt("%.4f%% of 600 mSv", r.careerLimitFraction * 100f))
        MetricLine(
          "WIND · DUST",
          fmt("%.1f m/s · %s", uiState.windSpeedMps, if (uiState.windSpeedMps >= 18f) "GALE" else if (uiState.windSpeedMps >= 10f) "GUSTS" else "CALM"),
          if (uiState.windSpeedMps >= 15f) IosSfAmber else IosLabelPrimary,
        )
      }
      Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          if (r.shelter == ShelterStatus.SHELTERED) "AT BASE · SHIELDED" else "SHELTER ROUTE",
          style = hudText(7.5.sp, if (r.shelter == ShelterStatus.SHELTERED) IosSfGreen else IosLabelSecondary, spacing = 1.sp),
        )
        BaseBearingArrow(
          relativeDeg = SpatialLayout.angleDelta(r.bearingToBaseDeg, uiState.headingDegrees),
          active = r.shelter == ShelterStatus.EXPOSED,
          color = statusColor,
          modifier = Modifier.size(54.dp),
        )
        MetricLine("TO BASE", if (r.shelter == ShelterStatus.SHELTERED) "HERE" else fmt("%.0f m · %03.0f°", r.distanceToBaseMeters, r.bearingToBaseDeg), valueTag = "radiation_base_text")
        MetricLine("ETA · RETURN DOSE", fmt("%s · %.1f µSv", formatDuration(r.etaToBaseSec), r.returnDoseUsv))
        Text(r.advice, style = hudText(7.5.sp, statusColor, FontWeight.Medium), maxLines = 2)
      }
    }
  }
}

@Composable
private fun BaseBearingArrow(relativeDeg: Float, active: Boolean, color: Color, modifier: Modifier) {
  val animated by animateFloatAsState(relativeDeg, tween(250, easing = FastOutSlowInEasing), label = "base_arrow")
  Box(modifier.testTag("base_bearing_arrow"), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      drawCircle(SpecularWhite.copy(alpha = 0.14f), size.minDimension / 2f, style = Stroke(1.dp.toPx()))
    }
    Canvas(Modifier.fillMaxSize().rotate(if (active) animated else 0f)) {
      val c = Offset(size.width / 2f, size.height / 2f)
      val l = size.minDimension * 0.38f
      val p =
        Path().apply {
          moveTo(c.x, c.y - l)
          lineTo(c.x + l * 0.45f, c.y + l * 0.55f)
          lineTo(c.x, c.y + l * 0.25f)
          lineTo(c.x - l * 0.45f, c.y + l * 0.55f)
          close()
        }
      if (active) drawPath(p, color) else drawCircle(IosSfGreen, l * 0.35f, c)
    }
  }
}

// ===================================================================== 4. VISUALIZATION (map)

@Composable
internal fun MapTile(uiState: HudUiState, onRequestLocationPermission: () -> Unit) {
  val safeGoal = uiState.distanceGoalMeters.coerceAtLeast(100f)
  val safeDistance = uiState.distanceMeters.coerceAtLeast(0f)
  val progress by animateFloatAsState((safeDistance / safeGoal).coerceIn(0f, 1f), tween(450), label = "distance_ring")
  val (valueText, unitText) =
    if (safeDistance >= 1000f) fmt("%.2f", safeDistance / 1000f) to "KM" else "${safeDistance.roundToInt()}" to "METERS"

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp).testTag("dashboard_page_meter"),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
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
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(ObsidianBlack.copy(alpha = 0.28f))
          .border(0.6.dp, SpecularWhite.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
          .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(28.dp).testTag("dashboard_distance_ring")) {
          val sw = 3.2.dp.toPx()
          val arc = Size(size.width - sw, size.height - sw)
          val tl = Offset(sw / 2f, sw / 2f)
          drawArc(SpecularWhite.copy(alpha = 0.16f), 0f, 360f, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
          if (progress > 0.004f) {
            drawArc(Brush.linearGradient(listOf(IosSfBlue, IosSfGreen)), -90f, 360f * progress, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
          }
        }
        Column {
          Text(valueText, style = hudText(15.sp), modifier = Modifier.testTag("dashboard_distance_text"))
          Text(unitText, style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium), modifier = Modifier.testTag("dashboard_distance_unit_text"))
        }
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SPEED", style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium))
        Text(fmt("%.1f m/s", uiState.speedMps.coerceAtLeast(0f)), style = hudText(11.sp, IosSfBlue), modifier = Modifier.testTag("dashboard_speed_text"))
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("GOAL", style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium))
        Text(fmt("%.1f km", safeGoal / 1000f), style = hudText(11.sp))
      }
      if (!uiState.hasLocationPermission) {
        val pillShape = RoundedCornerShape(50)
        Box(
          modifier =
            Modifier.testTag("location_permission_pill")
              .minimumInteractiveComponentSize()
              .clip(pillShape)
              .background(IosSfBlue.copy(alpha = 0.18f))
              .border(0.75.dp, IosSfBlue.copy(alpha = 0.65f), pillShape)
              .clickable(onClick = onRequestLocationPermission)
              .padding(horizontal = 10.dp, vertical = 5.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text("Enable GPS", style = hudText(10.sp, IosSfBlue))
        }
      } else {
        StatusPill(if (uiState.hasGpsFix) "GPS LOCK" else "ACQUIRING", if (uiState.hasGpsFix) IosSfGreen else IosSfAmber, "gps_status_pill")
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
      activeDrill == EmergencyDrill.NONE -> IosLabelSecondary
      activeDrill.isCritical -> IosSfRed
      else -> IosSfAmber
    }
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp).testTag("dashboard_page_drills"),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    TileHeader(
      "DRILLS",
      "VOICE: \"EMERGENCY 1-10\" · THUMB DOWN TO CLEAR",
      if (activeDrill == EmergencyDrill.NONE) "STANDBY" else "${activeDrill.code} ACTIVE",
      pillColor,
      "drills_status_pill",
    )
    Hairline()
    val rows = (drills.size + 1) / 2
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      for (i in 0 until rows) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          DrillSlot(drills[i], activeDrill == drills[i], Modifier.weight(1f).fillMaxHeight())
          drills.getOrNull(i + rows)?.let { DrillSlot(it, activeDrill == it, Modifier.weight(1f).fillMaxHeight()) }
        }
      }
    }
  }
}

@Composable
private fun DrillSlot(drill: EmergencyDrill, isActive: Boolean, modifier: Modifier) {
  val shape = RoundedCornerShape(10.dp)
  val accent =
    when {
      isActive && drill.isCritical -> IosSfRed
      isActive -> IosSfAmber
      else -> SpecularWhite.copy(alpha = 0.7f)
    }
  Row(
    modifier =
      modifier
        .testTag("drill_item_${drill.number}")
        .clip(shape)
        .background(if (isActive) accent.copy(alpha = 0.22f) else ObsidianBlack.copy(alpha = 0.24f))
        .border(if (isActive) 1.dp else 0.5.dp, if (isActive) accent.copy(alpha = 0.85f) else SpecularWhite.copy(alpha = 0.16f), shape)
        .padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text("E${drill.number}  ${drill.title}", style = hudText(9.sp, if (isActive) accent else IosLabelPrimary), maxLines = 1)
    Text(drill.impactSummary, style = hudText(7.5.sp, if (isActive) accent else IosLabelSecondary, FontWeight.Medium), maxLines = 1)
  }
}
