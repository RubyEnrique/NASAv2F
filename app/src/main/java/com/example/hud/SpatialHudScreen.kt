package com.example.hud

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.util.Size as AndroidSize
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.EyeOverlay
import com.example.camera.FaceEyeAnalyzer
import com.example.camera.MediaPipeHandAnalyzer
import com.example.eye.EyeSample
import com.example.eye.EyeTrackingStatus
import com.example.gesture.DashboardPage
import com.example.gesture.HandObservation
import com.example.gesture.HandPose
import com.example.gesture.SwipeDirection
import com.example.mental.CognitiveLevel
import com.example.mental.EmergencyDrill
import com.example.radiation.RadiationStatus
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfBlue
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.IosSfRed
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SpecularWhite
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun SpatialHudScreen(
  uiState: HudUiState,
  onRequestPermission: () -> Unit,
  onHandsDetected: (List<HandObservation>, Long) -> Unit,
  onInvisibleCornerTap: () -> Unit,
  modifier: Modifier = Modifier,
  onRequestLocationPermission: () -> Unit = {},
  onEyeSample: (EyeSample) -> Unit = {},
  onEyeTrackingStatus: (EyeTrackingStatus) -> Unit = {},
  enableLensEffect: Boolean = true,
) {
  val blurRadius by
    animateFloatAsState(
      targetValue = if (uiState.hasCameraPermission && uiState.isDashboardOpen) 34f else 0f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
      label = "backdrop_blur_radius",
    )
  val telemetryAlpha by animateFloatAsState(if (uiState.isDashboardOpen) 0.30f else 1f, tween(260), label = "telemetry_alpha")
  val density = LocalDensity.current.density

  // Eye preview frames live only in the UI layer (never in ViewModel state).
  var eyePreview by remember { mutableStateOf<ImageBitmap?>(null) }
  var eyeOverlay by remember { mutableStateOf<EyeOverlay?>(null) }
  val previewWanted =
    uiState.isDashboardOpen && uiState.currentPage == DashboardPage.ISOLATION && uiState.eyePreviewEnabled

  Box(modifier = modifier.fillMaxSize().background(ObsidianBlack)) {
    if (uiState.hasCameraPermission) {
      DualCameraPassThrough(
        blurRadius = blurRadius,
        previewWanted = previewWanted,
        onHandsDetected = onHandsDetected,
        onEyeSample = onEyeSample,
        onEyeTrackingStatus = onEyeTrackingStatus,
        onEyePreview = { bmp, overlay ->
          eyePreview = bmp.asImageBitmap()
          eyeOverlay = overlay
        },
        modifier = Modifier.fillMaxSize(),
      )
      LensVignette(Modifier.fillMaxSize())

      // Consume every touch to keep the wearer locked in.
      Box(
        modifier =
          Modifier.fillMaxSize().testTag("hud_touch_shield").pointerInput(Unit) {
            awaitEachGesture {
              while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
              }
            }
          }
      )

      Box(modifier = Modifier.fillMaxSize().displayCutoutPadding()) {
        VitalsTelemetryWidget(
          heartRateBpm = uiState.heartRateBpm,
          systolicBp = uiState.systolicBp,
          diastolicBp = uiState.diastolicBp,
          spo2Percent = uiState.spo2Percent,
          coreTempCelsius = uiState.coreTempCelsius,
          hydrationPercent = uiState.hydrationPercent,
          isBiometricSensorLost = uiState.isBiometricSensorLost,
          cognitiveLoad = uiState.cognitive.index,
          cognitiveLevel = uiState.cognitive.level,
          modifier =
            Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 16.dp).graphicsLayer {
              alpha = telemetryAlpha
              rotationY = 14f
              rotationX = 4f
              cameraDistance = 14f * density
              transformOrigin = TransformOrigin(0f, 0.5f)
            },
        )

        SuitTelemetryWidget(
          suit = uiState.suit,
          doseRateUsvPerHour = uiState.radiation.doseRateUsvPerHour,
          radiationStatus = uiState.radiation.status,
          evaSeconds = uiState.evaSeconds,
          modifier =
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 16.dp).graphicsLayer {
              alpha = telemetryAlpha
              rotationY = -14f
              rotationX = 4f
              cameraDistance = 14f * density
              transformOrigin = TransformOrigin(1f, 0.5f)
            },
        )

        SpatialDashboard(
          uiState = uiState,
          eyePreview = eyePreview,
          eyeOverlay = eyeOverlay,
          onRequestLocationPermission = onRequestLocationPermission,
          enableLensEffect = enableLensEffect,
          modifier = Modifier.fillMaxSize(),
        )

        Column(
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          GestureChip(uiState)
          MicMeter(uiState)
        }
      }
    } else {
      ZeroTextPermissionAperture(onRequestPermission, Modifier.fillMaxSize())
    }

    Column(
      modifier = Modifier.align(Alignment.TopCenter).displayCutoutPadding().padding(top = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      GameGradientCompass(headingDegrees = uiState.headingDegrees, batteryPercent = uiState.batteryPercent)
      AlertBanner(uiState)
    }

    // Invisible 5-tap exit zone (upper-right corner).
    Box(
      modifier =
        Modifier.align(Alignment.TopEnd)
          .size(64.dp)
          .testTag("invisible_exit_zone")
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onInvisibleCornerTap)
    )
  }
}

// ------------------------------------------------------------------ Cameras

@Composable
private fun DualCameraPassThrough(
  blurRadius: Float,
  previewWanted: Boolean,
  onHandsDetected: (List<HandObservation>, Long) -> Unit,
  onEyeSample: (EyeSample) -> Unit,
  onEyeTrackingStatus: (EyeTrackingStatus) -> Unit,
  onEyePreview: (Bitmap, EyeOverlay?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val handExecutor = remember { Executors.newSingleThreadExecutor() }
  val faceExecutor = remember { Executors.newSingleThreadExecutor() }
  val latestHands by rememberUpdatedState(onHandsDetected)
  val latestEye by rememberUpdatedState(onEyeSample)
  val latestStatus by rememberUpdatedState(onEyeTrackingStatus)
  val latestPreview by rememberUpdatedState(onEyePreview)
  val handAnalyzer = remember(context) { MediaPipeHandAnalyzer(context) { h, t -> latestHands(h, t) } }
  val faceAnalyzer =
    remember(context) {
      FaceEyeAnalyzer(
        context,
        onEyeSample = { latestEye(it) },
        onPreviewFrame = { bmp, overlay -> ContextCompat.getMainExecutor(context).execute { latestPreview(bmp, overlay) } },
      )
    }
  LaunchedEffect(previewWanted) { faceAnalyzer.previewEnabled = previewWanted }

  val previewView =
    remember(context) {
      PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
      }
    }

  DisposableEffect(lifecycleOwner, previewView, handAnalyzer, faceAnalyzer) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
      {
        try {
          val provider = providerFuture.get()
          val analysisResolution =
            ResolutionSelector.Builder()
              .setResolutionStrategy(ResolutionStrategy(AndroidSize(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
              .build()
          val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
          val handAnalysis =
            ImageAnalysis.Builder()
              .setResolutionSelector(analysisResolution)
              .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .build()
              .also { it.setAnalyzer(handExecutor, handAnalyzer) }

          provider.unbindAll()
          val concurrentOk =
            provider.availableConcurrentCameraInfos.any { pair ->
              pair.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT } &&
                pair.any { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            }
          var eyeBound = false
          if (concurrentOk && faceAnalyzer.isAvailable) {
            try {
              val faceAnalysis =
                ImageAnalysis.Builder()
                  .setResolutionSelector(analysisResolution)
                  .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                  .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                  .build()
                  .also { it.setAnalyzer(faceExecutor, faceAnalyzer) }
              val back = UseCaseGroup.Builder().addUseCase(preview).addUseCase(handAnalysis).build()
              val front = UseCaseGroup.Builder().addUseCase(faceAnalysis).build()
              provider.bindToLifecycle(
                listOf(
                  ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, back, lifecycleOwner),
                  ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, front, lifecycleOwner),
                )
              )
              eyeBound = true
            } catch (t: Throwable) {
              Log.w(TAG, "Concurrent front+rear binding failed; eye tracking disabled", t)
              provider.unbindAll()
            }
          }
          if (!eyeBound) {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, handAnalysis)
          }
          latestStatus(
            when {
              eyeBound -> EyeTrackingStatus.STARTING
              !faceAnalyzer.isAvailable -> EyeTrackingStatus.MODEL_ERROR
              else -> EyeTrackingStatus.UNSUPPORTED_DEVICE
            }
          )
        } catch (t: Throwable) {
          Log.w(TAG, "CameraX binding failed", t)
        }
      },
      ContextCompat.getMainExecutor(context),
    )
    onDispose {
      try {
        if (providerFuture.isDone) providerFuture.get().unbindAll()
      } catch (_: Throwable) {}
      handAnalyzer.close()
      faceAnalyzer.close()
      handExecutor.shutdown()
      faceExecutor.shutdown()
    }
  }

  AndroidView(
    factory = { previewView },
    update = { view ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.setRenderEffect(
          if (blurRadius > 1.5f) RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP) else null
        )
      }
    },
    modifier = modifier.testTag("camera_preview"),
  )
}

/** Dark curved edge falloff that sells the visor / fish-eye look over the passthrough. */
@Composable
private fun LensVignette(modifier: Modifier) {
  Canvas(modifier) {
    drawRect(
      Brush.radialGradient(
        0.55f to Color.Transparent,
        0.85f to ObsidianBlack.copy(alpha = 0.28f),
        1.0f to ObsidianBlack.copy(alpha = 0.62f),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = maxOf(size.width, size.height) * 0.62f,
      )
    )
  }
}

// ------------------------------------------------------------------ Spatial tiles

@Composable
private fun SpatialDashboard(
  uiState: HudUiState,
  eyePreview: ImageBitmap?,
  eyeOverlay: EyeOverlay?,
  onRequestLocationPermission: () -> Unit,
  enableLensEffect: Boolean,
  modifier: Modifier = Modifier,
) {
  val open = uiState.isDashboardOpen
  val reveal by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 320f), label = "dash_reveal")
  if (reveal <= 0.01f) return

  // Re-anchoring (swipes / recenter / voice) glides instead of jumping.
  val anchorYaw by animateFloatAsState(uiState.anchorYawDegrees, tween(380, easing = FastOutSlowInEasing), label = "anchor_yaw")
  val anchorPitch by animateFloatAsState(uiState.anchorPitchDegrees, tween(380, easing = FastOutSlowInEasing), label = "anchor_pitch")
  val cue = remember { Animatable(0f) }
  LaunchedEffect(uiState.swipeCueId) {
    if (uiState.swipeCueId > 0L) {
      cue.snapTo(1f)
      cue.animateTo(0f, tween(420))
    }
  }

  BoxWithConstraints(modifier.background(ObsidianBlack.copy(alpha = 0.12f * reveal)).fishEyeLens(enabled = enableLensEffect)) {
    val density = LocalDensity.current
    val wPx = constraints.maxWidth.toFloat()
    val hPx = constraints.maxHeight.toFloat()
    val pxPerDeg = wPx / SpatialLayout.VIEW_HFOV_DEG
    // Keep tiles clear of the compass + alert capsule above and the control chips below.
    val topReserve = 92.dp
    val bottomReserve = 30.dp
    val usableH = (maxHeight - topReserve - bottomReserve).coerceAtLeast(160.dp)
    val centerYPx = with(density) { (topReserve + usableH / 2).toPx() }
    fun tileSize(page: DashboardPage) =
      if (page == DashboardPage.ISOLATION || page == DashboardPage.ENVIRONMENT) minOf(maxWidth * 0.66f, 500.dp) to minOf(usableH, 280.dp)
      else minOf(maxWidth * 0.58f, 430.dp) to minOf(usableH, 270.dp)

    val pages = DashboardPage.ring + DashboardPage.DRILLS
    // Draw farthest first so the focused tile sits on top.
    val ordered =
      pages
        .map { page ->
          val yaw = SpatialLayout.tileYaw(page, anchorYaw, uiState.headingDegrees)
          val pitch = SpatialLayout.tilePitch(page, anchorPitch)
          page to SpatialLayout.project(yaw, pitch, uiState.headingDegrees, uiState.pitchDegrees)
        }
        .sortedByDescending { (_, p) -> kotlin.math.abs(p.xDeg) + kotlin.math.abs(p.yDeg) }

    for ((page, proj) in ordered) {
      if (!proj.visible) continue
      val focused = page == uiState.currentPage
      val (tileW, tileH) = tileSize(page)
      val tileWPx = with(density) { tileW.toPx() }
      val tileHPx = with(density) { tileH.toPx() }
      val accent =
        when {
          page == DashboardPage.ISOLATION && uiState.cognitive.level != CognitiveLevel.NOMINAL -> levelColor(uiState.cognitive.level)
          page == DashboardPage.RADIATION && uiState.radiation.status == RadiationStatus.STORM -> Ios.Red
          focused -> Color.White.copy(alpha = 0.32f)
          else -> null
        }
      GlassPanel(
        accent = accent,
        modifier =
          Modifier.offset {
              IntOffset(
                (wPx / 2f + proj.xDeg * pxPerDeg - tileWPx / 2f).roundToInt(),
                (centerYPx - proj.yDeg * pxPerDeg - tileHPx / 2f).roundToInt(),
              )
            }
            .size(tileW, tileH)
            .testTag("spatial_tile_${page.name.lowercase()}")
            .graphicsLayer {
              val s = proj.scale * (0.86f + 0.14f * reveal)
              scaleX = s
              scaleY = s
              rotationY = proj.rotationY
              rotationX = proj.rotationX
              alpha = proj.alpha * reveal * (if (focused) 1f else 0.82f)
              cameraDistance = 16f * density.density
            },
      ) {
        when (page) {
          DashboardPage.RADIATION -> RadiationTile(uiState)
          DashboardPage.ISOLATION -> IsolationTile(uiState, eyePreview, eyeOverlay)
          DashboardPage.DISTANCE -> DistanceTile(uiState)
          DashboardPage.GRAVITY -> GravityTile(uiState)
          DashboardPage.ENVIRONMENT -> EnvironmentTile(uiState, onRequestLocationPermission)
          DashboardPage.DRILLS -> DrillsTile(uiState.activeDrill)
        }
      }
    }

    RingIndicator(uiState.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).testTag("dashboard_page_indicator"))

    if (cue.value > 0.01f && uiState.lastSwipeDirection != null) {
      val left = uiState.lastSwipeDirection == SwipeDirection.PREVIOUS
      Box(
        Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
          .width(40.dp)
          .fillMaxHeight()
          .testTag("swipe_edge_cue")
          .background(
            Brush.horizontalGradient(
              if (left) listOf(IosSfBlue.copy(alpha = 0.36f * cue.value), Color.Transparent)
              else listOf(Color.Transparent, IosSfBlue.copy(alpha = 0.36f * cue.value))
            )
          )
      )
    }
  }
}

/** MENTAL -> VITALS -> RADIATION -> MAP (loop), with DRILLS shown as the "look down" dot. */
@Composable
private fun RingIndicator(current: DashboardPage, modifier: Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    DashboardPage.ring.forEach { page ->
      val selected = page == current
      val w by animateFloatAsState(if (selected) 20f else 7f, tween(200), label = "ring_dot_${page.name}")
      Box(Modifier.size(w.dp, 7.dp).clip(CircleShape).background(if (selected) hazardColor(page) else Color.White.copy(alpha = 0.3f)))
    }
    Text("↺", style = hudText(9.sp, Ios.Secondary))
    Box(Modifier.size(7.dp).clip(CircleShape).background(if (current == DashboardPage.DRILLS) Ios.Red else Color.White.copy(alpha = 0.18f)))
  }
}

// ------------------------------------------------------------------ Alerts & controls

@Composable
private fun AlertBanner(uiState: HudUiState) {
  val cog = uiState.cognitive
  val rad = uiState.radiation
  val (text, color) =
    when {
      cog.level == CognitiveLevel.EMERGENCY ->
        "Emergency · load ${cog.index} · pulse ${if (uiState.isBiometricSensorLost) "--" else uiState.heartRateBpm} · " +
          (if (uiState.alertAcknowledged) "acknowledged" else "thumbs up to acknowledge") to Ios.Red
      cog.level == CognitiveLevel.WARNING -> "Warning · load ${cog.index} · ${cog.topDriver.lowercase()}" to levelColor(cog.level)
      rad.status == RadiationStatus.STORM -> "Solar particle event · seek shelter" to Ios.Red
      cog.level == CognitiveLevel.CAUTION -> "Caution · load ${cog.index} · ${cog.topDriver.lowercase()}" to Ios.Yellow
      uiState.activeDrill != EmergencyDrill.NONE -> "Drill ${uiState.activeDrill.code} · ${uiState.activeDrill.title}" to Ios.Orange
      else -> null to IosSfGreen
    }
  if (text == null) return
  val pulse by
    rememberInfiniteTransition(label = "alert_pulse")
      .animateFloat(0.55f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "alert_pulse_a")
  val emergency = cog.level == CognitiveLevel.EMERGENCY && !uiState.alertAcknowledged
  val shape = RoundedCornerShape(50)
  Row(
    Modifier.padding(top = 4.dp)
      .testTag("alert_banner")
      .semantics(mergeDescendants = true) {}
      .clip(shape)
      .background(Color.Black)
      .border(if (emergency) 1.5.dp else 0.5.dp, color.copy(alpha = if (emergency) pulse else 0.5f), shape)
      .padding(horizontal = 14.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Dot(color.copy(alpha = if (emergency) pulse else 1f), 8.dp)
    Spacer(Modifier.width(8.dp))
    Text(text, style = hudText(11.sp, Color.White), maxLines = 1)
  }
}

@Composable
private fun GestureChip(uiState: HudUiState) {
  val g = uiState.gesture
  val flash = remember { Animatable(0f) }
  LaunchedEffect(g.lastActionId) {
    if (g.lastActionId > 0) {
      flash.snapTo(1f)
      flash.animateTo(0f, tween(900))
    }
  }
  val showPending = g.pendingAction != null && g.holdProgress > 0.02f
  val showFlash = flash.value > 0.02f && g.lastAction != null
  val showPose = g.activePose != HandPose.NONE
  if (!showPending && !showFlash && !showPose) return
  val shape = RoundedCornerShape(50)
  Row(
    Modifier.testTag("gesture_chip")
      .clip(shape)
      .background(Color.Black.copy(alpha = 0.85f))
      .padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
      Canvas(Modifier.fillMaxSize()) {
        val sw = 2.dp.toPx()
        val arc = Size(size.width - sw, size.height - sw)
        drawArc(SpecularWhite.copy(alpha = 0.2f), 0f, 360f, false, Offset(sw / 2, sw / 2), arc, style = Stroke(sw))
        val p = if (showFlash && !showPending) 1f else g.holdProgress
        drawArc(if (showFlash && !showPending) IosSfGreen else IosSfBlue, -90f, 360f * p, false, Offset(sw / 2, sw / 2), arc, style = Stroke(sw, cap = StrokeCap.Round))
      }
    }
    Spacer(Modifier.width(6.dp))
    val label =
      when {
        showPending -> "${g.activePose.label} → ${g.pendingAction?.label}"
        showFlash -> "✓ ${g.lastAction?.label}"
        else -> g.activePose.label
      }
    Text(label, style = hudText(9.sp, if (showFlash && !showPending) IosSfGreen else IosLabelPrimary))
  }
}

@Composable
private fun MicMeter(uiState: HudUiState) {
  val mic = uiState.mic
  val norm = { db: Float -> ((db + 80f) / 70f).coerceIn(0f, 1f) }
  Row(Modifier.testTag("mic_meter"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    Text(if (mic.calibrating) "Calibrating mic" else "Mic · S${mic.sensitivity}", style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium))
    Box(Modifier.width(70.dp).height(4.dp).clip(CircleShape).background(SpecularWhite.copy(alpha = 0.14f))) {
      Box(
        Modifier.fillMaxWidth(norm(mic.levelDb).coerceAtLeast(0.02f))
          .height(4.dp)
          .clip(CircleShape)
          .background(if (mic.speaking) IosSfGreen else SpecularWhite.copy(alpha = 0.55f))
      )
      Box(Modifier.offset(x = 70.dp * norm(mic.gateDb)).width(1.5.dp).height(4.dp).background(IosSfAmber))
    }
    if (mic.lastHeard.isNotEmpty()) Text("\"${mic.lastHeard}\"", style = hudText(7.sp, IosLabelSecondary, FontWeight.Medium), maxLines = 1)
  }
}

@Composable
private fun ZeroTextPermissionAperture(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .background(ObsidianBlack)
        .testTag("permission_trigger_area")
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRequestPermission),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(64.dp)) {
      val outerStroke = 2.dp.toPx()
      drawCircle(SpecularWhite.copy(alpha = 0.34f), size.minDimension / 2f - outerStroke, style = Stroke(outerStroke))
      drawCircle(SpecularWhite.copy(alpha = 0.22f), size.minDimension * 0.22f)
    }
  }
}

private const val TAG = "SpatialHudScreen"
