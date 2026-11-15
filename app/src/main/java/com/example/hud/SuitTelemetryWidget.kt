package com.example.hud

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.radiation.RadiationStatus
import com.example.suit.SuitTelemetry
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfBlue
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.SpecularWhite

/** Lower-right visor card: EMU consumables (NASA SUITS fields), helmet CO2, dose rate, EVA clock. */
@Composable
fun SuitTelemetryWidget(
  modifier: Modifier = Modifier,
  suit: SuitTelemetry = SuitTelemetry(),
  doseRateUsvPerHour: Float = 27.9f,
  radiationStatus: RadiationStatus = RadiationStatus.NOMINAL,
  evaSeconds: Int = HudUiState.INITIAL_EVA_SECONDS,
) {
  val pulse by
    rememberInfiniteTransition(label = "suit_pulse")
      .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "suit_pulse_v")
  val alarm = suit.isLowPower || suit.isOxygenLow || !suit.isPressureNominal
  GlassPanel(
    modifier = modifier.width(228.dp).testTag("suit_hud_widget"),
    corner = 24.dp,
    accent = if (alarm) IosSfRed.copy(alpha = 0.5f + 0.5f * pulse) else null,
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        RingStat("O₂", suit.oxyPriStoragePct, formatDuration(suit.oxyTimeLeftSec), if (suit.isOxygenLow) IosSfRed else IosSfBlue)
        RingStat(
          "PWR",
          suit.primaryBatteryPct,
          formatDuration(suit.batteryTimeLeftSec),
          if (suit.isLowPower) IosSfRed else IosSfGreen,
          percentTag = "suit_widget_pwr_text",
        )
        RingStat(
          "SUIT",
          (suit.suitPressureTotalPsi / 5f * 100f),
          fmt("%.1fpsi", suit.suitPressureTotalPsi),
          if (suit.isPressureNominal) IosSfGreen else IosSfRed,
          centerOverride = fmt("%.1f", suit.suitPressureTotalPsi),
        )
      }
      Spacer(Modifier.height(5.dp))
      Hairline()
      Spacer(Modifier.height(5.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Cell(
          "CO₂",
          fmt("%.1fmm", suit.helmetCo2MmHg),
          when {
            suit.helmetCo2MmHg >= 6f -> IosSfRed
            suit.helmetCo2MmHg >= 3f -> IosSfAmber
            else -> IosSfGreen
          },
        )
        Cell(
          "RAD",
          fmt("%.0fµSv/h", doseRateUsvPerHour),
          when (radiationStatus) {
            RadiationStatus.STORM -> IosSfRed
            RadiationStatus.ELEVATED -> IosSfAmber
            RadiationStatus.NOMINAL -> IosLabelPrimary
          },
          tag = "suit_rad_text",
        )
        Cell("FAN", fmt("%.0fk", suit.fanPriRpm / 1000f), if (suit.isFanFault) IosSfRed else IosLabelPrimary)
        Cell("EVA", formatDuration(evaSeconds), IosLabelPrimary, tag = "suit_widget_eva_text")
      }
    }
  }
}

@Composable
private fun RingStat(
  label: String,
  percent: Float,
  sub: String,
  color: Color,
  centerOverride: String? = null,
  percentTag: String? = null,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(contentAlignment = Alignment.Center) {
      Canvas(Modifier.size(24.dp)) {
        val sw = 2.2.dp.toPx()
        val arc = Size(size.width - sw, size.height - sw)
        val tl = Offset(sw / 2, sw / 2)
        drawArc(SpecularWhite.copy(alpha = 0.16f), 0f, 360f, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * (percent.coerceIn(0f, 100f) / 100f), false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
      }
      Text(
        centerOverride ?: "${percent.toInt()}%",
        style = hudText(6.5.sp),
        modifier = if (percentTag != null) Modifier.testTag(percentTag) else Modifier,
      )
    }
    Spacer(Modifier.width(4.dp))
    Column {
      Text(label, style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium))
      Text(sub, style = hudText(8.5.sp, color))
    }
  }
}

@Composable
private fun Cell(label: String, value: String, color: Color, tag: String? = null) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, style = hudText(6.5.sp, IosLabelSecondary, FontWeight.Medium))
    Text(value, style = hudText(9.sp, color), modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
  }
}
