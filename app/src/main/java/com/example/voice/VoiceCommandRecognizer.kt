package com.example.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

enum class VoiceCommand {
  TOGGLE_QUICK,
  OPEN_PERMANENT,
  CLOSE_PERMANENT,
}

class VoiceCommandRecognizer(
  context: Context,
  private val onCommandDetected: (VoiceCommand) -> Unit,
) {

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val workerExecutor = Executors.newSingleThreadExecutor()
  private val stateLock = Any()

  @Volatile private var isRunning = false
  private var model: Model? = null
  private var recognizer: Recognizer? = null
  private var speechService: SpeechService? = null

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
        if (isRunning) {
          mainHandler.postDelayed({ restartListeningIfActive() }, 450L)
        }
      }

      override fun onTimeout() {
        if (isRunning) {
          restartListeningIfActive()
        }
      }
    }

  fun start() {
    if (isRunning) return
    isRunning = true
    workerExecutor.execute {
      if (!isRunning) return@execute
      try {
        val loadedModel = synchronized(stateLock) { model } ?: loadOrUnpackModel()
        if (!isRunning || loadedModel == null) return@execute

        synchronized(stateLock) {
          if (!isRunning) return@synchronized
          model = loadedModel
          if (speechService == null) {
            val rec = Recognizer(loadedModel, SAMPLE_RATE_HZ, COMMAND_GRAMMAR)
            val service = SpeechService(rec, SAMPLE_RATE_HZ)
            recognizer = rec
            speechService = service
            service.startListening(voskListener)
          }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to start offline Vosk SpeechService", t)
      }
    }
  }

  fun stop() {
    isRunning = false
    mainHandler.post { cancelPendingQuickToggle() }
    workerExecutor.execute {
      synchronized(stateLock) {
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
    }
  }

  private fun restartListeningIfActive() {
    if (!isRunning) return
    workerExecutor.execute {
      synchronized(stateLock) {
        if (!isRunning) return@synchronized
        try {
          speechService?.stop()
          speechService?.startListening(voskListener)
        } catch (t: Throwable) {
          Log.w(TAG, "Failed to restart Vosk listener", t)
        }
      }
    }
  }

  private fun loadOrUnpackModel(): Model? {
    return try {
      val targetDir = File(appContext.filesDir, ASSET_MODEL_DIR)
      val readyMarker = File(targetDir, ".unpacked_v1")
      if (!readyMarker.exists()) {
        if (targetDir.exists()) {
          targetDir.deleteRecursively()
        }
        copyAssetFolder(ASSET_MODEL_DIR, targetDir)
        readyMarker.parentFile?.mkdirs()
        readyMarker.writeText("ok")
      }
      Model(targetDir.absolutePath)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to unpack Vosk model from assets", t)
      null
    }
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
        VoiceCommand.CLOSE_PERMANENT -> {
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
      "[\"dashboard\", \"dashboard open\", \"dashboard close\", \"open dashboard\", \"close dashboard\", \"[unk]\"]"

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
        Regex("\\bdashboard\\b").containsMatchIn(normalized) -> VoiceCommand.TOGGLE_QUICK
        else -> null
      }
    }
  }
}
