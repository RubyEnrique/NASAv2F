package com.example.hud

import com.example.gesture.DashboardPage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * World-locked tile layout. The ring tiles hang in the air on a horizontal arc, TILE_SPACING_DEG
 * apart, starting at [anchorYaw] (MENTAL). DRILLS hangs below the horizon and follows the head's
 * yaw, so looking down from anywhere shows it. All maths is pure so it can be unit-tested.
 */
object SpatialLayout {
  const val TILE_SPACING_DEG = 40f
  const val DRILLS_PITCH_OFFSET_DEG = -34f
  /** Approximate horizontal field of view of a phone rear camera in landscape. */
  const val VIEW_HFOV_DEG = 66f
  private const val FISHEYE_COMPRESSION = 0.0018f

  data class Projection(
    val xDeg: Float,
    val yDeg: Float,
    val scale: Float,
    val rotationY: Float,
    val rotationX: Float,
    val alpha: Float,
    val visible: Boolean,
  )

  fun angleDelta(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
  }

  fun ringIndex(page: DashboardPage): Int = DashboardPage.ring.indexOf(page).coerceAtLeast(0)

  fun anchorYawFor(page: DashboardPage, headingDeg: Float): Float =
    if (page == DashboardPage.DRILLS) headingDeg else headingDeg - ringIndex(page) * TILE_SPACING_DEG

  fun tileYaw(page: DashboardPage, anchorYaw: Float, headingDeg: Float): Float =
    if (page == DashboardPage.DRILLS) headingDeg else anchorYaw + ringIndex(page) * TILE_SPACING_DEG

  fun tilePitch(page: DashboardPage, anchorPitch: Float): Float =
    if (page == DashboardPage.DRILLS) anchorPitch + DRILLS_PITCH_OFFSET_DEG else anchorPitch

  fun focusedPage(headingDeg: Float, pitchDeg: Float, anchorYaw: Float, anchorPitch: Float): DashboardPage {
    if (pitchDeg - anchorPitch <= DRILLS_PITCH_OFFSET_DEG / 2f) return DashboardPage.DRILLS
    val rel = angleDelta(headingDeg, anchorYaw)
    val idx = (rel / TILE_SPACING_DEG).roundToInt().coerceIn(0, DashboardPage.ring.lastIndex)
    return DashboardPage.ring[idx]
  }

  /**
   * Slightly fish-eyed projection: angular offsets are compressed toward the edges, tiles shrink
   * and turn toward the viewer as they move off-axis, like panels on the inside of a sphere.
   */
  fun project(tileYaw: Float, tilePitch: Float, headingDeg: Float, pitchDeg: Float): Projection {
    val dx = angleDelta(tileYaw, headingDeg)
    val dy = tilePitch - pitchDeg
    val r = kotlin.math.sqrt(dx * dx + dy * dy)
    val compress = (1f - FISHEYE_COMPRESSION * r).coerceAtLeast(0.72f)
    val scale = (1f - 0.0042f * r).coerceIn(0.62f, 1f)
    val visible = abs(dx) < 95f && abs(dy) < 80f
    val alpha = (1f - ((r - 18f) / 70f)).coerceIn(0.28f, 1f)
    return Projection(
      xDeg = dx * compress,
      yDeg = dy * compress,
      scale = scale,
      rotationY = (-dx * 0.42f).coerceIn(-34f, 34f),
      rotationX = (dy * 0.30f).coerceIn(-22f, 22f),
      alpha = alpha,
      visible = visible,
    )
  }
}
