package com.example.hud

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mental.CognitiveLevel
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SpecularWhite
import java.util.Locale

internal fun levelColor(level: CognitiveLevel): Color =
  when (level) {
    CognitiveLevel.NOMINAL -> IosSfGreen
    CognitiveLevel.CAUTION -> IosSfAmber
    CognitiveLevel.WARNING -> Color(0xFFFF6A2B)
    CognitiveLevel.EMERGENCY -> IosSfRed
  }

internal fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.US, pattern, *args)

internal fun formatDuration(seconds: Int): String {
  val s = seconds.coerceAtLeast(0)
  return if (s >= 3600) fmt("%d:%02dh", s / 3600, (s % 3600) / 60) else fmt("%02d:%02d", s / 60, s % 60)
}

internal fun hudText(
  size: TextUnit,
  color: Color = IosLabelPrimary,
  weight: FontWeight = FontWeight.SemiBold,
  spacing: TextUnit = 0.3.sp,
): TextStyle =
  TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = spacing,
    fontFeatureSettings = "tnum",
  )

/** Frosted visor glass with a curved specular rim. */
@Composable
internal fun GlassPanel(
  modifier: Modifier = Modifier,
  corner: Dp = 30.dp,
  tint: Color = Color.Transparent,
  accent: Color? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val shape = RoundedCornerShape(corner)
  Box(
    modifier =
      modifier
        .clip(shape)
        .background(ObsidianBlack.copy(alpha = 0.30f))
        .background(
          Brush.radialGradient(
            colors = listOf(SpecularWhite.copy(alpha = 0.16f), SpecularWhite.copy(alpha = 0.06f)),
            radius = 900f,
          )
        )
        .background(tint)
        .border(
          width = if (accent != null) 1.1.dp else 0.8.dp,
          brush =
            Brush.linearGradient(
              colors =
                if (accent != null) listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.30f))
                else listOf(SpecularWhite.copy(alpha = 0.62f), SpecularWhite.copy(alpha = 0.12f), SpecularWhite.copy(alpha = 0.34f))
            ),
          shape = shape,
        ),
    content = content,
  )
}

@Composable
internal fun StatusPill(text: String, color: Color, tag: String, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(50)
  Box(
    modifier =
      modifier
        .testTag(tag)
        .semantics(mergeDescendants = true) {}
        .clip(shape)
        .background(color.copy(alpha = 0.18f))
        .border(0.75.dp, color.copy(alpha = 0.65f), shape)
        .padding(horizontal = 9.dp, vertical = 3.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = text, style = hudText(9.5.sp, color), maxLines = 1)
  }
}

@Composable
internal fun TileHeader(title: String, subtitle: String, pillText: String, pillColor: Color, pillTag: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(title, style = hudText(14.sp, spacing = 1.2.sp))
      Text(subtitle, style = hudText(7.5.sp, IosLabelSecondary, FontWeight.Medium, 0.6.sp))
    }
    StatusPill(pillText, pillColor, pillTag)
  }
}

/** Compact label / value row used inside tiles. */
@Composable
internal fun MetricLine(
  label: String,
  value: String,
  valueColor: Color = IosLabelPrimary,
  modifier: Modifier = Modifier,
  valueTag: String? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth().height(15.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = hudText(8.sp, IosLabelSecondary, FontWeight.Medium), maxLines = 1)
    Text(
      value,
      style = hudText(9.5.sp, valueColor),
      maxLines = 1,
      overflow = TextOverflow.Clip,
      modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
    )
  }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(0.5.dp).background(SpecularWhite.copy(alpha = 0.16f)))
}

/** Gauge arc 0..100 with threshold ticks at 70 / 90 / 95. */
@Composable
internal fun LoadGauge(value: Int, color: Color, modifier: Modifier = Modifier, stroke: Dp = 7.dp, showTicks: Boolean = true) {
  Canvas(modifier) {
    val sw = stroke.toPx()
    val arcSize = Size(size.width - sw, size.height - sw)
    val tl = Offset(sw / 2f, sw / 2f)
    val start = 135f
    val sweep = 270f
    drawArc(SpecularWhite.copy(alpha = 0.14f), start, sweep, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
    drawArc(
      color,
      start,
      sweep * (value.coerceIn(0, 100) / 100f),
      false,
      tl,
      arcSize,
      style = Stroke(sw, cap = StrokeCap.Round),
    )
    if (showTicks) {
      listOf(70 to IosSfAmber, 90 to Color(0xFFFF6A2B), 95 to IosSfRed).forEach { (t, c) ->
        val a = Math.toRadians((start + sweep * t / 100f).toDouble())
        val r = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawLine(
          c,
          Offset(cx + (r - sw * 1.6f) * kotlin.math.cos(a).toFloat(), cy + (r - sw * 1.6f) * kotlin.math.sin(a).toFloat()),
          Offset(cx + (r + 1f) * kotlin.math.cos(a).toFloat(), cy + (r + 1f) * kotlin.math.sin(a).toFloat()),
          strokeWidth = 1.6.dp.toPx(),
          cap = StrokeCap.Round,
        )
      }
    }
  }
}

@Composable
internal fun LevelBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(SpecularWhite.copy(alpha = 0.14f))) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(color))
  }
}

@Composable
internal fun Dot(color: Color, size: Dp = 6.dp) {
  Box(Modifier.size(size).clip(CircleShape).background(color))
}

private const val BARREL_SHADER =
  """
  uniform shader content;
  uniform float2 size;
  uniform float strength;
  half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 d = (p - c) / length(c);
    float r2 = dot(d, d);
    float2 src = c + d * (1.0 + strength * r2) * length(c);
    return content.eval(src);
  }
  """

/** Subtle barrel ("fish-eye") distortion of a layer on API 33+, no-op elsewhere. */
internal fun Modifier.fishEyeLens(strength: Float = 0.055f, enabled: Boolean = true): Modifier {
  if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
  return this.graphicsLayer {
    val w = size.width
    val h = size.height
    if (w > 0f && h > 0f) {
      renderEffect =
        try {
          val shader = RuntimeShader(BARREL_SHADER)
          shader.setFloatUniform("size", w, h)
          shader.setFloatUniform("strength", strength)
          RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        } catch (_: Throwable) {
          null
        }
    }
  }
}
