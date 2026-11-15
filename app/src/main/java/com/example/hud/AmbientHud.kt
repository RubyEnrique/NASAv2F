package com.example.hud

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mental.CognitiveLevel
import com.example.radiation.RadiationStatus

/*
 * Ambient (dashboard-closed) HUD. Designed to be felt rather than read:
 *  - Load halo: soft light lines on the left and right edges of the visor. Its length and colour follow
 *    the cognitive-load index, so a rising load is noticed in peripheral vision without text.
 *  - Glance strip: one small capsule with the four numbers that matter at a glance.
 */

@Composable
internal fun LoadHalo(s: HudUiState, modifier: Modifier = Modifier) {
  val level = s.cognitive.level
  val color = levelColor(level)
  val fraction by animateFloatAsState((s.cognitive.index / 100f).coerceIn(0.06f, 1f), tween(600), label = "halo_len")
  val pulse by
    rememberInfiniteTransition(label = "halo_pulse")
      .animateFloat(0.45f, 1f, infiniteRepeatable(tween(if (level == CognitiveLevel.EMERGENCY) 420 else 1800), RepeatMode.Reverse), label = "halo_a")
  val storm = s.radiation.status == RadiationStatus.STORM
  Canvas(modifier.testTag("load_halo")) {
    val w = size.width
    val h = size.height
    val half = h * 0.5f * fraction
    val c = h / 2f
    val alpha = if (level == CognitiveLevel.NOMINAL) 0.5f else pulse
    val edgeColor = if (storm) Ios.Red else color
    // Two vertical light lines on the far left/right edges: peripheral, never in the line of sight.
    for (x in listOf(2.dp.toPx(), w - 2.dp.toPx())) {
      drawLine(
        Brush.verticalGradient(listOf(Color.Transparent, edgeColor.copy(alpha = 0.32f * alpha), Color.Transparent), startY = c - half, endY = c + half),
        Offset(x, c - half),
        Offset(x, c + half),
        strokeWidth = 10.dp.toPx(),
        cap = StrokeCap.Round,
      )
      drawLine(
        Brush.verticalGradient(listOf(Color.Transparent, edgeColor.copy(alpha = alpha), Color.Transparent), startY = c - half, endY = c + half),
        Offset(x, c - half),
        Offset(x, c + half),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
      )
    }
  }
}

@Composable
internal fun GlanceStrip(s: HudUiState, modifier: Modifier = Modifier) {
  val lost = s.isBiometricSensorLost
  val shape = RoundedCornerShape(50)
  Column(
    modifier
      .testTag("glance_strip")
      .clip(RoundedCornerShape(16.dp))
      .background(Color.Black.copy(alpha = 0.42f))
      .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
      .padding(horizontal = 10.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Glance("HR", if (lost) "--" else "${s.heartRateBpm}", Vision.Text2, "vitals_bpm_text")
    Divider()
    Glance("O₂", formatMinutes(s.consumables.oxygenMinutesLeft), if (s.suit.isOxygenLow) Ios.Red else Ios.Cyan, "glance_o2_text")
    Divider()
    Glance(
      "RAD",
      fmt("%.0f", s.radiation.doseRateUsvPerHour),
      when (s.radiation.status) {
        RadiationStatus.STORM -> Ios.Red
        RadiationStatus.ELEVATED -> Ios.Orange
        RadiationStatus.NOMINAL -> Vision.Text2
      },
      "glance_rad_text",
    )
    Divider()
    Row(verticalAlignment = Alignment.CenterVertically) {
      MiniRing(s.cognitive.index / 100f, levelColor(s.cognitive.level), 12.dp)
      Spacer(Modifier.width(5.dp))
      Text("${s.cognitive.index}", style = hudText(10.5.sp, levelColor(s.cognitive.level)), modifier = Modifier.testTag("glance_load_text"))
    }
  }
}

@Composable
private fun Glance(icon: String, value: String, color: Color, tag: String) {
  Column(modifier = Modifier.semantics(mergeDescendants = false) {}) {
    Text(icon, style = hudText(6.5.sp, color, FontWeight.SemiBold, 0.4.sp))
    Text(value, style = hudText(10.5.sp, Ios.Label), modifier = Modifier.testTag(tag))
  }
}

@Composable
private fun Divider() {
  Box(Modifier.padding(vertical = 6.dp).width(18.dp).height(0.6.dp).background(Color.White.copy(alpha = 0.22f)))
}
