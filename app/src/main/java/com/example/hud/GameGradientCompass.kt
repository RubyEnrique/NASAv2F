package com.example.hud

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IosGlassBorder
import com.example.ui.theme.IosGlassFill
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.SpecularWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.delay

@Composable
fun GameGradientCompass(
  headingDegrees: Float,
  batteryPercent: Int,
  modifier: Modifier = Modifier,
  timeTextOverride: String? = null,
) {
  val normalized = ((headingDegrees % 360f) + 360f) % 360f
  val textMeasurer = rememberTextMeasurer()

  var currentTimeText by remember { mutableStateOf(timeTextOverride ?: format24HourTime()) }
  LaunchedEffect(timeTextOverride) {
    if (timeTextOverride != null) {
      currentTimeText = timeTextOverride
      return@LaunchedEffect
    }
    while (true) {
      currentTimeText = format24HourTime()
      delay(1000L)
    }
  }

  // Ultra-minimal ~32% width in landscape with a safe floor on narrow containers.
  Column(
    modifier = modifier.widthIn(min = 210.dp).fillMaxWidth(0.32f).testTag("game_compass"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
      val w = size.width
      val centerX = w / 2f
      val halfW = w / 2f
      val halfSpanDeg = 38f
      val pxPerDeg = w / (halfSpanDeg * 2f)

      val edgeLiftPx = 3.5.dp.toPx()
      val arcCenterY = 8.dp.toPx()

      fun curveOffsetY(x: Float): Float {
        val ratio = ((x - centerX) / halfW).coerceIn(-1f, 1f)
        return -edgeLiftPx * (ratio * ratio)
      }

      // Single ultra-minimal curved frosted-white hairline arc.
      val arcPath = Path()
      val arcSteps = 40
      for (i in 0..arcSteps) {
        val x = w * (i.toFloat() / arcSteps.toFloat())
        val y = arcCenterY + curveOffsetY(x)
        if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
      }
      drawPath(
        path = arcPath,
        brush =
          Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.18f to SpecularWhite.copy(alpha = 0.22f),
            0.50f to SpecularWhite.copy(alpha = 0.56f),
            0.82f to SpecularWhite.copy(alpha = 0.22f),
            1.0f to Color.Transparent,
          ),
        style = Stroke(width = 0.75.dp.toPx(), cap = StrokeCap.Round),
      )

      // Delicate center chevron indicator above the single hairline.
      val chevHalfW = 2.8.dp.toPx()
      val chevTopY = 2.dp.toPx()
      val chevTipY = 4.8.dp.toPx()
      val chevronPath =
        Path().apply {
          moveTo(centerX - chevHalfW, chevTopY)
          lineTo(centerX, chevTipY)
          lineTo(centerX + chevHalfW, chevTopY)
        }
      drawPath(
        path = chevronPath,
        color = SpecularWhite.copy(alpha = 0.88f),
        style = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
      )

      // Faint vertical center hair tick.
      drawLine(
        color = SpecularWhite.copy(alpha = 0.24f),
        start = Offset(centerX, arcCenterY + 2.dp.toPx()),
        end = Offset(centerX, size.height - 2.dp.toPx()),
        strokeWidth = 0.75.dp.toPx(),
        cap = StrokeCap.Round,
      )

      // Light scrolling tick marks and cardinal / 3-digit degree labels.
      val startTickDeg = floor(((normalized - halfSpanDeg) / 5f).toDouble()).toInt() * 5
      val endTickDeg = startTickDeg + 85

      var tickDeg = startTickDeg
      while (tickDeg <= endTickDeg) {
        val delta = tickDeg - normalized
        val x = centerX + delta * pxPerDeg
        if (x in 0f..w) {
          val edgeRatio = (abs(delta) / halfSpanDeg).coerceIn(0f, 1f)
          val fadeAlpha = (1f - edgeRatio * edgeRatio).coerceIn(0f, 1f)
          if (fadeAlpha > 0.04f) {
            val wrappedDeg = ((tickDeg % 360) + 360) % 360
            val isMajor = wrappedDeg % 15 == 0
            val isCardinal = wrappedDeg % 45 == 0
            val curveY = curveOffsetY(x)

            val tickTop = arcCenterY + curveY + 2.5.dp.toPx()
            val tickHeight = if (isMajor) 5.5.dp.toPx() else 3.dp.toPx()
            val tickStroke = if (isMajor) 1.05.dp.toPx() else 0.7.dp.toPx()

            drawLine(
              color = SpecularWhite.copy(alpha = (if (isMajor) 0.74f else 0.36f) * fadeAlpha),
              start = Offset(x, tickTop),
              end = Offset(x, tickTop + tickHeight),
              strokeWidth = tickStroke,
              cap = StrokeCap.Round,
            )

            if (isMajor) {
              val label = formatCompassLabel(wrappedDeg)
              val labelStyle =
                TextStyle(
                  color =
                    (if (isCardinal) IosLabelPrimary else IosLabelSecondary).copy(
                      alpha = (if (isCardinal) 0.94f else 0.72f) * fadeAlpha
                    ),
                  fontFamily = FontFamily.SansSerif,
                  fontWeight = if (isCardinal) FontWeight.SemiBold else FontWeight.Medium,
                  fontSize = if (isCardinal) 10.sp else 8.5.sp,
                  fontFeatureSettings = "tnum",
                )
              val measured = textMeasurer.measure(text = label, style = labelStyle)
              val textX = x - measured.size.width / 2f
              val textY = tickTop + tickHeight + 1.5.dp.toPx()
              if (textX >= 0f && textX + measured.size.width <= w) {
                drawText(
                  textLayoutResult = measured,
                  topLeft = Offset(textX, textY),
                )
              }
            }
          }
        }
        tickDeg += 5
      }
    }

    Spacer(modifier = Modifier.height(2.dp))

    // Left: 24hr Time | Right: Live Phone Battery in soft frosted iOS pills.
    Row(
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IosFrostedMicroPill {
        Text(
          text = currentTimeText,
          style =
            TextStyle(
              color = IosLabelPrimary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 10.sp,
              letterSpacing = 0.3.sp,
              fontFeatureSettings = "tnum",
            ),
          modifier = Modifier.testTag("compass_time_text"),
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      IosFrostedMicroPill {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IosSegmentedBatteryGlyph(percent = batteryPercent)
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "${batteryPercent}%",
            style =
              TextStyle(
                color = if (batteryPercent < 20) IosSfRed else IosLabelPrimary,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                fontFeatureSettings = "tnum",
              ),
            modifier = Modifier.testTag("compass_battery_text"),
          )
        }
      }
    }
  }
}

@Composable
private fun IosFrostedMicroPill(content: @Composable () -> Unit) {
  val pillShape = RoundedCornerShape(50)
  Box(
    modifier =
      Modifier.clip(pillShape)
        .background(IosGlassFill)
        .border(width = 0.55.dp, color = IosGlassBorder, shape = pillShape)
        .padding(horizontal = 9.dp, vertical = 2.5.dp),
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

@Composable
private fun IosSegmentedBatteryGlyph(percent: Int) {
  val isLow = percent < 20
  val accent = if (isLow) IosSfRed else SpecularWhite.copy(alpha = 0.90f)
  Canvas(modifier = Modifier.size(width = 16.dp, height = 8.dp)) {
    val bodyW = size.width - 2.dp.toPx()
    val bodyH = size.height
    val stroke = 0.9.dp.toPx()

    drawRoundRect(
      color = accent.copy(alpha = 0.85f),
      topLeft = Offset(0f, 0f),
      size = Size(bodyW, bodyH),
      cornerRadius = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx()),
      style = Stroke(width = stroke),
    )

    drawRoundRect(
      color = accent.copy(alpha = 0.85f),
      topLeft = Offset(bodyW + 0.6.dp.toPx(), bodyH * 0.30f),
      size = Size(1.3.dp.toPx(), bodyH * 0.40f),
      cornerRadius = CornerRadius(0.6.dp.toPx(), 0.6.dp.toPx()),
    )

    val innerPad = 1.7.dp.toPx()
    val gap = 0.8.dp.toPx()
    val usableW = bodyW - innerPad * 2f - gap * 3f
    val barW = usableW / 4f
    val barH = bodyH - innerPad * 2f
    val activeBars =
      when {
        percent >= 80 -> 4
        percent >= 55 -> 3
        percent >= 30 -> 2
        percent > 5 -> 1
        else -> 0
      }

    for (i in 0 until activeBars) {
      val x = innerPad + i * (barW + gap)
      drawRoundRect(
        color = accent,
        topLeft = Offset(x, innerPad),
        size = Size(barW, barH),
        cornerRadius = CornerRadius(0.7.dp.toPx(), 0.7.dp.toPx()),
      )
    }
  }
}

private fun formatCompassLabel(degrees: Int): String {
  return when (degrees % 360) {
    0 -> "N"
    45 -> "NE"
    90 -> "E"
    135 -> "SE"
    180 -> "S"
    225 -> "SW"
    270 -> "W"
    315 -> "NW"
    else -> (degrees % 360).toString().padStart(3, '0')
  }
}

private fun format24HourTime(): String {
  return SimpleDateFormat("HH:mm", Locale.US).format(Date())
}
