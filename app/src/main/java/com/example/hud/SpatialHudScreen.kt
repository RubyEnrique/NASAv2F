package com.example.hud

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.MediaPipeHandAnalyzer
import com.example.gesture.DashboardMode
import com.example.gesture.HandLandmarkPoint
import com.example.ui.theme.IosGlassHairline
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfBlue
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SpecularWhite
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun SpatialHudScreen(
  uiState: HudUiState,
  onRequestPermission: () -> Unit,
  onHandsDetected: (List<List<HandLandmarkPoint>>, Long) -> Unit,
  onInvisibleCornerTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val blurRadius by
    animateFloatAsState(
      targetValue = if (uiState.hasCameraPermission && uiState.isDashboardOpen) 54f else 0f,
      animationSpec =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
      label = "backdrop_blur_radius",
    )

  // Gently dim and recede corner telemetry cards when the frosted dashboard is open.
  val telemetryAlpha by
    animateFloatAsState(
      targetValue = if (uiState.isDashboardOpen) 0.34f else 1f,
      animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
      label = "telemetry_dim_alpha",
    )
  val telemetryScale by
    animateFloatAsState(
      targetValue = if (uiState.isDashboardOpen) 0.97f else 1f,
      animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
      label = "telemetry_dim_scale",
    )

  val density = LocalDensity.current.density

  Box(modifier = modifier.fillMaxSize().background(ObsidianBlack)) {
    if (uiState.hasCameraPermission) {
      RearCameraPassThrough(
        blurRadius = blurRadius,
        onHandsDetected = onHandsDetected,
        modifier = Modifier.fillMaxSize(),
      )

      // Consume every touch and swipe gesture across the viewport to lock the user in.
      Box(
        modifier =
          Modifier.fillMaxSize()
            .testTag("hud_touch_shield")
            .pointerInput(Unit) {
              awaitEachGesture {
                while (true) {
                  val event = awaitPointerEvent()
                  event.changes.forEach { it.consume() }
                }
              }
            }
      )

      // Safe-area HUD layer avoids clipping by landscape display cutouts / punch-holes.
      Box(modifier = Modifier.fillMaxSize().displayCutoutPadding()) {
        PalmHoldProgressRing(
          progress = uiState.palmHoldProgress,
          visible =
            uiState.dashboardMode != DashboardMode.PERMANENT && uiState.palmHoldProgress > 0.015f,
          modifier = Modifier.align(Alignment.Center),
        )

        VitalsTelemetryWidget(
          heartRateBpm = uiState.heartRateBpm,
          modifier =
            Modifier.align(Alignment.BottomStart)
              .padding(start = 16.dp, bottom = 14.dp)
              .graphicsLayer {
                alpha = telemetryAlpha
                scaleX = telemetryScale
                scaleY = telemetryScale
                rotationY = 11f
                rotationX = 3f
                cameraDistance = 18f * density
                transformOrigin = TransformOrigin(0f, 0.5f)
              },
        )

        SuitTelemetryWidget(
          modifier =
            Modifier.align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = 14.dp)
              .graphicsLayer {
                alpha = telemetryAlpha
                scaleX = telemetryScale
                scaleY = telemetryScale
                rotationY = -11f
                rotationX = 3f
                cameraDistance = 18f * density
                transformOrigin = TransformOrigin(1f, 0.5f)
              },
        )

        VisionEmptyDashboard(
          isOpen = uiState.isDashboardOpen,
          distanceMeters = uiState.distanceMeters,
          speedMps = uiState.speedMps,
          distanceGoalMeters = uiState.distanceGoalMeters,
          modifier = Modifier.fillMaxSize(),
        )

        TwoFistsDualRingCue(
          visible = uiState.dashboardMode == DashboardMode.PERMANENT && uiState.isTwoFistsReady,
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
      }
    } else {
      ZeroTextPermissionAperture(
        onRequestPermission = onRequestPermission,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // Always-visible ultra-minimal iOS top-center compass with 24hr time & live phone battery.
    GameGradientCompass(
      headingDegrees = uiState.headingDegrees,
      batteryPercent = uiState.batteryPercent,
      modifier = Modifier.align(Alignment.TopCenter).displayCutoutPadding().padding(top = 6.dp),
    )

    // Invisible 5-tap exit zone at the upper-right corner (zero visual tell, zero ripple).
    Box(
      modifier =
        Modifier.align(Alignment.TopEnd)
          .size(64.dp)
          .testTag("invisible_exit_zone")
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onInvisibleCornerTap,
          )
    )
  }
}

@Composable
private fun RearCameraPassThrough(
  blurRadius: Float,
  onHandsDetected: (List<List<HandLandmarkPoint>>, Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  val handAnalyzer = remember(context) { MediaPipeHandAnalyzer(context, onHandsDetected) }
  val previewView =
    remember(context) {
      PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
      }
    }

  DisposableEffect(lifecycleOwner, previewView, handAnalyzer) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    val mainExecutor = ContextCompat.getMainExecutor(context)

    cameraProviderFuture.addListener(
      {
        try {
          val cameraProvider = cameraProviderFuture.get()
          val preview =
            Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

          val imageAnalysis =
            ImageAnalysis.Builder()
              .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .build()
              .also { it.setAnalyzer(analysisExecutor, handAnalyzer) }

          cameraProvider.unbindAll()
          cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
          )
        } catch (t: Throwable) {
          Log.w("SpatialHudScreen", "CameraX binding failed", t)
        }
      },
      mainExecutor,
    )

    onDispose {
      try {
        if (cameraProviderFuture.isDone) {
          cameraProviderFuture.get().unbindAll()
        }
      } catch (_: Throwable) {}
      handAnalyzer.close()
      analysisExecutor.shutdown()
    }
  }

  AndroidView(
    factory = { previewView },
    update = { view ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.setRenderEffect(
          if (blurRadius > 1.5f) {
            RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
          } else {
            null
          }
        )
      }
    },
    modifier = modifier.testTag("camera_preview"),
  )
}

@Composable
private fun PalmHoldProgressRing(
  progress: Float,
  visible: Boolean,
  modifier: Modifier = Modifier,
) {
  val alpha by
    animateFloatAsState(
      targetValue = if (visible) 1f else 0f,
      animationSpec = tween(durationMillis = 150),
      label = "ring_alpha",
    )
  val animatedSweep by
    animateFloatAsState(
      targetValue = progress.coerceIn(0f, 1f),
      animationSpec = tween(durationMillis = 60),
      label = "ring_sweep",
    )

  if (alpha > 0.01f) {
    Canvas(modifier = modifier.size(48.dp).testTag("palm_hold_ring")) {
      val strokeWidth = 2.4.dp.toPx()
      val inset = strokeWidth / 2f
      val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
      val topLeft = Offset(inset, inset)

      drawArc(
        color = SpecularWhite.copy(alpha = 0.22f * alpha),
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
      )

      drawArc(
        color = SpecularWhite.copy(alpha = 0.94f * alpha),
        startAngle = -90f,
        sweepAngle = 360f * animatedSweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
      )
    }
  }
}

@Composable
private fun TwoFistsDualRingCue(
  visible: Boolean,
  modifier: Modifier = Modifier,
) {
  val alpha by
    animateFloatAsState(
      targetValue = if (visible) 1f else 0f,
      animationSpec = tween(durationMillis = 180),
      label = "dual_ring_alpha",
    )
  val infiniteTransition = rememberInfiniteTransition(label = "dual_ring_pulse")
  val pulse by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "dual_ring_pulse_wave",
    )

  if (alpha > 0.01f) {
    Canvas(modifier = modifier.size(width = 88.dp, height = 32.dp).testTag("two_fists_dual_ring")) {
      val centerY = size.height / 2f
      val centerX = size.width / 2f
      val spacing = 15.dp.toPx()
      val baseRadius = 7.5.dp.toPx() * (0.90f + 0.18f * pulse)
      val strokeWidth = 1.8.dp.toPx()
      val ringAlpha = (0.55f + 0.40f * pulse) * alpha

      listOf(-spacing, spacing).forEach { dx ->
        val ringCenter = Offset(centerX + dx, centerY)
        drawCircle(
          brush =
            Brush.verticalGradient(
              colors =
                listOf(
                  SpecularWhite.copy(alpha = ringAlpha),
                  IosSfBlue.copy(alpha = ringAlpha * 0.85f),
                ),
            ),
          radius = baseRadius,
          center = ringCenter,
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
      }
    }
  }
}

@Composable
private fun VisionEmptyDashboard(
  isOpen: Boolean,
  distanceMeters: Float,
  speedMps: Float,
  distanceGoalMeters: Float,
  modifier: Modifier = Modifier,
) {
  val transition = updateTransition(targetState = isOpen, label = "ios_dashboard_transition")

  val panelAlpha by
    transition.animateFloat(
      transitionSpec = {
        if (targetState) tween(durationMillis = 260, easing = FastOutSlowInEasing)
        else tween(durationMillis = 200, easing = FastOutSlowInEasing)
      },
      label = "panel_alpha",
    ) { open ->
      if (open) 1f else 0f
    }

  val panelScale by
    transition.animateFloat(
      transitionSpec = {
        if (targetState) {
          spring(dampingRatio = 0.84f, stiffness = 340f)
        } else {
          tween(durationMillis = 220, easing = FastOutSlowInEasing)
        }
      },
      label = "panel_scale",
    ) { open ->
      if (open) 1f else 0.86f
    }

  val panelOffsetY by
    transition.animateDp(
      transitionSpec = {
        if (targetState) {
          spring(dampingRatio = 0.85f, stiffness = 340f)
        } else {
          tween(durationMillis = 220, easing = FastOutSlowInEasing)
        }
      },
      label = "panel_offset_y",
    ) { open ->
      if (open) 0.dp else 22.dp
    }

  val scrimAlpha by
    transition.animateFloat(
      transitionSpec = { tween(durationMillis = 240, easing = FastOutSlowInEasing) },
      label = "scrim_alpha",
    ) { open ->
      if (open) 0.14f else 0f
    }

  if (panelAlpha > 0.01f) {
    Box(
      modifier = modifier.background(ObsidianBlack.copy(alpha = scrimAlpha)),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
          Modifier.offset(y = panelOffsetY + 10.dp).graphicsLayer {
            alpha = panelAlpha
            scaleX = panelScale
            scaleY = panelScale
          },
      ) {
        val windowShape = RoundedCornerShape(40.dp)
        val glassSheenBrush =
          Brush.linearGradient(
            colors =
              listOf(
                SpecularWhite.copy(alpha = 0.28f),
                SpecularWhite.copy(alpha = 0.10f),
                SpecularWhite.copy(alpha = 0.18f),
              ),
          )
        val specularRimBrush =
          Brush.linearGradient(
            colors =
              listOf(
                SpecularWhite.copy(alpha = 0.72f),
                SpecularWhite.copy(alpha = 0.22f),
                SpecularWhite.copy(alpha = 0.44f),
              ),
          )

        Box(
          modifier =
            Modifier.widthIn(max = 540.dp)
              .fillMaxWidth(0.64f)
              .fillMaxHeight(0.58f)
              .testTag("vision_dashboard_panel")
              .clip(windowShape)
              .background(SpecularWhite.copy(alpha = 0.16f))
              .background(glassSheenBrush)
              .border(width = 1.dp, brush = specularRimBrush, shape = windowShape),
          contentAlignment = Alignment.Center,
        ) {
          DashboardDistanceRing(
            distanceMeters = distanceMeters,
            speedMps = speedMps,
            distanceGoalMeters = distanceGoalMeters,
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
          modifier =
            Modifier.width(64.dp)
              .height(4.5.dp)
              .clip(CircleShape)
              .background(SpecularWhite.copy(alpha = 0.56f))
        )
      }
    }
  }
}

@Composable
private fun DashboardDistanceRing(
  distanceMeters: Float,
  speedMps: Float,
  distanceGoalMeters: Float,
) {
  val safeGoal = distanceGoalMeters.coerceAtLeast(100f)
  val safeDistance = distanceMeters.coerceAtLeast(0f)
  val rawProgress = (safeDistance / safeGoal).coerceIn(0f, 1f)
  val animatedProgress by
    animateFloatAsState(
      targetValue = rawProgress,
      animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
      label = "distance_ring_progress",
    )

  val (distanceValueText, distanceUnitText) =
    if (safeDistance >= 1000f) {
      String.format(Locale.US, "%.2f", safeDistance / 1000f) to "KM"
    } else {
      "${safeDistance.roundToInt()}" to "METERS"
    }
  val goalText = String.format(Locale.US, "%.1f km", safeGoal / 1000f)
  val speedFormatted = String.format(Locale.US, "%.1f m/s", speedMps.coerceAtLeast(0f))

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Canvas(modifier = Modifier.size(120.dp).testTag("dashboard_distance_ring")) {
        val strokeWidth = 8.5.dp.toPx()
        val inset = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(inset, inset)

        drawArc(
          color = SpecularWhite.copy(alpha = 0.16f),
          startAngle = 0f,
          sweepAngle = 360f,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        if (animatedProgress > 0.004f) {
          drawArc(
            brush = Brush.linearGradient(colors = listOf(IosSfBlue, IosSfGreen)),
            startAngle = -90f,
            sweepAngle = 360f * animatedProgress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
          )
        }
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = distanceValueText,
          style =
            TextStyle(
              color = IosLabelPrimary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 27.sp,
              fontFeatureSettings = "tnum",
            ),
          modifier = Modifier.testTag("dashboard_distance_text"),
        )
        Text(
          text = distanceUnitText,
          style =
            TextStyle(
              color = IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 8.5.sp,
              letterSpacing = 0.6.sp,
            ),
          modifier = Modifier.testTag("dashboard_distance_unit_text"),
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))
    Box(modifier = Modifier.width(164.dp).height(0.5.dp).background(IosGlassHairline))
    Spacer(modifier = Modifier.height(10.dp))

    Row(
      modifier = Modifier.width(176.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "SPEED",
          style =
            TextStyle(
              color = IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 7.5.sp,
              letterSpacing = 0.4.sp,
            ),
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
          text = speedFormatted,
          style =
            TextStyle(
              color = IosSfBlue,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 11.5.sp,
              fontFeatureSettings = "tnum",
            ),
          modifier = Modifier.testTag("dashboard_speed_text"),
        )
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "GOAL",
          style =
            TextStyle(
              color = IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 7.5.sp,
              letterSpacing = 0.4.sp,
            ),
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
          text = goalText,
          style =
            TextStyle(
              color = IosLabelPrimary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 11.5.sp,
              fontFeatureSettings = "tnum",
            ),
        )
      }
    }
  }
}

@Composable
private fun ZeroTextPermissionAperture(
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .background(ObsidianBlack)
        .testTag("permission_trigger_area")
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onRequestPermission,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(64.dp)) {
      val outerStroke = 2.dp.toPx()
      drawCircle(
        color = SpecularWhite.copy(alpha = 0.34f),
        radius = size.minDimension / 2f - outerStroke,
        style = Stroke(width = outerStroke),
      )
      drawCircle(
        color = SpecularWhite.copy(alpha = 0.22f),
        radius = size.minDimension * 0.22f,
      )
    }
  }
}
