package com.example.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IosGlassHairline
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfIndigo
import com.example.ui.theme.IosSfPink
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.SpecularWhite
import java.util.Locale
import kotlin.math.exp
import kotlin.math.pow
import kotlinx.coroutines.delay

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
  cognitiveLevel: com.example.mental.CognitiveLevel = com.example.mental.CognitiveLevel.NOMINAL,
) {
  var microOffset by remember { mutableIntStateOf(0) }

  LaunchedEffect(isBiometricSensorLost) {
    if (isBiometricSensorLost) {
      microOffset = 0
      return@LaunchedEffect
    }
    val driftCycle = intArrayOf(0, 1, 0, -1, 0, 2, 1, 0)
    var index = 0
    while (true) {
      delay(2400L)
      index = (index + 1) % driftCycle.size
      microOffset = driftCycle[index]
    }
  }

  val displayBpm = (heartRateBpm + microOffset).coerceIn(50, 195)
  val bpmText = if (isBiometricSensorLost) "--" else "$displayBpm"
  val bpText = if (isBiometricSensorLost) "--/--" else "$systolicBp/$diastolicBp"
  val spo2Text = if (isBiometricSensorLost) "--" else "$spo2Percent%"
  val coreText = String.format(Locale.US, "%.1f\u00B0", coreTempCelsius)

  val infiniteTransition = rememberInfiniteTransition(label = "ecg_sweep")
  val phase by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1450, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "ecg_phase",
    )

  val cardShape = RoundedCornerShape(24.dp)
  val frostedGlassBrush =
    Brush.linearGradient(
      colors =
        listOf(
          SpecularWhite.copy(alpha = 0.20f),
          SpecularWhite.copy(alpha = 0.11f),
          SpecularWhite.copy(alpha = 0.15f),
        ),
    )
  val specularBorderBrush =
    Brush.linearGradient(
      colors =
        listOf(
          SpecularWhite.copy(alpha = 0.44f),
          SpecularWhite.copy(alpha = 0.16f),
          SpecularWhite.copy(alpha = 0.28f),
        ),
    )

  Box(
    modifier =
      modifier
        .width(224.dp)
        .testTag("vitals_hud_widget")
        .clip(cardShape)
        .background(frostedGlassBrush)
        .border(width = 0.65.dp, brush = specularBorderBrush, shape = cardShape)
        .padding(horizontal = 13.dp, vertical = 9.dp)
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = bpmText,
          style =
            TextStyle(
              color = if (isBiometricSensorLost) IosLabelSecondary else IosLabelPrimary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 20.sp,
              fontFeatureSettings = "tnum",
            ),
          modifier = Modifier.testTag("vitals_bpm_text"),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
          text = "BPM",
          style =
            TextStyle(
              color = if (isBiometricSensorLost) IosLabelSecondary else IosSfPink,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 8.sp,
              letterSpacing = 0.3.sp,
            ),
          modifier = Modifier.align(Alignment.Bottom).padding(bottom = 3.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        EcgWaveform(
          phase = phase,
          isFlatline = isBiometricSensorLost,
          modifier = Modifier.weight(1f).height(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = "BP",
            style =
              TextStyle(
                color = IosLabelSecondary,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 7.sp,
                letterSpacing = 0.3.sp,
              ),
          )
          Text(
            text = bpText,
            style =
              TextStyle(
                color =
                  when {
                    isBiometricSensorLost -> IosLabelSecondary
                    systolicBp >= 135 -> IosSfAmber
                    else -> IosLabelPrimary
                  },
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                fontFeatureSettings = "tnum",
              ),
            modifier = Modifier.testTag("vitals_bp_text"),
          )
        }
      }

      Spacer(modifier = Modifier.height(6.dp))
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(0.5.dp)
            .background(IosGlassHairline)
      )
      Spacer(modifier = Modifier.height(6.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        TelemetryCell(
          label = "SpO\u2082",
          value = spo2Text,
          valueColor =
            when {
              isBiometricSensorLost -> IosLabelSecondary
              spo2Percent < 93 -> IosSfRed
              else -> IosLabelPrimary
            },
        )
        TelemetryCell(
          label = "CORE",
          value = coreText,
          valueColor = if (coreTempCelsius >= 38.0f) IosSfAmber else IosLabelPrimary,
        )
        TelemetryCell(
          label = "H\u2082O",
          value = "$hydrationPercent%",
          valueColor = if (hydrationPercent < 35) IosSfRed else IosSfIndigo,
          valueTag = "vitals_h2o_text",
        )
        if (cognitiveLoad != null) {
          TelemetryCell(
            label = "LOAD",
            value = "$cognitiveLoad",
            valueColor = levelColor(cognitiveLevel),
            valueTag = "vitals_load_text",
          )
        }
      }
    }
  }
}

@Composable
private fun TelemetryCell(
  label: String,
  value: String,
  valueColor: Color,
  valueTag: String? = null,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = label,
      style =
        TextStyle(
          color = IosLabelSecondary,
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.Medium,
          fontSize = 7.5.sp,
          letterSpacing = 0.3.sp,
        ),
    )
    Spacer(modifier = Modifier.height(1.dp))
    Text(
      text = value,
      style =
        TextStyle(
          color = valueColor,
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.SemiBold,
          fontSize = 10.5.sp,
          fontFeatureSettings = "tnum",
        ),
      modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
    )
  }
}

@Composable
private fun EcgWaveform(
  phase: Float,
  isFlatline: Boolean = false,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val midY = h * 0.56f
    val amp = h * 0.42f
    val steps = 64
    val path = Path()

    if (isFlatline) {
      path.moveTo(0f, midY)
      path.lineTo(w, midY)
      drawPath(
        path = path,
        color = IosLabelSecondary.copy(alpha = 0.55f),
        style = Stroke(width = 1.35.dp.toPx(), cap = StrokeCap.Round),
      )
      return@Canvas
    }

    for (i in 0..steps) {
      val u = i.toFloat() / steps.toFloat()
      val shifted = (u - phase + 1f) % 1f
      val beat = pqrstSample(((shifted * 2f) % 1f))
      val y = midY - beat * amp
      val x = u * w
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(
      path = path,
      brush =
        Brush.horizontalGradient(
          0.0f to IosSfPink.copy(alpha = 0.22f),
          0.18f to IosSfPink.copy(alpha = 0.94f),
          0.85f to IosSfPink.copy(alpha = 0.94f),
          1.0f to IosSfPink.copy(alpha = 0.22f),
        ),
      style = Stroke(width = 1.45.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
  }
}

private fun pqrstSample(t: Float): Float {
  val p = 0.14f * exp(-((t - 0.22f) / 0.045f).pow(2))
  val q = -0.22f * exp(-((t - 0.36f) / 0.018f).pow(2))
  val r = 1.00f * exp(-((t - 0.40f) / 0.017f).pow(2))
  val s = -0.34f * exp(-((t - 0.44f) / 0.019f).pow(2))
  val tWave = 0.22f * exp(-((t - 0.62f) / 0.055f).pow(2))
  return p + q + r + s + tWave
}
