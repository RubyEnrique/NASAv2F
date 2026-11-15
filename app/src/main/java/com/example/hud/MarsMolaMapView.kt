package com.example.hud

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfBlue
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SpecularWhite
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Monochrome rendering of the MOLA relief (colour is reserved for status). */
private val GRAYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

data class MarsTrackPoint(
  val eastMeters: Float,
  val northMeters: Float,
)

data class MarsTileKey(
  val z: Int,
  val row: Int,
  val col: Int,
)

data class MarsTilePosition(
  val key: MarsTileKey,
  val fractionX: Float,
  val fractionY: Float,
)

data class MarsTileSourceCrop(
  val key: MarsTileKey,
  val scaleDivisor: Int,
  val subTileX: Int,
  val subTileY: Int,
)

data class ResolvedMarsTile(
  val bitmap: ImageBitmap,
  val crop: MarsTileSourceCrop,
)

object MarsMolaTileCoords {
  const val JEZERO_LAT_DEG = 18.38
  const val JEZERO_LON_DEG = 77.58
  const val DEFAULT_ZOOM = 4
  const val MIN_TILE_ZOOM = 4
  const val MAX_TILE_ZOOM = 6
  const val MIN_MAP_ZOOM = 1.0f
  const val MAX_MAP_ZOOM = 5.0f
  const val MAX_PAN_EAST_DEG = 8.5f
  const val MAX_PAN_NORTH_DEG = 7.0f

  fun assetPath(z: Int, row: Int, col: Int): String {
    return "mars_mola_tiles/$z/$row/$col.jpg"
  }

  fun selectTileZoom(mapZoom: Float): Int {
    return when {
      mapZoom >= 2.75f -> 6
      mapZoom >= 1.65f -> 5
      else -> 4
    }
  }

  /**
   * Resolves the ordered lookup candidates for a requested tile: first the exact tile,
   * then ancestor tiles with cropped sub-tile coordinates so edges stay covered offline.
   */
  fun fallbackCandidates(key: MarsTileKey): List<MarsTileSourceCrop> {
    val candidates = ArrayList<MarsTileSourceCrop>(3)
    for (z in key.z downTo MIN_TILE_ZOOM) {
      val dz = key.z - z
      val divisor = 1 shl dz
      val ancestorRow = key.row shr dz
      val ancestorCol = key.col shr dz
      val subY = key.row and (divisor - 1)
      val subX = key.col and (divisor - 1)
      candidates.add(
        MarsTileSourceCrop(
          key = MarsTileKey(z = z, row = ancestorRow, col = ancestorCol),
          scaleDivisor = divisor,
          subTileX = subX,
          subTileY = subY,
        )
      )
    }
    return candidates
  }

  fun latLonToTile(latDeg: Double, lonDeg: Double, zoom: Int = DEFAULT_ZOOM): MarsTilePosition {
    val safeZoom = zoom.coerceIn(0, 7)
    val numCols = 2 shl safeZoom
    val numRows = 1 shl safeZoom
    val normalizedLon = (((lonDeg + 180.0) % 360.0) + 360.0) % 360.0
    val clampedLat = latDeg.coerceIn(-89.999, 89.999)

    val u = normalizedLon / 360.0
    val v = (90.0 - clampedLat) / 180.0

    val exactCol = u * numCols
    val exactRow = v * numRows
    val col = floor(exactCol).toInt().coerceIn(0, numCols - 1)
    val row = floor(exactRow).toInt().coerceIn(0, numRows - 1)

    return MarsTilePosition(
      key = MarsTileKey(z = safeZoom, row = row, col = col),
      fractionX = (exactCol - col).toFloat().coerceIn(0f, 1f),
      fractionY = (exactRow - row).toFloat().coerceIn(0f, 1f),
    )
  }

  fun wrapCol(col: Int, zoom: Int): Int {
    val numCols = 2 shl zoom.coerceIn(0, 7)
    return ((col % numCols) + numCols) % numCols
  }
}

object MarsMolaOfflineTileStore {
  private val memoryCache = object : LruCache<String, ImageBitmap>(48) {}

  private fun loadRawAsset(appContext: Context, key: MarsTileKey): ImageBitmap? {
    val cacheKey = "${key.z}/${key.row}/${key.col}"
    memoryCache.get(cacheKey)?.let { return it }
    return try {
      appContext.assets.open(MarsMolaTileCoords.assetPath(key.z, key.row, key.col)).use { stream ->
        val bitmap = BitmapFactory.decodeStream(stream) ?: return null
        val imageBitmap = bitmap.asImageBitmap()
        memoryCache.put(cacheKey, imageBitmap)
        imageBitmap
      }
    } catch (_: Throwable) {
      null
    }
  }

  suspend fun loadBundledTileWithFallback(context: Context, key: MarsTileKey): ResolvedMarsTile? {
    val appContext = context.applicationContext
    return withContext(Dispatchers.IO) {
      for (candidate in MarsMolaTileCoords.fallbackCandidates(key)) {
        val img = loadRawAsset(appContext, candidate.key)
        if (img != null) {
          return@withContext ResolvedMarsTile(bitmap = img, crop = candidate)
        }
      }
      null
    }
  }
}

@Composable
fun MarsMolaMapCanvas(
  trackPoints: List<MarsTrackPoint>,
  headingDegrees: Float,
  hasGpsFix: Boolean,
  modifier: Modifier = Modifier,
  mapZoom: Float = 1f,
  mapTiltDegrees: Float = 0f,
  mapPanEastDeg: Float = 0f,
  mapPanNorthDeg: Float = 0f,
  isMapPinching: Boolean = false,
  crew: List<com.example.crew.CrewMember> = emptyList(),
) {
  val context = LocalContext.current
  val density = LocalDensity.current.density

  val animatedZoom by
    animateFloatAsState(
      targetValue =
        mapZoom.coerceIn(MarsMolaTileCoords.MIN_MAP_ZOOM, MarsMolaTileCoords.MAX_MAP_ZOOM),
      animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
      label = "mola_zoom",
    )
  val animatedTilt by
    animateFloatAsState(
      targetValue = mapTiltDegrees.coerceIn(0f, 42f),
      animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
      label = "mola_tilt",
    )
  val animatedPanEast by
    animateFloatAsState(
      targetValue =
        mapPanEastDeg.coerceIn(
          -MarsMolaTileCoords.MAX_PAN_EAST_DEG,
          MarsMolaTileCoords.MAX_PAN_EAST_DEG,
        ),
      animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
      label = "mola_pan_east",
    )
  val animatedPanNorth by
    animateFloatAsState(
      targetValue =
        mapPanNorthDeg.coerceIn(
          -MarsMolaTileCoords.MAX_PAN_NORTH_DEG,
          MarsMolaTileCoords.MAX_PAN_NORTH_DEG,
        ),
      animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
      label = "mola_pan_north",
    )

  val tileZoom = MarsMolaTileCoords.selectTileZoom(animatedZoom)
  val tierRelativeScale =
    (animatedZoom / 2f.pow((tileZoom - MarsMolaTileCoords.MIN_TILE_ZOOM).toFloat())).coerceIn(
      0.75f,
      2.4f,
    )

  val currentPoint = trackPoints.lastOrNull() ?: MarsTrackPoint(0f, 0f)
  val roverLatDeg = MarsMolaTileCoords.JEZERO_LAT_DEG + (currentPoint.northMeters / 59_200.0)
  val roverLonDeg = MarsMolaTileCoords.JEZERO_LON_DEG + (currentPoint.eastMeters / 56_100.0)
  val cameraLatDeg = (roverLatDeg + animatedPanNorth).coerceIn(-85.0, 85.0)
  val cameraLonDeg = roverLonDeg + animatedPanEast
  val centerTile = MarsMolaTileCoords.latLonToTile(cameraLatDeg, cameraLonDeg, zoom = tileZoom)

  val tileKeys =
    remember(centerTile.key) {
      val numRows = 1 shl centerTile.key.z
      buildList {
        for (dr in -1..1) {
          val row = centerTile.key.row + dr
          if (row in 0 until numRows) {
            for (dc in -1..1) {
              val col = MarsMolaTileCoords.wrapCol(centerTile.key.col + dc, centerTile.key.z)
              add(Triple(dc, dr, MarsTileKey(centerTile.key.z, row, col)))
            }
          }
        }
      }
    }

  var loadedTiles by remember { mutableStateOf<Map<MarsTileKey, ResolvedMarsTile>>(emptyMap()) }

  LaunchedEffect(tileKeys, context) {
    val fetched = mutableMapOf<MarsTileKey, ResolvedMarsTile>()
    tileKeys.forEach { (_, _, key) ->
      val resolved = MarsMolaOfflineTileStore.loadBundledTileWithFallback(context, key)
      if (resolved != null) {
        fetched[key] = resolved
      }
    }
    loadedTiles = fetched
  }

  val infiniteTransition = rememberInfiniteTransition(label = "mars_beacon_pulse")
  val beaconPulse by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "beacon_pulse_wave",
    )

  val coordsText =
    String.format(
      Locale.US,
      "%.2f\u00B0N  %.2f\u00B0E",
      cameraLatDeg,
      cameraLonDeg,
    )
  val zoomTiltTag =
    String.format(
      Locale.US,
      "%.1fx  %d\u00B0  z%d",
      animatedZoom,
      animatedTilt.roundToInt(),
      tileZoom,
    )

  val mapShape = RoundedCornerShape(24.dp)

  Box(
    modifier =
      modifier
        .clip(mapShape)
        .background(Color(0xFF0E1826))
        .border(
          width = if (isMapPinching) 1.15.dp else 0.75.dp,
          color = if (isMapPinching) IosSfBlue.copy(alpha = 0.78f) else SpecularWhite.copy(alpha = 0.24f),
          shape = mapShape,
        ),
  ) {
    Canvas(
      modifier =
        Modifier.fillMaxSize()
          .testTag("mars_mola_map_canvas")
          .graphicsLayer {
            rotationX = animatedTilt
            cameraDistance = 14f * density
            transformOrigin = TransformOrigin(0.5f, 0.58f)
            val reliefScaleBoost = 1f + (animatedTilt / 42f) * 0.14f
            scaleX = reliefScaleBoost
            scaleY = reliefScaleBoost
          },
    ) {
      val w = size.width
      val h = size.height
      val center = Offset(w / 2f, h / 2f)

      drawRect(
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color(0xFF3A3A3C),
                Color(0xFF2C2C2E),
                Color(0xFF1C1C1E),
              ),
            center = Offset(w * 0.48f, h * 0.45f),
            radius = max(w, h) * 0.75f,
          ),
      )

      val drawTileSizePx =
        ((max(w, h) * 0.68f) * tierRelativeScale).roundToInt().coerceAtLeast(128)
      val anchorPixelX = center.x - centerTile.fractionX * drawTileSizePx
      val anchorPixelY = center.y - centerTile.fractionY * drawTileSizePx

      if (loadedTiles.isNotEmpty()) {
        tileKeys.forEach { (dc, dr, key) ->
          val resolved = loadedTiles[key] ?: return@forEach
          val bmp = resolved.bitmap
          val divisor = resolved.crop.scaleDivisor
          val srcW = (bmp.width / divisor).coerceAtLeast(1)
          val srcH = (bmp.height / divisor).coerceAtLeast(1)
          val srcX = (resolved.crop.subTileX * srcW).coerceIn(0, bmp.width - srcW)
          val srcY = (resolved.crop.subTileY * srcH).coerceIn(0, bmp.height - srcH)
          val dstX = (anchorPixelX + dc * drawTileSizePx).roundToInt()
          val dstY = (anchorPixelY + dr * drawTileSizePx).roundToInt()
          drawImage(
            image = bmp,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset(dstX, dstY),
            dstSize = IntSize(drawTileSizePx, drawTileSizePx),
            alpha = 0.92f,
            colorFilter = GRAYSCALE,
          )
        }
        drawRect(color = ObsidianBlack.copy(alpha = 0.18f))
      } else {
        val contourRadii = floatArrayOf(0.18f, 0.30f, 0.44f, 0.60f, 0.78f)
        contourRadii.forEachIndexed { idx, factor ->
          val radius = max(w, h) * 0.52f * factor * tierRelativeScale
          val contourColor =
            when {
              idx <= 1 -> IosSfBlue.copy(alpha = 0.24f)
              idx == 2 -> SpecularWhite.copy(alpha = 0.16f)
              else -> SpecularWhite.copy(alpha = 0.12f)
            }
          drawCircle(
            color = contourColor,
            radius = radius,
            center = Offset(w * 0.52f, h * 0.48f),
            style = Stroke(width = 1.dp.toPx()),
          )
        }
      }

      // Graticule shifts inversely to pan for grounded terrain parallax
      val pxPerDeg =
        drawTileSizePx.toFloat() * (2 shl centerTile.key.z).toFloat() / 360f
      val gridStep = 44.dp.toPx()
      var gx = (((w / 2f) - animatedPanEast * pxPerDeg) % gridStep + gridStep) % gridStep
      while (gx < w) {
        drawLine(
          color = SpecularWhite.copy(alpha = 0.08f),
          start = Offset(gx, 0f),
          end = Offset(gx, h),
          strokeWidth = 0.6.dp.toPx(),
        )
        gx += gridStep
      }
      var gy = (((h / 2f) + animatedPanNorth * pxPerDeg) % gridStep + gridStep) % gridStep
      while (gy < h) {
        drawLine(
          color = SpecularWhite.copy(alpha = 0.08f),
          start = Offset(0f, gy),
          end = Offset(w, gy),
          strokeWidth = 0.6.dp.toPx(),
        )
        gy += gridStep
      }

      val maxExtentMeters =
        trackPoints
          .fold(35f) { acc, pt ->
            val dx = kotlin.math.abs(pt.eastMeters - currentPoint.eastMeters)
            val dy = kotlin.math.abs(pt.northMeters - currentPoint.northMeters)
            max(acc, max(dx, dy))
          }
          .coerceAtLeast(35f)
      val pxPerMeter = ((kotlin.math.min(w, h) * 0.36f) / maxExtentMeters) * animatedZoom
      val roverCenter =
        Offset(
          x = center.x - animatedPanEast * pxPerDeg,
          y = center.y + animatedPanNorth * pxPerDeg,
        )

      fun toCanvas(pt: MarsTrackPoint): Offset {
        return Offset(
          x = roverCenter.x + (pt.eastMeters - currentPoint.eastMeters) * pxPerMeter,
          y = roverCenter.y - (pt.northMeters - currentPoint.northMeters) * pxPerMeter,
        )
      }

      if (trackPoints.size >= 2) {
        val path = Path()
        trackPoints.forEachIndexed { idx, pt ->
          val pos = toCanvas(pt)
          if (idx == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
        }
        drawPath(
          path = path,
          color = IosSfBlue.copy(alpha = 0.35f),
          style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
          path = path,
          color = IosSfBlue,
          style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val originOffset = toCanvas(trackPoints.first())
        drawCircle(
          color = SpecularWhite.copy(alpha = 0.85f),
          radius = 3.5.dp.toPx(),
          center = originOffset,
        )
      }

      val haloRadius = 11.dp.toPx() + 6.dp.toPx() * beaconPulse
      drawCircle(
        color = (if (hasGpsFix) IosSfBlue else IosSfAmber).copy(alpha = 0.28f * (1f - beaconPulse)),
        radius = haloRadius,
        center = roverCenter,
      )

      rotate(degrees = headingDegrees, pivot = roverCenter) {
        val conePath =
          Path().apply {
            moveTo(roverCenter.x, roverCenter.y)
            val coneLen = 22.dp.toPx()
            val halfAngleRad = Math.toRadians(22.0)
            lineTo(
              roverCenter.x - (coneLen * sin(halfAngleRad)).toFloat(),
              roverCenter.y - (coneLen * cos(halfAngleRad)).toFloat(),
            )
            lineTo(
              roverCenter.x + (coneLen * sin(halfAngleRad)).toFloat(),
              roverCenter.y - (coneLen * cos(halfAngleRad)).toFloat(),
            )
            close()
          }
        drawPath(
          path = conePath,
          brush =
            Brush.verticalGradient(
              colors = listOf(Color.Transparent, IosSfBlue.copy(alpha = 0.42f)),
              startY = roverCenter.y - 22.dp.toPx(),
              endY = roverCenter.y,
            ),
        )
      }

      // Crewmates relative to this wearer (white, red when they need help).
      for (m in crew) {
        val e = m.eastMeters ?: continue
        val n = m.northMeters ?: continue
        val p = Offset(roverCenter.x + e * pxPerMeter, roverCenter.y - n * pxPerMeter)
        val c = if (m.emergency) IosSfRed else SpecularWhite
        drawCircle(c.copy(alpha = 0.3f), 9.dp.toPx(), p)
        drawCircle(c, 4.dp.toPx(), p)
      }

      drawCircle(
        color = SpecularWhite,
        radius = 5.5.dp.toPx(),
        center = roverCenter,
      )
      drawCircle(
        color = if (hasGpsFix) IosSfBlue else IosSfAmber,
        radius = 3.5.dp.toPx(),
        center = roverCenter,
      )

      // Subtle viewport center crosshair whenever panned away from rover position
      if (kotlin.math.abs(animatedPanEast) > 0.08f || kotlin.math.abs(animatedPanNorth) > 0.08f) {
        val crossArm = 7.dp.toPx()
        val gap = 2.5.dp.toPx()
        val crossColor = SpecularWhite.copy(alpha = 0.65f)
        drawLine(
          color = crossColor,
          start = Offset(center.x - crossArm, center.y),
          end = Offset(center.x - gap, center.y),
          strokeWidth = 1.dp.toPx(),
          cap = StrokeCap.Round,
        )
        drawLine(
          color = crossColor,
          start = Offset(center.x + gap, center.y),
          end = Offset(center.x + crossArm, center.y),
          strokeWidth = 1.dp.toPx(),
          cap = StrokeCap.Round,
        )
        drawLine(
          color = crossColor,
          start = Offset(center.x, center.y - crossArm),
          end = Offset(center.x, center.y - gap),
          strokeWidth = 1.dp.toPx(),
          cap = StrokeCap.Round,
        )
        drawLine(
          color = crossColor,
          start = Offset(center.x, center.y + gap),
          end = Offset(center.x, center.y + crossArm),
          strokeWidth = 1.dp.toPx(),
          cap = StrokeCap.Round,
        )
      }
    }

    // Minimal top overlay labels (Jezero coordinates + Offline Zoom/Tilt readout)
    Row(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Box(
        modifier =
          Modifier.clip(RoundedCornerShape(50))
            .background(ObsidianBlack.copy(alpha = 0.52f))
            .border(0.5.dp, SpecularWhite.copy(alpha = 0.24f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
      ) {
        Text(
          text = "JEZERO  $coordsText",
          style =
            TextStyle(
              color = IosLabelPrimary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 9.sp,
              fontFeatureSettings = "tnum",
              letterSpacing = 0.3.sp,
            ),
        )
      }

      Box(
        modifier =
          Modifier.testTag("mola_zoom_tilt_pill")
            .clip(RoundedCornerShape(50))
            .background(
              if (isMapPinching) IosSfBlue.copy(alpha = 0.28f) else ObsidianBlack.copy(alpha = 0.52f),
            )
            .border(
              0.5.dp,
              if (isMapPinching) IosSfBlue.copy(alpha = 0.78f) else SpecularWhite.copy(alpha = 0.20f),
              RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Text(
          text = zoomTiltTag,
          style =
            TextStyle(
              color = if (isMapPinching) IosLabelPrimary else IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 8.5.sp,
              fontFeatureSettings = "tnum",
              letterSpacing = 0.4.sp,
            ),
        )
      }
    }
  }
}
