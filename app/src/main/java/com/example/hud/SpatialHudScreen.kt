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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.MediaPipeHandAnalyzer
import com.example.gesture.DashboardMode
import com.example.gesture.DashboardPage
import com.example.gesture.HandLandmarkPoint
import com.example.gesture.SwipeDirection
import com.example.mental.MentalBand
import com.example.mental.MentalState
import com.example.ui.theme.IosGlassHairline
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosSfAmber
import com.example.ui.theme.IosSfBlue
import com.example.ui.theme.IosSfGreen
import com.example.ui.theme.IosSfIndigo
import com.example.ui.theme.IosSfPink
import com.example.ui.theme.IosSfRed
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
  onRequestLocationPermission: () -> Unit = {},
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
          visible = uiState.dashboardMode != DashboardMode.PERMANENT && uiState.palmHoldProgress > 0.015f,
          modifier = Modifier.align(Alignment.Center),
        )

        VitalsTelemetryWidget(
          heartRateBpm = uiState.heartRateBpm,
          systolicBp = uiState.systolicBp,
          diastolicBp = uiState.diastolicBp,
          spo2Percent = uiState.spo2Percent,
          coreTempCelsius = uiState.coreTempCelsius,
          hydrationPercent = uiState.hydrationPercent,
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
          suitPressurePsi = uiState.suitPressurePsi,
          oxygenPercent = uiState.oxygenPercent,
          oxygenHours = uiState.oxygenHours,
          suitPowerPercent = uiState.suitPowerPercent,
          suitPowerHours = uiState.suitPowerHours,
          ppCo2MmHg = uiState.ppCo2MmHg,
          radiationMSv = uiState.radiationMSv,
          windSpeedMps = uiState.windSpeedMps,
          evaSeconds = uiState.evaSeconds,
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

        VisionTriDashboard(
          uiState = uiState,
          onRequestLocationPermission = onRequestLocationPermission,
          modifier = Modifier.fillMaxSize(),
        )

        TwoFistsDualRingCue(
          visible = uiState.dashboardMode == DashboardMode.PERMANENT && uiState.isTwoFistsReady,
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
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
private fun VisionTriDashboard(
  uiState: HudUiState,
  onRequestLocationPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val transition = updateTransition(targetState = uiState.isDashboardOpen, label = "ios_dashboard_transition")

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
    val pages = DashboardPage.entries
    val pagerState =
      rememberPagerState(
        initialPage = uiState.currentPage.ordinal,
        pageCount = { pages.size },
      )
    val swipeEdgeCueAlpha = remember { Animatable(0f) }

    LaunchedEffect(uiState.currentPage) {
      if (pagerState.currentPage != uiState.currentPage.ordinal) {
        pagerState.animateScrollToPage(
          page = uiState.currentPage.ordinal,
          animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        )
      }
    }

    LaunchedEffect(uiState.swipeCueId) {
      if (uiState.swipeCueId > 0L && uiState.lastSwipeDirection != null) {
        swipeEdgeCueAlpha.snapTo(1f)
        swipeEdgeCueAlpha.animateTo(
          targetValue = 0f,
          animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        )
      }
    }

    BoxWithConstraints(
      modifier =
        modifier
          .background(ObsidianBlack.copy(alpha = scrimAlpha))
          .padding(top = 58.dp, bottom = 38.dp, start = 20.dp, end = 20.dp),
      contentAlignment = Alignment.Center,
    ) {
      val panelWidth = min(maxWidth * 0.80f, 700.dp)
      val panelHeight = min(maxHeight * 0.90f, 340.dp)

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
          Modifier.offset(y = panelOffsetY).graphicsLayer {
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
            Modifier.widthIn(min = 280.dp)
              .size(width = panelWidth, height = panelHeight)
              .testTag("vision_dashboard_panel")
              .clip(windowShape)
              .background(SpecularWhite.copy(alpha = 0.16f))
              .background(glassSheenBrush)
              .border(width = 1.dp, brush = specularRimBrush, shape = windowShape),
        ) {
          HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().padding(bottom = 26.dp),
          ) { pageIndex ->
            when (pages[pageIndex]) {
              DashboardPage.VITALS -> InferredVitalsDashboardPage(uiState = uiState)
              DashboardPage.MAP ->
                MarsMapMeterDashboardPage(
                  uiState = uiState,
                  onRequestLocationPermission = onRequestLocationPermission,
                )
              DashboardPage.SUIT_STATUS -> InferredSuitStatusDashboardPage(uiState = uiState)
              DashboardPage.COGNITIVE -> CognitiveDashboardPage(mentalState = uiState.mentalState)
            }
          }

          if (swipeEdgeCueAlpha.value > 0.01f && uiState.lastSwipeDirection != null) {
            val isLeftEdge = uiState.lastSwipeDirection == SwipeDirection.PREVIOUS
            val cueAlpha = swipeEdgeCueAlpha.value
            val glowColors =
              if (isLeftEdge) {
                listOf(IosSfBlue.copy(alpha = 0.34f * cueAlpha), Color.Transparent)
              } else {
                listOf(Color.Transparent, IosSfBlue.copy(alpha = 0.34f * cueAlpha))
              }
            Box(
              modifier =
                Modifier.align(if (isLeftEdge) Alignment.CenterStart else Alignment.CenterEnd)
                  .width(34.dp)
                  .fillMaxHeight()
                  .testTag("swipe_edge_cue")
                  .background(Brush.horizontalGradient(glowColors))
            ) {
              Box(
                modifier =
                  Modifier.align(if (isLeftEdge) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(2.5.dp)
                    .fillMaxHeight(0.54f)
                    .clip(CircleShape)
                    .background(SpecularWhite.copy(alpha = 0.72f * cueAlpha))
              )
            }
          }

          DashboardPageDots(
            pageCount = pages.size,
            currentIndex = uiState.currentPage.ordinal,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

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
private fun InferredVitalsDashboardPage(uiState: HudUiState) {
  val (overallLabel, overallColor) =
    when {
      uiState.heartRateBpm >= 135 || uiState.systolicBp >= 140 -> "ELEVATED" to IosSfAmber
      uiState.heartRateBpm >= 105 || uiState.speedMps >= 1.0f -> "ACTIVE" to IosSfBlue
      else -> "NOMINAL" to IosSfGreen
    }
  val exertionZone =
    when {
      uiState.heartRateBpm >= 135 -> "PEAK"
      uiState.heartRateBpm >= 105 -> "AEROBIC"
      else -> "RESTING"
    }
  val bpClassification = if (uiState.systolicBp >= 135) "EXERTION" else "NORMOTENSIVE"
  val spo2Classification = if (uiState.spo2Percent >= 95) "OPTIMAL" else "LOW"
  val hydrationClassification = if (uiState.hydrationPercent >= 60) "HYDRATED" else "LOW"
  val coreText = String.format(Locale.US, "%.1f\u00B0C", uiState.coreTempCelsius)
  val hydrationValue = String.format(Locale.US, "%d%% · %.1fL", uiState.hydrationPercent, uiState.hydrationLiters)

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 20.dp, vertical = 14.dp)
        .testTag("dashboard_page_vitals"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "VITALS",
        style =
          TextStyle(
            color = IosLabelPrimary,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = 0.6.sp,
          ),
      )
      StatusBadgePill(
        text = overallLabel,
        color = overallColor,
        tag = "vitals_status_pill",
      )
    }

    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(IosGlassHairline))

    Row(
      modifier = Modifier.fillMaxWidth().weight(1f),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      DashboardMetricCard(
        label = "HEART RATE",
        value = "${uiState.heartRateBpm} BPM",
        inference = exertionZone,
        accentColor = IosSfPink,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "BLOOD PRESSURE",
        value = "${uiState.systolicBp}/${uiState.diastolicBp} mmHg",
        inference = bpClassification,
        accentColor = if (uiState.systolicBp >= 135) IosSfAmber else IosLabelPrimary,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth().weight(1f),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      DashboardMetricCard(
        label = "SpO\u2082",
        value = "${uiState.spo2Percent}%",
        inference = spo2Classification,
        accentColor = IosSfBlue,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "CORE TEMP",
        value = coreText,
        inference = "THERMAL OK",
        accentColor = IosSfGreen,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "H\u2082O",
        value = hydrationValue,
        inference = hydrationClassification,
        accentColor = IosSfIndigo,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
    }
  }
}

@Composable
private fun InferredSuitStatusDashboardPage(uiState: HudUiState) {
  val pressureOk = uiState.suitPressurePsi in 3.8f..4.6f
  val isLowPower = uiState.isSuitLowPower
  val radElevated = uiState.radiationMSv >= 0.50f
  val co2Elevated = uiState.ppCo2MmHg >= 3.0f

  val (overallLabel, overallColor) =
    when {
      !pressureOk -> "PRESSURE WARN" to IosSfRed
      isLowPower -> "LOW POWER" to IosSfRed
      radElevated -> "RAD ADVISORY" to IosSfAmber
      co2Elevated -> "CO\u2082 HIGH" to IosSfAmber
      else -> "SEALED · NOMINAL" to IosSfGreen
    }

  val evaMin = uiState.evaSeconds / 60
  val evaSec = uiState.evaSeconds % 60
  val evaFormatted = "${evaMin.toString().padStart(2, '0')}:${evaSec.toString().padStart(2, '0')}"
  val pwrHours = if (isLowPower) 0.8f else uiState.suitPowerHours
  val windText = String.format(Locale.US, "%.1f m/s", uiState.windSpeedMps)
  val radText = String.format(Locale.US, "%.2f mSv", uiState.radiationMSv)
  val pressureText = String.format(Locale.US, "%.1f psi", uiState.suitPressurePsi)
  val o2Text = String.format(Locale.US, "%d%% · %.1fh", uiState.oxygenPercent, uiState.oxygenHours)
  val pwrText = String.format(Locale.US, "%d%% · %.1fh", uiState.suitPowerPercent, pwrHours)
  val co2Text = String.format(Locale.US, "%.1f mmHg", uiState.ppCo2MmHg)

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 20.dp, vertical = 14.dp)
        .testTag("dashboard_page_suit"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "SUIT STATUS",
        style =
          TextStyle(
            color = IosLabelPrimary,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = 0.6.sp,
          ),
      )
      StatusBadgePill(
        text = overallLabel,
        color = overallColor,
        tag = "suit_status_pill",
      )
    }

    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(IosGlassHairline))

    Row(
      modifier = Modifier.fillMaxWidth().weight(1f),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      DashboardMetricCard(
        label = "SUIT PRESSURE",
        value = pressureText,
        inference = if (pressureOk) "SEAL OK" else "CHECK SEAL",
        accentColor = if (pressureOk) IosSfGreen else IosSfRed,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "O\u2082 RESERVE",
        value = o2Text,
        inference = if (uiState.oxygenPercent >= 25) "NOMINAL" else "LOW",
        accentColor = IosSfBlue,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "POWER",
        value = pwrText,
        inference = if (isLowPower) "RESERVE" else "NOMINAL",
        accentColor = if (isLowPower) IosSfRed else IosSfGreen,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth().weight(1f),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DashboardMetricCard(
        label = "RADIATION",
        value = radText,
        inference = if (radElevated) "ELEVATED" else "SAFE",
        accentColor = IosSfAmber,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "ppCO\u2082",
        value = co2Text,
        inference = if (co2Elevated) "HIGH" else "SCRUB OK",
        accentColor = if (co2Elevated) IosSfAmber else IosSfGreen,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "WIND",
        value = windText,
        inference = if (uiState.windSpeedMps < 10f) "CALM" else "GUSTS",
        accentColor = IosLabelPrimary,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      DashboardMetricCard(
        label = "EVA TIME",
        value = evaFormatted,
        inference = "ACTIVE",
        accentColor = IosLabelPrimary,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
    }
  }
}

@Composable
private fun CognitiveDashboardPage(mentalState: MentalState) {
  val (bandLabel, bandColor) =
    when (mentalState.band) {
      MentalBand.OK -> "OK" to IosSfGreen
      MentalBand.CAUTION -> "CAUTION" to IosSfAmber
      MentalBand.WARNING -> "WARNING" to IosSfRed
      MentalBand.UNKNOWN -> "CALIBRATING" to IosLabelSecondary
    }
  val scoreText = mentalState.score?.toString() ?: "--"
  val scoreColor = if (mentalState.band == MentalBand.UNKNOWN) IosLabelPrimary else bandColor
  val calibrationProgress = mentalState.calibration.coerceIn(0f, 1f)
  val calibrationPercent = (calibrationProgress * 100f).roundToInt()
  val hrText = mentalState.heartRate?.let { "${it.roundToInt()} BPM" } ?: "--"
  val cardShape = RoundedCornerShape(18.dp)

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 20.dp, vertical = 14.dp)
        .testTag("dashboard_page_cognitive"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "COGNITIVE",
        style =
          TextStyle(
            color = IosLabelPrimary,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = 0.6.sp,
          ),
      )
      StatusBadgePill(
        text = bandLabel,
        color = bandColor,
        tag = "cognitive_status_pill",
      )
    }

    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(IosGlassHairline))

    Row(
      modifier = Modifier.fillMaxWidth().weight(1f),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(
        modifier =
          Modifier.weight(1.2f)
            .fillMaxHeight()
            .testTag("cognitive_readiness_card")
            .clip(cardShape)
            .background(ObsidianBlack.copy(alpha = 0.24f))
            .border(width = 0.6.dp, color = SpecularWhite.copy(alpha = 0.18f), shape = cardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "READY",
          style =
            TextStyle(
              color = IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 9.sp,
              letterSpacing = 0.5.sp,
            ),
        )
        Text(
          text = scoreText,
          style =
            TextStyle(
              color = scoreColor,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 36.sp,
              fontFeatureSettings = "tnum",
            ),
          modifier = Modifier.testTag("cognitive_score_text"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(SpecularWhite.copy(alpha = 0.14f))
                .testTag("cognitive_calibration_bar"),
          ) {
            Box(
              modifier =
                Modifier.fillMaxWidth(calibrationProgress)
                  .fillMaxHeight()
                  .clip(CircleShape)
                  .background(if (mentalState.calibrated) bandColor else IosSfBlue),
            )
          }
          Text(
            text =
              if (mentalState.calibrated) {
                "CALIBRATED"
              } else {
                "CALIBRATION $calibrationPercent%"
              },
            style =
              TextStyle(
                color = IosLabelSecondary,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 9.sp,
                fontFeatureSettings = "tnum",
                letterSpacing = 0.4.sp,
              ),
            modifier = Modifier.testTag("cognitive_calibration_text"),
          )
        }
      }

      Column(
        modifier =
          Modifier.weight(1f)
            .fillMaxHeight()
            .testTag("cognitive_hr_card")
            .clip(cardShape)
            .background(ObsidianBlack.copy(alpha = 0.24f))
            .border(width = 0.6.dp, color = SpecularWhite.copy(alpha = 0.18f), shape = cardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "HEART RATE",
          style =
            TextStyle(
              color = IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 9.sp,
              letterSpacing = 0.5.sp,
            ),
        )
        Text(
          text = hrText,
          style =
            TextStyle(
              color = if (mentalState.heartRate != null) IosSfPink else IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 24.sp,
              fontFeatureSettings = "tnum",
            ),
          modifier = Modifier.testTag("cognitive_hr_text"),
        )
        Text(
          text = "BASELINE",
          style =
            TextStyle(
              color = IosLabelSecondary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.Medium,
              fontSize = 9.sp,
              letterSpacing = 0.4.sp,
            ),
        )
      }
    }
  }
}

@Composable
private fun MarsMapMeterDashboardPage(
  uiState: HudUiState,
  onRequestLocationPermission: () -> Unit,
) {
  val safeGoal = uiState.distanceGoalMeters.coerceAtLeast(100f)
  val safeDistance = uiState.distanceMeters.coerceAtLeast(0f)
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
  val speedFormatted = String.format(Locale.US, "%.1f m/s", uiState.speedMps.coerceAtLeast(0f))

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("dashboard_page_meter"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    MarsMolaMapCanvas(
      trackPoints = uiState.marsTrackPoints,
      headingDegrees = uiState.headingDegrees,
      hasGpsFix = uiState.hasGpsFix,
      mapZoom = uiState.mapZoom,
      mapTiltDegrees = uiState.mapTiltDegrees,
      mapPanEastDeg = uiState.mapPanEastDeg,
      mapPanNorthDeg = uiState.mapPanNorthDeg,
      isMapPinching = uiState.isMapPinching,
      modifier = Modifier.fillMaxWidth().weight(1f),
    )

    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(18.dp))
          .background(ObsidianBlack.copy(alpha = 0.28f))
          .border(width = 0.6.dp, color = SpecularWhite.copy(alpha = 0.20f), shape = RoundedCornerShape(18.dp))
          .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Canvas(modifier = Modifier.size(34.dp).testTag("dashboard_distance_ring")) {
          val strokeWidth = 3.6.dp.toPx()
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

        Column {
          Text(
            text = distanceValueText,
            style =
              TextStyle(
                color = IosLabelPrimary,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
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
                fontSize = 8.sp,
                letterSpacing = 0.5.sp,
              ),
            modifier = Modifier.testTag("dashboard_distance_unit_text"),
          )
        }
      }

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
        Text(
          text = speedFormatted,
          style =
            TextStyle(
              color = IosSfBlue,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 12.sp,
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
        Text(
          text = goalText,
          style =
            TextStyle(
              color = IosLabelPrimary,
              fontFamily = FontFamily.SansSerif,
              fontWeight = FontWeight.SemiBold,
              fontSize = 12.sp,
              fontFeatureSettings = "tnum",
            ),
        )
      }

      if (!uiState.hasLocationPermission) {
        val pillShape = RoundedCornerShape(50)
        Box(
          modifier =
            Modifier.testTag("location_permission_pill")
              .minimumInteractiveComponentSize()
              .clip(pillShape)
              .background(IosSfBlue.copy(alpha = 0.18f))
              .border(width = 0.75.dp, color = IosSfBlue.copy(alpha = 0.65f), shape = pillShape)
              .clickable(onClick = onRequestLocationPermission)
              .padding(horizontal = 12.dp, vertical = 6.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "Enable GPS",
            style =
              TextStyle(
                color = IosSfBlue,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
              ),
          )
        }
      } else {
        StatusBadgePill(
          text = if (uiState.hasGpsFix) "GPS LOCK" else "ACQUIRING",
          color = if (uiState.hasGpsFix) IosSfGreen else IosSfAmber,
          tag = "gps_status_pill",
        )
      }
    }
  }
}

@Composable
private fun DashboardMetricCard(
  label: String,
  value: String,
  inference: String,
  accentColor: Color,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(18.dp)
  Column(
    modifier =
      modifier
        .clip(shape)
        .background(ObsidianBlack.copy(alpha = 0.24f))
        .border(width = 0.6.dp, color = SpecularWhite.copy(alpha = 0.18f), shape = shape)
        .padding(horizontal = 12.dp, vertical = 9.dp),
    verticalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style =
        TextStyle(
          color = IosLabelSecondary,
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.Medium,
          fontSize = 8.5.sp,
          letterSpacing = 0.4.sp,
        ),
    )
    Text(
      text = value,
      style =
        TextStyle(
          color = accentColor,
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.SemiBold,
          fontSize = 15.sp,
          fontFeatureSettings = "tnum",
        ),
    )
    Text(
      text = inference,
      style =
        TextStyle(
          color = IosLabelSecondary,
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.Medium,
          fontSize = 8.5.sp,
          letterSpacing = 0.4.sp,
        ),
    )
  }
}

@Composable
private fun StatusBadgePill(
  text: String,
  color: Color,
  tag: String,
) {
  val pillShape = RoundedCornerShape(50)
  Box(
    modifier =
      Modifier.testTag(tag)
        .semantics(mergeDescendants = true) {}
        .clip(pillShape)
        .background(color.copy(alpha = 0.18f))
        .border(width = 0.75.dp, color = color.copy(alpha = 0.65f), shape = pillShape)
        .padding(horizontal = 10.dp, vertical = 4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style =
        TextStyle(
          color = color,
          fontFamily = FontFamily.SansSerif,
          fontWeight = FontWeight.SemiBold,
          fontSize = 10.sp,
          letterSpacing = 0.4.sp,
        ),
    )
  }
}

@Composable
private fun DashboardPageDots(
  pageCount: Int,
  currentIndex: Int,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.testTag("dashboard_page_indicator"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(pageCount) { index ->
      val selected = index == currentIndex
      val dotWidth by
        animateDpAsState(
          targetValue = if (selected) 16.dp else 6.dp,
          animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
          label = "dot_width_$index",
        )
      val dotAlpha by
        animateFloatAsState(
          targetValue = if (selected) 0.94f else 0.30f,
          animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
          label = "dot_alpha_$index",
        )
      Box(
        modifier =
          Modifier.size(width = dotWidth, height = 6.dp)
            .clip(CircleShape)
            .background(SpecularWhite.copy(alpha = dotAlpha))
      )
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
