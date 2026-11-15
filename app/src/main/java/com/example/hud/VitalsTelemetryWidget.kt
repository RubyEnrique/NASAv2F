package com.example.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mental.CognitiveLevel
import kotlin.math.exp
import kotlin.math.pow

/** Lower-left iOS widget: heart rate + ECG, then SpO2 · BP · core · H2O · load. */
@Composable
fun VitalsTelemetryWidget(
  modifier: Modifier = Modifier,
  heartRateBpm: Int = 86,
  systolicBp: Int = 118,
  diastolicBp: Int = 76,
  spo2Percent: Int = 98,
  coreTempCelsius: Float = 37.1f,
  hydrationPercent: Int = 74,
  isBiometricSensorLost: Boolean = false,
  cognitiveLoad: Int? = null,
  cognitiveLevel: CognitiveLevel = CognitiveLevel.NOMINAL,
) {
  val lost = isBiometricSensorLost
  val phase by
    rememberInfiniteTransition(label = "ecg").animateFloat(
      0f,
      1f,
      infiniteRepeatable(tween((60_000 / heartRateBpm.coerceIn(40, 200)) * 2, easing = LinearEasing), RepeatMode.Restart),
      label = "ecg_phase",
    )
  GlassPanel(modifier = modifier.width(222.dp).testTag("vitals_hud_widget"), corner = 22.dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (lost) Ios.Secondary else Ios.Pink)
        Spacer(Modifier.width(5.dp))
        Text("Heart rate", style = hudText(9.sp, if (lost) Ios.Secondary else Ios.Pink))
        Spacer(Modifier.weight(1f))
        Text(if (lost) "Sensor lost" else "Live", style = IosType.caption)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (lost) "--" else "$heartRateBpm",
          style = IosType.largeNumber.copy(fontSize = 24.sp, color = if (lost) Ios.Secondary else Ios.Label),
          modifier = Modifier.testTag("vitals_bpm_text"),
        )
        Spacer(Modifier.width(3.dp))
        Text("bpm", style = IosType.footnote, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(8.dp))
        EcgWaveform(phase, lost, Modifier.weight(1f).height(20.dp))
      }
      Hairline()
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Cell("SpO₂", if (lost) "--" else "$spo2Percent%", if (!lost && spo2Percent < 93) Ios.Red else Ios.Label)
        Cell("BP", if (lost) "--/--" else "$systolicBp/$diastolicBp", if (!lost && systolicBp >= 135) Ios.Orange else Ios.Label, "vitals_bp_text")
        Cell("Core", fmt("%.1f°", coreTempCelsius), if (coreTempCelsius >= 38f) Ios.Orange else Ios.Label)
        Cell("H₂O", "$hydrationPercent%", if (hydrationPercent < 35) Ios.Red else Ios.Cyan, "vitals_h2o_text")
        if (cognitiveLoad != null) Cell("Load", "$cognitiveLoad", levelColor(cognitiveLevel), "vitals_load_text")
      }
    }
  }
}

@Composable
private fun Cell(label: String, value: String, color: Color, tag: String? = null) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, style = IosType.caption)
    Text(value, style = hudText(10.5.sp, color), modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
  }
}

@Composable
private fun EcgWaveform(phase: Float, isFlatline: Boolean, modifier: Modifier) {
  Canvas(modifier) {
    val w = size.width
    val midY = size.height * 0.58f
    val amp = size.height * 0.45f
    val path = Path()
    if (isFlatline) {
      path.moveTo(0f, midY)
      path.lineTo(w, midY)
      drawPath(path, Ios.Secondary, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
      return@Canvas
    }
    for (i in 0..64) {
      val u = i / 64f
      val shifted = (u - phase + 1f) % 1f
      val y = midY - pqrst((shifted * 2f) % 1f) * amp
      if (i == 0) path.moveTo(u * w, y) else path.lineTo(u * w, y)
    }
    drawPath(
      path,
      Brush.horizontalGradient(0f to Ios.Pink.copy(alpha = 0.15f), 0.25f to Ios.Pink, 1f to Ios.Pink),
      style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
  }
}

private fun pqrst(t: Float): Float =
  0.14f * exp(-((t - 0.22f) / 0.045f).pow(2)) - 0.22f * exp(-((t - 0.36f) / 0.018f).pow(2)) +
    exp(-((t - 0.40f) / 0.017f).pow(2)) - 0.34f * exp(-((t - 0.44f) / 0.019f).pow(2)) +
    0.22f * exp(-((t - 0.62f) / 0.055f).pow(2))
