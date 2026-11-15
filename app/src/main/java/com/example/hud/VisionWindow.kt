package com.example.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.EyeOverlay
import com.example.eye.EyeTrackingStatus
import com.example.gesture.DashboardPage
import com.example.mental.CognitiveLevel
import com.example.nasa.Mars24
import androidx.compose.ui.graphics.ImageBitmap

/*
 * visionOS-style window: one compact light-glass pane per hazard, a persistent "mind" sidebar
 * (Mars clock, cognitive-load ring, quick status chips), a bottom tab ornament and a left
 * gesture ornament floating outside the pane.
 */

@Composable
internal fun VisionWindow(
  page: DashboardPage,
  s: HudUiState,
  eyePreview: ImageBitmap?,
  eyeOverlay: EyeOverlay?,
  onRequestLocationPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(30.dp)
  val (statusText, statusColor) = pageStatus(page, s)
  Row(
    modifier
      .clip(shape)
      .background(Vision.Window)
      .border(0.8.dp, Vision.WindowEdge, shape),
  ) {
    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HazardBadge(page, 20.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
          Text(page.title, style = hudText(13.sp, weight = FontWeight.Bold), maxLines = 1)
          Text(page.focus, style = hudText(8.sp, Vision.Text2, FontWeight.Normal), maxLines = 1)
        }
        StatusPill(statusText, statusColor, pageStatusTag(page))
      }
      Spacer(Modifier.height(8.dp))
      Box(Modifier.fillMaxWidth().weight(1f)) {
        PageBody(page, s, eyePreview, eyeOverlay, onRequestLocationPermission)
      }
    }
    MindSidebar(s, Modifier.width(112.dp).fillMaxHeight())
  }
}

/** Persistent right column — the cognitive area stays visible on every hazard page. */
@Composable
private fun MindSidebar(s: HudUiState, modifier: Modifier) {
  val cog = s.cognitive
  val color = levelColor(cog.level)
  Column(
    modifier.background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 10.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    val mars = s.marsTime
    Text(mars?.let { Mars24.formatHours(it.localMeanSolarTimeHours) } ?: "--:--", style = hudText(17.sp, weight = FontWeight.Light))
    Text(mars?.let { "Sol ${it.marsSolDate.toInt()} · Jezero" } ?: "Mars time", style = hudText(7.5.sp, Vision.Text2, FontWeight.Normal), modifier = Modifier.testTag("mars_time_text"))
    Box(Modifier.width(26.dp).height(1.dp).background(Color.White.copy(alpha = 0.35f)))
    Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
      LoadGauge(cog.index, color, Modifier.fillMaxSize(), stroke = 6.dp, forecast = s.cognitiveForecast.projectedIn10Min)
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${cog.index}", style = hudText(22.sp, weight = FontWeight.Light), modifier = Modifier.testTag("cognitive_load_text"))
        Text("load", style = hudText(7.sp, Vision.Text2, FontWeight.Normal))
      }
    }
    Text(cog.topDriver.lowercase().replaceFirstChar { it.uppercase() }, style = hudText(8.5.sp, color), maxLines = 1, modifier = Modifier.testTag("cognitive_driver_text"))
    if (cog.level != CognitiveLevel.NOMINAL) BreathingPacer(color)
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
      QuickChip("Eyes", s.eyeTrackingStatus == EyeTrackingStatus.ACTIVE, Ios.Cyan, Modifier.weight(1f))
      QuickChip("Mic", s.mic.speaking || !s.mic.calibrating, Ios.Green, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
      QuickChip("Breathe", cog.level != CognitiveLevel.NOMINAL, Ios.Yellow, Modifier.weight(1f))
      QuickChip("Ack", s.alertAcknowledged, Ios.Blue, Modifier.weight(1f))
    }
  }
}

@Composable
private fun QuickChip(label: String, on: Boolean, color: Color, modifier: Modifier) {
  Box(
    modifier.height(22.dp).clip(RoundedCornerShape(9.dp)).background(if (on) (if (color == Ios.Yellow) Ios.Yellow else Color.White).copy(alpha = 0.88f) else Color.White.copy(alpha = 0.10f)),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, style = hudText(7.5.sp, if (on) Color.Black else Vision.Text2, FontWeight.Medium), maxLines = 1)
  }
}

/** Bottom ornament: hazard tabs (like visionOS room tabs) plus a round "+" for Drills. */
@Composable
internal fun TabOrnament(current: DashboardPage, modifier: Modifier = Modifier) {
  Row(
    modifier
      .testTag("dashboard_page_indicator")
      .clip(RoundedCornerShape(50))
      .background(Vision.Window)
      .border(0.6.dp, Vision.WindowEdge, RoundedCornerShape(50))
      .padding(3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    DashboardPage.ring.forEach { p ->
      val selected = p == current
      Box(
        Modifier.clip(RoundedCornerShape(50))
          .background(if (selected) Color.White.copy(alpha = 0.85f) else Color.Transparent)
          .padding(horizontal = 9.dp, vertical = 4.dp)
      ) {
        Text(
          when (p) {
            DashboardPage.RADIATION -> "Radiation"
            DashboardPage.ISOLATION -> "Isolation"
            DashboardPage.DISTANCE -> "Distance"
            DashboardPage.GRAVITY -> "Gravity"
            else -> "Environment"
          },
          style = hudText(8.5.sp, if (selected) Color.Black else Vision.Text2, if (selected) FontWeight.SemiBold else FontWeight.Medium),
        )
      }
    }
    Box(Modifier.padding(horizontal = 4.dp).width(0.8.dp).height(14.dp).background(Color.White.copy(alpha = 0.3f)))
    Box(
      Modifier.size(22.dp).clip(CircleShape).background(if (current == DashboardPage.DRILLS) Ios.Red else Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      Text("+", style = hudText(13.sp, Color.White, FontWeight.Light))
    }
  }
}

/** Left ornament: the gesture vocabulary as a quiet cheat-sheet, current pose highlighted. */
@Composable
internal fun GestureOrnament(s: HudUiState, modifier: Modifier = Modifier) {
  val active = s.gesture.activePose
  Column(
    modifier
      .testTag("gesture_ornament")
      .clip(RoundedCornerShape(50))
      .background(Vision.Window)
      .border(0.6.dp, Vision.WindowEdge, RoundedCornerShape(50))
      .padding(vertical = 8.dp, horizontal = 5.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    GestureHint(Glyph.HOLD, "Palm", active == com.example.gesture.HandPose.OPEN_PALM)
    GestureHint(Glyph.SWIPE, "Swipe", false)
    GestureHint(Glyph.CENTER, "Point", active == com.example.gesture.HandPose.POINTING_UP)
    GestureHint(Glyph.ACK, "Thumb", active == com.example.gesture.HandPose.THUMB_UP)
    GestureHint(Glyph.EYE, "V", active == com.example.gesture.HandPose.VICTORY)
    GestureHint(Glyph.CLOSE, "Fist", active == com.example.gesture.HandPose.CLOSED_FIST)
  }
}

internal enum class Glyph { HOLD, SWIPE, CENTER, ACK, EYE, CLOSE }

@Composable
private fun GestureHint(glyph: Glyph, label: String, active: Boolean) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.size(24.dp).clip(CircleShape).background(if (active) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.10f)),
      contentAlignment = Alignment.Center,
    ) {
      LineGlyph(glyph, if (active) Color.Black else Color.White, Modifier.size(12.dp))
    }
    Text(label, style = hudText(6.5.sp, Vision.Text2, FontWeight.Medium))
  }
}

/** Minimal 1.3 dp line icons (no emoji, no fills). */
@Composable
internal fun LineGlyph(glyph: Glyph, color: Color, modifier: Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val sw = 1.3.dp.toPx()
    val st = Stroke(sw, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
      drawLine(color, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, cap = StrokeCap.Round)
    when (glyph) {
      Glyph.HOLD -> {
        drawCircle(color, w * 0.42f, style = st)
        drawArc(color, -90f, 250f, false, Offset(w * 0.22f, h * 0.22f), Size(w * 0.56f, h * 0.56f), style = st)
      }
      Glyph.SWIPE -> {
        line(0.1f, 0.5f, 0.9f, 0.5f)
        line(0.1f, 0.5f, 0.3f, 0.32f); line(0.1f, 0.5f, 0.3f, 0.68f)
        line(0.9f, 0.5f, 0.7f, 0.32f); line(0.9f, 0.5f, 0.7f, 0.68f)
      }
      Glyph.CENTER -> {
        drawCircle(color, w * 0.28f, style = st)
        line(0.5f, 0.0f, 0.5f, 0.2f); line(0.5f, 0.8f, 0.5f, 1f)
        line(0.0f, 0.5f, 0.2f, 0.5f); line(0.8f, 0.5f, 1f, 0.5f)
      }
      Glyph.ACK -> { line(0.12f, 0.55f, 0.4f, 0.8f); line(0.4f, 0.8f, 0.9f, 0.22f) }
      Glyph.EYE -> {
        drawOval(color, Offset(0f, h * 0.25f), Size(w, h * 0.5f), style = st)
        drawCircle(color, w * 0.13f, Offset(w / 2f, h / 2f))
      }
      Glyph.CLOSE -> { line(0.2f, 0.2f, 0.8f, 0.8f); line(0.8f, 0.2f, 0.2f, 0.8f) }
    }
  }
}

/** Small faded card marking where a neighbouring hazard window hangs in space. */
@Composable
internal fun PeekCard(page: DashboardPage, s: HudUiState, modifier: Modifier = Modifier) {
  val (status, color) = pageStatus(page, s)
  Row(
    modifier
      .clip(RoundedCornerShape(16.dp))
      .background(Vision.Window)
      .border(0.5.dp, Vision.WindowEdge, RoundedCornerShape(16.dp))
      .padding(horizontal = 9.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    HazardBadge(page, 16.dp)
    Spacer(Modifier.width(6.dp))
    Column {
      Text(page.title, style = hudText(8.5.sp), maxLines = 1)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 4.dp)
        Spacer(Modifier.width(3.dp))
        Text("$status · ${pageHeadline(page, s)}", style = hudText(7.sp, Vision.Text2, FontWeight.Normal), maxLines = 1)
      }
    }
  }
}

/** Thin progress arc used by the ambient HUD. */
@Composable
internal fun MiniRing(fraction: Float, color: Color, size: Dp, stroke: Dp = 2.dp) {
  val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "mini_ring")
  Canvas(Modifier.size(size)) {
    val sw = stroke.toPx()
    val arc = Size(this.size.width - sw, this.size.height - sw)
    drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(sw / 2, sw / 2), arc, style = Stroke(sw))
    drawArc(color, -90f, 360f * animated, false, Offset(sw / 2, sw / 2), arc, style = Stroke(sw, cap = StrokeCap.Round))
  }
}
