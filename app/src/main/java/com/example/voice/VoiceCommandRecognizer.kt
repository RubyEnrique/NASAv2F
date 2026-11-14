package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

enum class VoiceCommand {
  TOGGLE_QUICK,
  OPEN_PERMANENT,
  CLOSE_PERMANENT,
  NAVIGATE_VITALS,
  NAVIGATE_MAP,
  NAVIGATE_SUIT,
  NAVIGATE_COGNITIVE,
  MAP_ZOOM_IN,
  MAP_ZOOM_OUT,
  MAP_RECENTER,
}

class VoiceCommandRecognizer(
  context: Context,
  private val onCommandDetected: (VoiceCommand) -> Unit,
) {

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val workerExecutor = Executors.newSingleThreadExecutor()
  private val stateLock = Any()
  private val sessionGeneration = AtomicLong(0L)

  @Volatile private var isRunning = false
  @Volatile private var usingAndroidFallback = false

  private var model: Model? = null
  private var recognizer: Recognizer? = null
  private var speechService: SpeechService? = null

  private var androidSpeechRecognizer: SpeechRecognizer? = null
  private var androidPreferOffline = true
  private var pendingAndroidRestartRunnable: Runnable? = null

  private var pendingQuickToggleRunnable: Runnable? = null
  private var lastCommandTimeMs = 0L
  private var lastCommand: VoiceCommand? = null

  private val voskListener =
    object : RecognitionListener {
      override fun onPartialResult(hypothesis: String?) {
        val transcript = extractTranscript(hypothesis, "partial")
        if (transcript.isNotEmpty()) {
          handleTranscript(transcript, isFinal = false)
        }
      }

      override fun onResult(hypothesis: String?) {
        val transcript = extractTranscript(hypothesis, "text")
        if (transcript.isNotEmpty()) {
          handleTranscript(transcript, isFinal = true)
        }
      }

      override fun onFinalResult(hypothesis: String?) {
        val transcript = extractTranscript(hypothesis, "text")
        if (transcript.isNotEmpty()) {
          handleTranscript(transcript, isFinal = true)
        }
      }

      override fun onError(exception: Exception?) {
        Log.w(TAG, "Vosk recognition error", exception)
        if (isRunning && !usingAndroidFallback) {
          mainHandler.postDelayed({ restartVoskListeningIfActive() }, 450L)
        }
      }

      override fun onTimeout() {
        if (isRunning && !usingAndroidFallback) {
          restartVoskListeningIfActive()
        }
      }
    }

  private val androidSpeechListener =
    object : AndroidRecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {}

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {}

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {}

      override fun onError(error: Int) {
        if (!isRunning || !usingAndroidFallback) return
        val gen = sessionGeneration.get()
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
          Log.w(TAG, "Android SpeechRecognizer missing RECORD_AUDIO permission")
          isRunning = false
          return
        }
        if (
          error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        ) {
          androidPreferOffline = false
        }
        val delayMs =
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 160L
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_AUDIO -> 650L
            else -> 450L
          }
        val recreate =
          error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_AUDIO
        scheduleAndroidSpeechRestart(gen, delayMs, recreate)
      }

      override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (candidate in matches) {
          if (parseVoiceCommand(candidate) != null) {
            handleTranscript(candidate, isFinal = true)
            break
          }
        }
        if (isRunning && usingAndroidFallback) {
          scheduleAndroidSpeechRestart(sessionGeneration.get(), 160L, recreate = false)
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val matches =
          partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (candidate in matches) {
          if (parseVoiceCommand(candidate) != null) {
            handleTranscript(candidate, isFinal = false)
            break
          }
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }

  fun start() {
    val shouldStart =
      synchronized(stateLock) {
        if (isRunning) {
          false
        } else {
          isRunning = true
          true
        }
      }
    if (!shouldStart) return

    val gen = sessionGeneration.incrementAndGet()
    if (usingAndroidFallback) {
      mainHandler.post { startAndroidSpeechFallback(gen) }
      return
    }

    workerExecutor.execute {
      if (!isRunning || sessionGeneration.get() != gen) return@execute
      var startedVosk = false
      try {
        val loadedModel = synchronized(stateLock) { model } ?: loadOrUnpackModel()
        if (!isRunning || sessionGeneration.get() != gen) return@execute

        if (loadedModel != null) {
          synchronized(stateLock) {
            if (!isRunning || sessionGeneration.get() != gen) return@synchronized
            model = loadedModel
            if (speechService == null) {
              val rec = createVoskRecognizer(loadedModel)
              val service = SpeechService(rec, SAMPLE_RATE_HZ)
              recognizer = rec
              speechService = service
              service.startListening(voskListener)
            }
            startedVosk = true
          }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to start offline Vosk SpeechService, falling back", t)
        cleanupVoskLocked()
      }

      if (!startedVosk && isRunning && sessionGeneration.get() == gen) {
        usingAndroidFallback = true
        mainHandler.post { startAndroidSpeechFallback(gen) }
      }
    }
  }

  fun stop() {
    val gen = sessionGeneration.incrementAndGet()
    synchronized(stateLock) { isRunning = false }
    mainHandler.post {
      cancelPendingQuickToggle()
      cancelPendingAndroidRestart()
      destroyAndroidSpeechRecognizer()
    }
    workerExecutor.execute {
      if (sessionGeneration.get() != gen && isRunning) return@execute
      synchronized(stateLock) { cleanupVoskLocked() }
    }
  }

  private fun createVoskRecognizer(loadedModel: Model): Recognizer {
    return try {
      Recognizer(loadedModel, SAMPLE_RATE_HZ, COMMAND_GRAMMAR)
    } catch (t: Throwable) {
      Log.w(TAG, "Vosk grammar recognizer failed, retrying unrestricted", t)
      Recognizer(loadedModel, SAMPLE_RATE_HZ)
    }
  }

  private fun cleanupVoskLocked() {
    try {
      speechService?.stop()
      speechService?.shutdown()
    } catch (t: Throwable) {
      Log.w(TAG, "Error stopping Vosk SpeechService", t)
    } finally {
      speechService = null
    }

    try {
      recognizer?.close()
    } catch (_: Throwable) {} finally {
      recognizer = null
    }
  }

  private fun restartVoskListeningIfActive() {
    if (!isRunning || usingAndroidFallback) return
    val gen = sessionGeneration.get()
    workerExecutor.execute {
      synchronized(stateLock) {
        if (!isRunning || usingAndroidFallback || sessionGeneration.get() != gen) {
          return@synchronized
        }
        try {
          speechService?.stop()
          speechService?.startListening(voskListener)
        } catch (t: Throwable) {
          Log.w(TAG, "Failed to restart Vosk listener", t)
        }
      }
    }
  }

  private fun startAndroidSpeechFallback(gen: Long) {
    if (!isRunning || sessionGeneration.get() != gen) return
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      Log.w(TAG, "No system SpeechRecognizer service available on this device")
      isRunning = false
      return
    }
    try {
      if (androidSpeechRecognizer == null) {
        androidSpeechRecognizer =
          SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(androidSpeechListener)
          }
      }
      val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
          putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
          if (androidPreferOffline) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          }
        }
      androidSpeechRecognizer?.startListening(intent)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to start Android SpeechRecognizer fallback", t)
      scheduleAndroidSpeechRestart(gen, 700L, recreate = true)
    }
  }

  private fun scheduleAndroidSpeechRestart(gen: Long, delayMs: Long, recreate: Boolean) {
    cancelPendingAndroidRestart()
    val runnable =
      Runnable {
        pendingAndroidRestartRunnable = null
        if (!isRunning || !usingAndroidFallback || sessionGeneration.get() != gen) return@Runnable
        if (recreate) {
          destroyAndroidSpeechRecognizer()
        }
        startAndroidSpeechFallback(gen)
      }
    pendingAndroidRestartRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
  }

  private fun cancelPendingAndroidRestart() {
    pendingAndroidRestartRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingAndroidRestartRunnable = null
  }

  private fun destroyAndroidSpeechRecognizer() {
    try {
      androidSpeechRecognizer?.cancel()
      androidSpeechRecognizer?.destroy()
    } catch (t: Throwable) {
      Log.w(TAG, "Error destroying Android SpeechRecognizer", t)
    } finally {
      androidSpeechRecognizer = null
    }
  }

  private fun loadOrUnpackModel(): Model? {
    return try {
      val targetDir = File(appContext.filesDir, ASSET_MODEL_DIR)
      val readyMarker = File(targetDir, ".unpacked_v1")
      if (!readyMarker.exists() || !hasRequiredVoskFiles(targetDir)) {
        if (!hasRequiredVoskAssets()) {
          Log.w(TAG, "Bundled Vosk assets incomplete (missing am/final.mdl or graph); using SpeechRecognizer fallback")
          return null
        }
        if (targetDir.exists()) {
          targetDir.deleteRecursively()
        }
        copyAssetFolder(ASSET_MODEL_DIR, targetDir)
        if (!hasRequiredVoskFiles(targetDir)) {
          Log.w(TAG, "Unpacked Vosk model missing required binaries")
          return null
        }
        readyMarker.parentFile?.mkdirs()
        readyMarker.writeText("ok")
      }
      Model(targetDir.absolutePath)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to unpack Vosk model from assets", t)
      null
    }
  }

  private fun hasRequiredVoskAssets(): Boolean {
    val amEntries = appContext.assets.list("$ASSET_MODEL_DIR/am") ?: emptyArray()
    val graphEntries = appContext.assets.list("$ASSET_MODEL_DIR/graph") ?: emptyArray()
    val hasAm = amEntries.contains("final.mdl")
    val hasGraph = graphEntries.contains("Gr.fst") || graphEntries.contains("HCLr.fst")
    return hasAm && hasGraph
  }

  private fun hasRequiredVoskFiles(dir: File): Boolean {
    val hasAm = File(dir, "am/final.mdl").exists()
    val hasGraph = File(dir, "graph/Gr.fst").exists() || File(dir, "graph/HCLr.fst").exists()
    return hasAm && hasGraph
  }

  private fun copyAssetFolder(assetPath: String, targetDir: File) {
    val entries = appContext.assets.list(assetPath) ?: emptyArray()
    if (entries.isEmpty()) {
      targetDir.parentFile?.mkdirs()
      appContext.assets.open(assetPath).use { input ->
        FileOutputStream(targetDir).use { output -> input.copyTo(output) }
      }
    } else {
      targetDir.mkdirs()
      for (entry in entries) {
        copyAssetFolder("$assetPath/$entry", File(targetDir, entry))
      }
    }
  }

  private fun handleTranscript(transcript: String, isFinal: Boolean) {
    val command = parseVoiceCommand(transcript) ?: return
    mainHandler.post {
      if (!isRunning) return@post
      when (command) {
        VoiceCommand.OPEN_PERMANENT,
        VoiceCommand.CLOSE_PERMANENT,
        VoiceCommand.NAVIGATE_VITALS,
        VoiceCommand.NAVIGATE_MAP,
        VoiceCommand.NAVIGATE_SUIT,
        VoiceCommand.NAVIGATE_COGNITIVE,
        VoiceCommand.MAP_ZOOM_IN,
        VoiceCommand.MAP_ZOOM_OUT,
        VoiceCommand.MAP_RECENTER -> {
          cancelPendingQuickToggle()
          emitWithDebounce(command)
        }
        VoiceCommand.TOGGLE_QUICK -> {
          if (isFinal) {
            cancelPendingQuickToggle()
            emitWithDebounce(command)
          } else if (pendingQuickToggleRunnable == null) {
            val runnable =
              Runnable {
                pendingQuickToggleRunnable = null
                if (isRunning) {
                  emitWithDebounce(VoiceCommand.TOGGLE_QUICK)
                }
              }
            pendingQuickToggleRunnable = runnable
            mainHandler.postDelayed(runnable, PARTIAL_DISAMBIGUATION_DELAY_MS)
          }
        }
      }
    }
  }

  private fun cancelPendingQuickToggle() {
    pendingQuickToggleRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingQuickToggleRunnable = null
  }

  private fun emitWithDebounce(command: VoiceCommand) {
    val now = SystemClock.uptimeMillis()
    if (command != lastCommand || now - lastCommandTimeMs >= COMMAND_DEBOUNCE_MS) {
      lastCommand = command
      lastCommandTimeMs = now
      onCommandDetected(command)
    }
  }

  companion object {
    private const val TAG = "VoiceCommandRecognizer"
    private const val ASSET_MODEL_DIR = "vosk-model-small-en-us-0.15"
    private const val SAMPLE_RATE_HZ = 16000.0f
    private const val COMMAND_DEBOUNCE_MS = 950L
    private const val PARTIAL_DISAMBIGUATION_DELAY_MS = 550L

    private const val COMMAND_GRAMMAR =
      "[\"dashboard\", \"dashboard open\", \"dashboard close\", \"open dashboard\", \"close dashboard\", \"vitals\", \"map\", \"meter\", \"suit\", \"suit status\", \"cognitive\", \"readiness\", \"zoom in\", \"zoom out\", \"recenter\", \"center map\", \"reset map\", \"[unk]\"]"

    internal fun extractTranscript(hypothesisJson: String?, key: String): String {
      if (hypothesisJson.isNullOrBlank()) return ""
      val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
      return pattern.find(hypothesisJson)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    fun parseVoiceCommand(transcript: String): VoiceCommand? {
      val normalized =
        transcript
          .lowercase()
          .replace(Regex("[^a-z0-9\\s]"), " ")
          .replace(Regex("\\s+"), " ")
          .trim()

      if (normalized.isEmpty()) return null

      return when {
        normalized.contains("dashboard open") || normalized.contains("open dashboard") ->
          VoiceCommand.OPEN_PERMANENT
        normalized.contains("dashboard close") || normalized.contains("close dashboard") ->
          VoiceCommand.CLOSE_PERMANENT
        Regex("\\bzoom in\\b").containsMatchIn(normalized) -> VoiceCommand.MAP_ZOOM_IN
        Regex("\\bzoom out\\b").containsMatchIn(normalized) -> VoiceCommand.MAP_ZOOM_OUT
        Regex("\\b(recenter|center map|reset map)\\b").containsMatchIn(normalized) ->
          VoiceCommand.MAP_RECENTER
        Regex("\\bvitals\\b").containsMatchIn(normalized) -> VoiceCommand.NAVIGATE_VITALS
        Regex("\\b(map|meter)\\b").containsMatchIn(normalized) -> VoiceCommand.NAVIGATE_MAP
        Regex("\\bsuit\\b").containsMatchIn(normalized) -> VoiceCommand.NAVIGATE_SUIT
        Regex("\\b(cognitive|readiness)\\b").containsMatchIn(normalized) ->
          VoiceCommand.NAVIGATE_COGNITIVE
        Regex("\\bdashboard\\b").containsMatchIn(normalized) -> VoiceCommand.TOGGLE_QUICK
        else -> null
      }
    }
  }
}
