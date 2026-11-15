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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.PathEffect
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
import com.example.gesture.DashboardPage
import com.example.mental.CognitiveLevel
import java.util.Locale

/** iOS dark-mode system palette (vibrant variants). */
internal object Ios {
  // Colour discipline: everything is black / white / gray, except status.
  // Green = nominal, Yellow = caution, Red = warning. Nothing else carries colour.
  val Red = Color(0xFFFF453A)
  val Yellow = Color(0xFFFFD60A)
  val Green = Color(0xFF30D158)
  val Orange = Yellow // elevated == caution
  val Mint = Color.White
  val Teal = Color.White
  val Cyan = Color.White
  val Blue = Color.White
  val Indigo = Color.White
  val Purple = Color.White
  val Pink = Color.White
  val Label = Color(0xFFFFFFFF)
  val Secondary = Color(0x99EBEBF5)
  val Tertiary = Color(0x4DEBEBF5)
  val Separator = Color(0x5C545458)
  val Material = Color(0xC71C1C1E) // ~78% "thin material" over the passthrough
  val Grouped = Color(0x332C2C2E)
  val Fill = Color(0x3D787880)
}

@Suppress("UNUSED_PARAMETER")
internal fun hazardColor(page: DashboardPage): Color = Color.White

internal fun levelColor(level: CognitiveLevel): Color =
  when (level) {
    CognitiveLevel.NOMINAL -> Ios.Green
    CognitiveLevel.CAUTION -> Ios.Yellow
    CognitiveLevel.WARNING -> Ios.Red
    CognitiveLevel.EMERGENCY -> Ios.Red
  }

internal fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.US, pattern, *args)

internal fun formatDuration(seconds: Int): String {
  val s = seconds.coerceAtLeast(0)
  return if (s >= 3600) fmt("%d:%02dh", s / 3600, (s % 3600) / 60) else fmt("%02d:%02d", s / 60, s % 60)
}

internal fun formatMinutes(min: Double): String {
  val m = min.coerceAtLeast(0.0).toInt()
  return if (m >= 60) fmt("%dh %02dm", m / 60, m % 60) else fmt("%dm", m)
}

/** SF-style text scale (system sans; sentence case, tabular numbers). */
internal fun hudText(
  size: TextUnit,
  color: Color = Ios.Label,
  weight: FontWeight = FontWeight.SemiBold,
  spacing: TextUnit = 0.sp,
): TextStyle =
  TextStyle(
    color = color,
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = spacing,
    fontFeatureSettings = "tnum",
  )

internal object IosType {
  val largeNumber = hudText(30.sp, weight = FontWeight.Bold, spacing = (-0.5).sp)
  val title = hudText(15.sp, weight = FontWeight.Bold, spacing = (-0.2).sp)
  val headline = hudText(11.5.sp, weight = FontWeight.SemiBold)
  val body = hudText(10.5.sp, weight = FontWeight.Normal)
  val footnote = hudText(9.sp, Ios.Secondary, FontWeight.Normal)
  val caption = hudText(8.sp, Ios.Secondary, FontWeight.Medium)
}

/** Translucent iOS material card with a hairline edge and soft top highlight ("liquid glass"). */
@Composable
internal fun GlassPanel(
  modifier: Modifier = Modifier,
  corner: Dp = 26.dp,
  accent: Color? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val shape = RoundedCornerShape(corner)
  Box(
    modifier =
      modifier
        .clip(shape)
        .background(Ios.Material)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent, Color.Transparent)))
        .border(if (accent != null) 1.dp else 0.5.dp, accent?.copy(alpha = 0.85f) ?: Color.White.copy(alpha = 0.14f), shape),
    content = content,
  )
}

/** Capsule like iOS status badges. */
@Composable
internal fun StatusPill(text: String, color: Color, tag: String, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .testTag(tag)
        .semantics(mergeDescendants = true) {}
        .clip(RoundedCornerShape(50))
        .background(color.copy(alpha = 0.20f))
        .padding(horizontal = 8.dp, vertical = 2.5.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = text, style = hudText(9.sp, color), maxLines = 1)
  }
}

/** Rounded-square numbered glyph, like iOS Settings icons (numbers follow NASA's 5 hazards). */
@Composable
internal fun HazardBadge(page: DashboardPage, size: Dp = 22.dp) {
  Box(
    Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color.White.copy(alpha = 0.92f)),
    contentAlignment = Alignment.Center,
  ) {
    Text(if (page.hazard == 0) "D" else "${page.hazard}", style = hudText((size.value * 0.55f).sp, Color.Black, FontWeight.Bold))
  }
}

@Composable
internal fun TileHeader(page: DashboardPage, pillText: String, pillColor: Color, pillTag: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    HazardBadge(page)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Text(page.title, style = IosType.title, maxLines = 1)
      Text(page.focus, style = IosType.footnote, maxLines = 1)
    }
    StatusPill(pillText, pillColor, pillTag)
  }
}

/** iOS inset-grouped list container. */
@Composable
internal fun GroupedList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier.clip(RoundedCornerShape(14.dp)).background(Ios.Grouped).padding(horizontal = 10.dp, vertical = 2.dp),
    content = content,
  )
}

/** One row of a grouped list: tinted dot, label left, value right, inset separator. */
@Composable
internal fun ListRow(
  label: String,
  value: String,
  valueColor: Color = Ios.Label,
  dot: Color? = null,
  separator: Boolean = true,
  valueTag: String? = null,
) {
  Column {
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
      if (dot != null) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
      }
      Text(label, style = IosType.body, maxLines = 1, modifier = Modifier.weight(1f))
      Text(
        value,
        style = hudText(10.5.sp, valueColor, FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
      )
    }
    if (separator) Box(Modifier.fillMaxWidth().padding(start = if (dot != null) 12.dp else 0.dp).height(0.5.dp).background(Ios.Separator))
  }
}

/** Back-compat compact row used by older call sites. */
@Composable
internal fun MetricLine(label: String, value: String, valueColor: Color = Ios.Label, modifier: Modifier = Modifier, valueTag: String? = null) {
  Row(modifier.fillMaxWidth().height(15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = IosType.caption, maxLines = 1)
    Text(value, style = hudText(9.5.sp, valueColor), maxLines = 1, modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier)
  }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(0.5.dp).background(Ios.Separator))
}

@Composable
internal fun Dot(color: Color, size: Dp = 6.dp) {
  Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun LevelBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
  Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Ios.Fill)) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(height).clip(CircleShape).background(color))
  }
}

/** Apple-Watch-style ring with a dim track. */
@Composable
internal fun ActivityRing(fraction: Float, color: Color, modifier: Modifier, stroke: Dp = 8.dp) {
  Canvas(modifier) {
    val sw = stroke.toPx()
    val arc = Size(size.width - sw, size.height - sw)
    val tl = Offset(sw / 2f, sw / 2f)
    drawArc(color.copy(alpha = 0.22f), 0f, 360f, false, tl, arc, style = Stroke(sw))
    drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
  }
}

/** Cognitive-load gauge: 270 deg sweep, green->red gradient, forecast tick. */
@Composable
internal fun LoadGauge(value: Int, color: Color, modifier: Modifier = Modifier, stroke: Dp = 9.dp, forecast: Int? = null) {
  Canvas(modifier) {
    val sw = stroke.toPx()
    val arc = Size(size.width - sw, size.height - sw)
    val tl = Offset(sw / 2f, sw / 2f)
    val start = 135f
    val sweep = 270f
    drawArc(Ios.Fill, start, sweep, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
    drawArc(
      color,
      start,
      sweep * (value.coerceIn(0, 100) / 100f),
      false,
      tl,
      arc,
      style = Stroke(sw, cap = StrokeCap.Round),
    )
    if (forecast != null) {
      val a = Math.toRadians((start + sweep * forecast.coerceIn(0, 100) / 100f).toDouble())
      val r = size.minDimension / 2f - sw / 2f
      val c = Offset(size.width / 2f, size.height / 2f)
      drawCircle(Color.White, sw * 0.32f, Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat()))
    }
  }
}

/** Stocks-style sparkline with dashed threshold lines and a dashed forecast extension. */
@Composable
internal fun TrendChart(
  history: List<Int>,
  forecast: Int?,
  color: Color,
  modifier: Modifier = Modifier,
  thresholds: List<Pair<Int, Color>> = listOf(70 to Ios.Yellow, 90 to Ios.Red, 95 to Ios.Red),
) {
  Canvas(modifier) {
    val h = size.height
    val w = size.width
    fun y(v: Int) = h - (v.coerceIn(0, 100) / 100f) * h
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    thresholds.forEach { (t, c) -> drawLine(c.copy(alpha = 0.45f), Offset(0f, y(t)), Offset(w, y(t)), 0.8.dp.toPx(), pathEffect = dash) }
    if (history.size < 2) return@Canvas
    val histW = w * 0.78f
    val step = histW / (history.size - 1)
    val path = androidx.compose.ui.graphics.Path()
    history.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
    val fill = androidx.compose.ui.graphics.Path().apply {
      addPath(path)
      lineTo(histW, h)
      lineTo(0f, h)
      close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
    drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
    val last = Offset(histW, y(history.last()))
    if (forecast != null) {
      drawLine(Color.White.copy(alpha = 0.75f), last, Offset(w, y(forecast)), 1.4.dp.toPx(), pathEffect = dash, cap = StrokeCap.Round)
      drawCircle(Color.White, 2.5.dp.toPx(), Offset(w - 2.dp.toPx(), y(forecast)), style = Stroke(1.2.dp.toPx()))
    }
    drawCircle(color, 3.dp.toPx(), last)
  }
}


/** visionOS-style light glass tokens. */
internal object Vision {
  val Window = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.13f)))
  val WindowEdge = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.30f)))
  val Card = Color.White.copy(alpha = 0.13f)
  val CardStrong = Color.White.copy(alpha = 0.22f)
  val Well = Color.Black.copy(alpha = 0.16f)
  val Text2 = Color.White.copy(alpha = 0.68f)
  val Text3 = Color.White.copy(alpha = 0.45f)
}

/** Frosted bento card: small caption on top, big value, optional unit and footnote. */
@Composable
internal fun BentoCard(
  title: String,
  modifier: Modifier = Modifier,
  tint: Color? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = RoundedCornerShape(18.dp)
  Column(
    modifier
      .clip(shape)
      .background(Vision.Card)
      .then(if (tint != null) Modifier.background(Brush.linearGradient(listOf(tint.copy(alpha = 0.26f), tint.copy(alpha = 0.06f)))) else Modifier)
      .border(0.5.dp, Color.White.copy(alpha = 0.16f), shape)
      .padding(horizontal = 11.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(title, style = hudText(8.5.sp, Vision.Text2, FontWeight.Medium), maxLines = 1)
    content()
  }
}

@Composable
internal fun BigValue(value: String, unit: String? = null, color: Color = Ios.Label, size: TextUnit = 20.sp, tag: String? = null) {
  Row(verticalAlignment = Alignment.Bottom) {
    Text(
      value,
      style = hudText(size, color, FontWeight.SemiBold, (-0.3).sp),
      maxLines = 1,
      modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
    if (unit != null) {
      Spacer(Modifier.width(3.dp))
      Text(unit, style = hudText(8.5.sp, Vision.Text2, FontWeight.Medium), modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
    }
  }
}

@Composable
internal fun SmallNote(text: String, color: Color = Vision.Text3, maxLines: Int = 1, tag: String? = null) {
  Text(text, style = hudText(7.5.sp, color, FontWeight.Normal), maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
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
internal fun Modifier.fishEyeLens(strength: Float = 0.05f, enabled: Boolean = true): Modifier {
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
