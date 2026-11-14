package com.example.hud

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IosGlassBorder
import com.example.ui.theme.IosGlassHairline
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfBlue
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.SpecularWhite
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun SuitTelemetryWidget(
  modifier: Modifier = Modifier,
  lowPowerDelayMs: Long = 60_000L,
  initialBatteryLow: Boolean = false,
  suitPressurePsi: Float = 4.3f,
  oxygenPercent: Int = 88,
  oxygenHours: Float = 3.8f,
  suitPowerPercent: Int? = null,
  suitPowerHours: Float = 4.5f,
  ppCo2MmHg: Float = 1.8f,
  radiationMSv: Float? = null,
  windSpeedMps: Float? = null,
  evaSeconds: Int? = null,
) {
  var localEvaSeconds by remember { mutableIntStateOf(3 * 60 + 42) }
  var localWindSpeedTenths by remember { mutableIntStateOf(64) }
  var localRadHundredths by remember { mutableIntStateOf(24) }
  var localPowerPercent by remember { mutableIntStateOf(if (initialBatteryLow) 18 else 92) }

  val useInternalTicker = suitPowerPercent == null && evaSeconds == null
  LaunchedEffect(lowPowerDelayMs, initialBatteryLow, useInternalTicker) {
    if (!useInternalTicker) return@LaunchedEffect
    val windCycle = intArrayOf(64, 65, 64, 63, 64, 66, 65, 64)
    val radCycle = intArrayOf(24, 24, 25, 24, 23, 24, 25, 24)
    var elapsedMs = 0L
    var tick = 0
    while (true) {
      delay(1000L)
      elapsedMs += 1000L
      localEvaSeconds++
      tick++
      if (tick % 3 == 0) {
        val idx = (tick / 3) % windCycle.size
        localWindSpeedTenths = windCycle[idx]
        localRadHundredths = radCycle[idx]
      }
      if (elapsedMs >= lowPowerDelayMs && localPowerPercent >= 20) {
        localPowerPercent = 18
      }
    }
  }

  val activePowerPercent = suitPowerPercent ?: localPowerPercent
  val activeEvaSeconds = evaSeconds ?: localEvaSeconds
  val activeWindMps = windSpeedMps ?: (localWindSpeedTenths / 10f)
  val activeRadMSv = radiationMSv ?: (localRadHundredths / 100f)

  val isLowPower = activePowerPercent < 20
  val infiniteTransition = rememberInfiniteTransition(label = "low_power_pulse")
  val pulseRaw by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "low_power_pulse_wave",
    )

  val activePulse = if (isLowPower) pulseRaw else 0f
  val powerRingColor by
    animateColorAsState(
      targetValue = if (isLowPower) IosSfRed else IosSfGreen,
      animationSpec = tween(durationMillis = 500),
      label = "power_ring_color",
    )

  val cardShape = RoundedCornerShape(22.dp)
  val frostedGlassBrush =
    Brush.linearGradient(
      colors =
        listOf(
          SpecularWhite.copy(alpha = 0.20f),
          SpecularWhite.copy(alpha = 0.11f),
          SpecularWhite.copy(alpha = 0.15f),
        ),
    )

  val borderBrush =
    if (isLowPower) {
      val pulsedBorder = lerp(IosGlassBorder, IosSfRed.copy(alpha = 0.62f), activePulse)
      Brush.linearGradient(colors = listOf(pulsedBorder, pulsedBorder.copy(alpha = 0.40f)))
    } else {
      Brush.linearGradient(
        colors =
          listOf(
            SpecularWhite.copy(alpha = 0.44f),
            SpecularWhite.copy(alpha = 0.16f),
            SpecularWhite.copy(alpha = 0.28f),
          ),
      )
    }

  val evaMinutes = activeEvaSeconds / 60
  val evaRemSeconds = activeEvaSeconds % 60
  val evaFormatted =
    "${evaMinutes.toString().padStart(2, '0')}:${evaRemSeconds.toString().padStart(2, '0')}"
  val windFormatted = String.format(Locale.US, "%.1fm/s", activeWindMps)
  val radFormatted = String.format(Locale.US, "%.2fmSv", activeRadMSv)
  val o2HoursText = String.format(Locale.US, "%.1fh", oxygenHours)
  val powerHoursText = if (isLowPower) "0.8h" else String.format(Locale.US, "%.1fh", suitPowerHours)
  val pressureValueText = String.format(Locale.US, "%.1fpsi", suitPressurePsi)
  val pressureRingPercent = ((suitPressurePsi / 5.0f) * 100f).roundToInt().coerceIn(0, 100)
  val pressureOk = suitPressurePsi in 3.8f..4.6f
  val ppCo2Text = String.format(Locale.US, "%.1fmm", ppCo2MmHg)

  Box(
    modifier =
      modifier
        .width(232.dp)
        .testTag("suit_hud_widget")
        .clip(cardShape)
        .background(frostedGlassBrush)
        .then(
          if (isLowPower) {
            Modifier.background(IosSfRed.copy(alpha = 0.05f + 0.05f * activePulse))
          } else {
            Modifier
          }
        )
        .border(width = 0.65.dp, brush = borderBrush, shape = cardShape)
        .padding(horizontal = 13.dp, vertical = 9.dp)
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ResourceRingCluster(
          percent = oxygenPercent,
          ringColor = IosSfBlue,
          label = "O\u2082",
          subValue = o2HoursText,
        )
        ResourceRingCluster(
          percent = pressureRingPercent,
          centerTextOverride = String.format(Locale.US, "%.1f", suitPressurePsi),
          ringColor = if (pressureOk) IosSfGreen else IosSfRed,
          label = "SUIT",
          subValue = pressureValueText,
          subValueTestTag = "suit_pressure_text",
        )
        ResourceRingCluster(
          percent = activePowerPercent,
          ringColor = powerRingColor,
          label = "PWR",
          subValue = powerHoursText,
          pulseFraction = activePulse,
          percentTestTag = "suit_pwr_percent_text",
        )
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
        SuitCell(label = "ppCO\u2082", value = ppCo2Text, valueColor = IosSfGreen)
        SuitCell(
          label = "RAD",
          value = radFormatted,
          valueColor = IosSfAmber,
          valueTag = "suit_rad_text",
        )
        SuitCell(label = "WIND", value = windFormatted, valueColor = IosLabelPrimary)
        SuitCell(
          label = "EVA",
          value = evaFormatted,
          valueColor = IosLabelPrimary,
          valueTag = "suit_eva_text",
        )
      }
    }
  }
}

@Composable
private fun ResourceRingCluster(
  percent: Int,
  ringColor: Color,
  label: String,
  subValue: String,
  pulseFraction: Float = 0f,
  centerTextOverride: String? = null,
  percentTestTag: String? = null,
  subValueTestTag: String? = null,
) {
  val isPulsing = percent < 20 && centerTextOverride == null
  val ringScale = if (isPulsing) 0.96f + 0.08f * pulseFraction else 1f
  val contentAlpha = if (isPulsing) 0.74f + 0.26f * pulseFraction else 1f

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.graphicsLayer { alpha = contentAlpha },
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.graphicsLayer {
          scaleX = ringScale
          scaleY = ringScale
        },
    ) {
      Canvas(modifier = Modifier.size(25.dp)) {
        val stroke = 2.2.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        if (isPulsing) {
          drawCircle(
            color = ringColor.copy(alpha = 0.14f + 0.22f * pulseFraction),
            radius = size.minDimension / 2f,
            style = Stroke(width = 1.dp.toPx()),
          )
        }

        drawArc(
          color = SpecularWhite.copy(alpha = 0.16f),
          startAngle = 0f,
          sweepAngle = 360f,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
          color = ringColor,
          startAngle = -90f,
          sweepAngle = 360f * (percent / 100f),
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
      }
      Text(
        text = centerTextOverride ?: "$percent%",
        style =
          TextStyle(
            color = IosLabelPrimary,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 7.sp,
            fontFeatureSettings = "tnum",
          ),
        modifier = if (percentTestTag != null) Modifier.testTag(percentTestTag) else Modifier,
      )
    }

    Spacer(modifier = Modifier.width(4.dp))

    Column {
      Text(
        text = label,
        style =
          TextStyle(
            color = IosLabelSecondary,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 7.5.sp,
          ),
      )
      Text(
        text = subValue,
        style =
          TextStyle(
            color = ringColor,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.5.sp,
            fontFeatureSettings = "tnum",
          ),
        modifier = if (subValueTestTag != null) Modifier.testTag(subValueTestTag) else Modifier,
      )
    }
  }
}

@Composable
private fun SuitCell(
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
          fontSize = 7.sp,
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
          fontSize = 10.sp,
          fontFeatureSettings = "tnum",
        ),
      modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
    )
  }
}
