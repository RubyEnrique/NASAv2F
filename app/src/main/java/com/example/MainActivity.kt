package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hud.HudEvent
import com.example.hud.HudViewModel
import com.example.hud.SpatialHudScreen
import com.example.sensor.BatteryStatusMonitor
import com.example.sensor.GpsDistanceMonitor
import com.example.sensor.HeadingSensorMonitor
import com.example.ui.theme.SpatialHudTheme
import com.example.voice.VoiceCommandRecognizer

class MainActivity : ComponentActivity() {

  private val viewModel: HudViewModel by viewModels()
  private lateinit var headingSensorMonitor: HeadingSensorMonitor
  private lateinit var batteryStatusMonitor: BatteryStatusMonitor
  private lateinit var gpsDistanceMonitor: GpsDistanceMonitor
  private lateinit var voiceCommandRecognizer: VoiceCommandRecognizer

  private val permissionsLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
      val cameraGranted =
        results[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
      val audioGranted =
        results[Manifest.permission.RECORD_AUDIO] == true ||
          hasPermission(Manifest.permission.RECORD_AUDIO)

      viewModel.onPermissionResult(cameraGranted)
      if (cameraGranted) {
        tryStartLockTask()
      }
      if (audioGranted) {
        voiceCommandRecognizer.start()
      }
      if (hasLocationPermission()) {
        gpsDistanceMonitor.start()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    configureImmersiveWindow()
    disableBackNavigation()

    headingSensorMonitor = HeadingSensorMonitor(this, viewModel::onHeadingChanged)
    batteryStatusMonitor = BatteryStatusMonitor(this, viewModel::onBatteryPercentChanged)
    gpsDistanceMonitor = GpsDistanceMonitor(this, viewModel::onLocationSample)
    voiceCommandRecognizer = VoiceCommandRecognizer(this, viewModel::onVoiceCommand)

    val hasCamera = hasPermission(Manifest.permission.CAMERA)
    val missingPermissions = requiredMissingPermissions()

    viewModel.onPermissionResult(hasCamera)

    if (missingPermissions.isEmpty()) {
      tryStartLockTask()
    } else {
      permissionsLauncher.launch(missingPermissions.toTypedArray())
      if (hasCamera) {
        tryStartLockTask()
      }
    }

    setContent {
      SpatialHudTheme {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
          viewModel.events.collect { event ->
            when (event) {
              HudEvent.ExitApp -> exitLockedHud()
            }
          }
        }

        SpatialHudScreen(
          uiState = uiState,
          onRequestPermission = {
            val missing = requiredMissingPermissions()
            if (missing.isNotEmpty()) {
              permissionsLauncher.launch(missing.toTypedArray())
            }
          },
          onHandsDetected = viewModel::onHandsDetected,
          onInvisibleCornerTap = { viewModel.onInvisibleExitZoneTap() },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    hideSystemBars()
    headingSensorMonitor.start()
    batteryStatusMonitor.start()

    val hasCamera = hasPermission(Manifest.permission.CAMERA)
    val hasAudio = hasPermission(Manifest.permission.RECORD_AUDIO)
    viewModel.onPermissionResult(hasCamera)
    if (hasAudio) {
      voiceCommandRecognizer.start()
    }
    if (hasLocationPermission()) {
      gpsDistanceMonitor.start()
    }
  }

  override fun onPause() {
    headingSensorMonitor.stop()
    batteryStatusMonitor.stop()
    gpsDistanceMonitor.stop()
    voiceCommandRecognizer.stop()
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      hideSystemBars()
    }
  }

  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
  }

  private fun hasLocationPermission(): Boolean {
    return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
      hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
  }

  private fun requiredMissingPermissions(): List<String> {
    return buildList {
      if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
      if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
      if (!hasLocationPermission()) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
      }
    }
  }

  private fun configureImmersiveWindow() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val params = window.attributes
      params.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      window.attributes = params
    }

    window.decorView.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
      val width = right - left
      val height = bottom - top
      if (width > 0 && height > 0) {
        ViewCompat.setSystemGestureExclusionRects(v, listOf(Rect(0, 0, width, height)))
      }
    }

    hideSystemBars()
  }

  private fun hideSystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }

  private fun disableBackNavigation() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          // Intentionally blocked: only the 5-tap invisible upper-right exit closes the HUD.
        }
      },
    )
  }

  private fun tryStartLockTask() {
    try {
      startLockTask()
    } catch (_: Throwable) {
      // Screen pinning may be unavailable or already active on some environments.
    }
  }

  private fun exitLockedHud() {
    voiceCommandRecognizer.stop()
    gpsDistanceMonitor.stop()
    try {
      stopLockTask()
    } catch (_: Throwable) {}
    finishAndRemoveTask()
  }
}
