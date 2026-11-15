package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alert.AlertAnnouncer
import com.example.hud.HudEvent
import com.example.hud.HudViewModel
import com.example.hud.SpatialHudScreen
import com.example.crew.CrewLink
import com.example.nasa.DonkiClient
import com.example.sensor.BatteryStatusMonitor
import com.example.sensor.BodySensorMonitor
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
  private lateinit var alertAnnouncer: AlertAnnouncer
  private lateinit var bodySensorMonitor: BodySensorMonitor
  private lateinit var crewLink: CrewLink

  private var isPermissionPromptPending = false
  private var hasAttemptedLocationPrompt = false

  private val permissionsLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
      isPermissionPromptPending = false
      val cameraGranted =
        results[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
      val audioGranted =
        results[Manifest.permission.RECORD_AUDIO] == true ||
          hasPermission(Manifest.permission.RECORD_AUDIO)
      val locationGranted =
        results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
          results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
          hasLocationPermission()

      viewModel.onPermissionResult(cameraGranted)
      viewModel.onLocationPermissionResult(locationGranted)
      if (cameraGranted) {
        tryStartLockTask()
      }
      if (audioGranted) {
        voiceCommandRecognizer.start()
      }
      if (locationGranted) {
        gpsDistanceMonitor.start()
      }
      if (hasCrewPermissions()) crewLink.start()
      hideSystemBars()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    configureImmersiveWindow()
    disableBackNavigation()

    headingSensorMonitor = HeadingSensorMonitor(this, viewModel::onHeadingChanged, viewModel::onPitchChanged)
    batteryStatusMonitor = BatteryStatusMonitor(this, viewModel::onBatteryPercentChanged)
    gpsDistanceMonitor =
      GpsDistanceMonitor(
        context = this,
        onLocationSample = viewModel::onLocationSample,
        onFixStatusChanged = viewModel::onGpsFixStatusChanged,
        onVectorDelta = viewModel::onGpsVectorDelta,
        onAbsoluteFix = viewModel::onAbsoluteFix,
      )
    voiceCommandRecognizer = VoiceCommandRecognizer(this, viewModel::onVoiceCommand, viewModel::onMicStatus)
    alertAnnouncer = AlertAnnouncer(this) { speaking -> voiceCommandRecognizer.setMuted(speaking) }
    bodySensorMonitor = BodySensorMonitor(this, viewModel::onStep, viewModel::onLightLux)
    startSpaceWeatherSync()
    val prefs = getSharedPreferences("crew", MODE_PRIVATE)
    val crewId =
      prefs.getString("id", null)
        ?: java.util.UUID.randomUUID().toString().take(4).uppercase().also { prefs.edit().putString("id", it).apply() }
    val crewName = "Crew-$crewId"
    viewModel.setCrewIdentity(crewId, crewName)
    crewLink = CrewLink(this, crewId, crewName, onStatus = { viewModel.onCrewPacket(it) }, onLinkChanged = viewModel::onCrewLinkChanged)
    startCrewBroadcast()

    val hasCamera = hasPermission(Manifest.permission.CAMERA)
    val hasLocation = hasLocationPermission()
    val missingPermissions = requiredMissingPermissions()

    viewModel.onPermissionResult(hasCamera)
    viewModel.onLocationPermissionResult(hasLocation)

    if (missingPermissions.isEmpty()) {
      tryStartLockTask()
    } else {
      // Never start LockTask before the system permission dialog completes, or it can suppress
      // Location and Audio prompts.
      if (missingPermissions.any {
          it == Manifest.permission.ACCESS_FINE_LOCATION ||
            it == Manifest.permission.ACCESS_COARSE_LOCATION
        }
      ) {
        hasAttemptedLocationPrompt = true
      }
      isPermissionPromptPending = true
      permissionsLauncher.launch(missingPermissions.toTypedArray())
    }

    setContent {
      SpatialHudTheme {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
          viewModel.events.collect { event ->
            when (event) {
              HudEvent.ExitApp -> exitLockedHud()
              is HudEvent.Speak -> alertAnnouncer.speak(event.text)
              HudEvent.StopSpeaking -> alertAnnouncer.stop()
              HudEvent.RecalibrateMic -> voiceCommandRecognizer.recalibrate()
            }
          }
        }

        SpatialHudScreen(
          uiState = uiState,
          onRequestPermission = {
            val missing = requiredMissingPermissions()
            if (missing.isNotEmpty()) {
              isPermissionPromptPending = true
              permissionsLauncher.launch(missing.toTypedArray())
            }
          },
          onRequestLocationPermission = { requestLocationOrOpenSettings() },
          onHandsDetected = viewModel::onHandsDetected,
          onInvisibleCornerTap = { viewModel.onInvisibleExitZoneTap() },
          onEyeSample = viewModel::onEyeSample,
          onEyeTrackingStatus = viewModel::onEyeTrackingStatus,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (!isPermissionPromptPending) {
      hideSystemBars()
    }
    headingSensorMonitor.start()
    batteryStatusMonitor.start()
    bodySensorMonitor.start()

    val hasCamera = hasPermission(Manifest.permission.CAMERA)
    val hasAudio = hasPermission(Manifest.permission.RECORD_AUDIO)
    val hasLocation = hasLocationPermission()
    viewModel.onPermissionResult(hasCamera)
    viewModel.onLocationPermissionResult(hasLocation)
    if (hasAudio) {
      voiceCommandRecognizer.start()
    }
    if (hasLocation) {
      gpsDistanceMonitor.start()
    }
    if (hasCrewPermissions()) crewLink.start()
  }

  override fun onPause() {
    headingSensorMonitor.stop()
    batteryStatusMonitor.stop()
    bodySensorMonitor.stop()
    gpsDistanceMonitor.stop()
    voiceCommandRecognizer.stop()
    crewLink.stop()
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && !isPermissionPromptPending) {
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

  private fun requestLocationOrOpenSettings() {
    if (hasLocationPermission()) {
      viewModel.onLocationPermissionResult(true)
      gpsDistanceMonitor.start()
      return
    }
    val canPrompt =
      !hasAttemptedLocationPrompt ||
        ActivityCompat.shouldShowRequestPermissionRationale(
          this,
          Manifest.permission.ACCESS_FINE_LOCATION,
        ) ||
        ActivityCompat.shouldShowRequestPermissionRationale(
          this,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )

    if (canPrompt) {
      hasAttemptedLocationPrompt = true
      isPermissionPromptPending = true
      try {
        stopLockTask()
      } catch (_: Throwable) {}
      permissionsLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      )
    } else {
      try {
        stopLockTask()
      } catch (_: Throwable) {}
      startActivity(
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.fromParts("package", packageName, null),
        )
      )
    }
  }

  private fun requiredMissingPermissions(): List<String> {
    return buildList {
      if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
      if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
      if (!hasLocationPermission()) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
      }
      crewPermissions().filterNot(::hasPermission).forEach(::add)
    }
  }

  /** Nearby Connections needs Bluetooth (API 31+) and nearby Wi-Fi (API 33+) runtime grants. */
  private fun crewPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      add(Manifest.permission.BLUETOOTH_SCAN)
      add(Manifest.permission.BLUETOOTH_ADVERTISE)
      add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
  }

  private fun hasCrewPermissions(): Boolean = hasLocationPermission() && crewPermissions().all(::hasPermission)

  /** Shares this wearer's status with the crew every 2 s while the link is up. */
  private fun startCrewBroadcast() {
    lifecycleScope.launch {
      while (isActive) {
        crewLink.broadcast(viewModel.crewOutbound())
        delay(2_000L)
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

  /** Pulls NASA DONKI flares / SEPs every 30 min while connectivity exists (DEMO_KEY: 30 req/h). */
  private fun startSpaceWeatherSync() {
    val client = DonkiClient(BuildConfig.NASA_API_KEY.ifBlank { "DEMO_KEY" })
    lifecycleScope.launch {
      while (isActive) {
        try {
          val now = System.currentTimeMillis()
          val events = withContext(Dispatchers.IO) { client.fetch(now) }
          viewModel.onSpaceWeatherEvents(events, now)
        } catch (_: Throwable) {
          // Offline (expected on an EVA): keep last known state.
        }
        delay(30 * 60_000L)
      }
    }
  }

  override fun onDestroy() {
    alertAnnouncer.shutdown()
    super.onDestroy()
  }

  private fun exitLockedHud() {
    alertAnnouncer.stop()
    voiceCommandRecognizer.stop()
    gpsDistanceMonitor.stop()
    try {
      stopLockTask()
    } catch (_: Throwable) {}
    finishAndRemoveTask()
  }
}
