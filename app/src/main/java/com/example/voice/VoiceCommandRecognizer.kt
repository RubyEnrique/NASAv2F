package com.example.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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

sealed interface VoiceCommand {
  data object TOGGLE_QUICK : VoiceCommand
  data object OPEN_PERMANENT : VoiceCommand
  data object CLOSE_PERMANENT : VoiceCommand
  data object NAVIGATE_RADIATION : VoiceCommand
  data object NAVIGATE_ISOLATION : VoiceCommand
  data object NAVIGATE_DISTANCE : VoiceCommand
  data object NAVIGATE_GRAVITY : VoiceCommand
  data object NAVIGATE_ENVIRONMENT : VoiceCommand
  data object NAVIGATE_DRILLS : VoiceCommand
  data object MAP_ZOOM_IN : VoiceCommand
  data object MAP_ZOOM_OUT : VoiceCommand
  data object MAP_RECENTER : VoiceCommand
  data object RECENTER_VIEW : VoiceCommand
  data object CENTER_LOCK : VoiceCommand
  data object END_ALL_EMERGENCIES : VoiceCommand
  data object ACKNOWLEDGE : VoiceCommand
  data object TOGGLE_EYE_PREVIEW : VoiceCommand
  data object CALIBRATE_MIC : VoiceCommand
  data object SENSITIVITY_UP : VoiceCommand
  data object SENSITIVITY_DOWN : VoiceCommand
  data class RunEmergency(val number: Int) : VoiceCommand
}

/**
 * Offline voice commands: Vosk small-en model fed from our own AudioRecord loop through a
 * calibrated voice-activity gate ([MicCalibrator]). Only gated speech reaches the recogniser, which
 * stops grammar-constrained decoding from "hearing" commands in suit-fan / wind noise, and final
 * results must pass a word-confidence threshold tied to the sensitivity setting.
 * Falls back to the platform SpeechRecognizer if the bundled model or the mic cannot be opened.
 */
class VoiceCommandRecognizer(
  context: Context,
  private val onCommandDetected: (VoiceCommand) -> Unit,
  private val onMicStatus: (MicStatus) -> Unit = {},
) {

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val workerExecutor = Executors.newSingleThreadExecutor()
  private val stateLock = Any()
  private val sessionGeneration = AtomicLong(0L)

  @Volatile private var isRunning = false
  @Volatile private var usingAndroidFallback = false
  @Volatile private var muted = false
  @Volatile private var pendingRecalibrate = false
  @Volatile private var pendingSensitivity: Int? = null

  private var model: Model? = null
  private val calibrator = MicCalibrator()
  private var audioThread: Thread? = null
  private var lastStatusMs = 0L
  private var lastHeard = ""

  private var androidSpeechRecognizer: SpeechRecognizer? = null
  private var androidPreferOffline = true
  private var pendingAndroidRestartRunnable: Runnable? = null

  private var lastCommandTimeMs = 0L
  private var lastCommand: VoiceCommand? = null

  /** Mute while the app itself is speaking so alerts are not re-recognised as commands. */
  fun setMuted(value: Boolean) {
    muted = value
  }

  fun recalibrate() {
    pendingRecalibrate = true
  }

  fun adjustSensitivity(delta: Int) {
    pendingSensitivity = (calibrator.sensitivity + delta).coerceIn(1, 5)
  }

  fun start() {
    val shouldStart =
      synchronized(stateLock) {
        if (isRunning) false
        else {
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
      val loaded = synchronized(stateLock) { model } ?: loadOrUnpackModel()
      if (loaded == null) {
        switchToFallback(gen)
        return@execute
      }
      synchronized(stateLock) { model = loaded }
      if (!isRunning || sessionGeneration.get() != gen) return@execute
      val thread = Thread({ runAudioLoop(gen, loaded) }, "vosk-audio").apply { priority = Thread.MAX_PRIORITY }
      audioThread = thread
      thread.start()
    }
  }

  fun stop() {
    sessionGeneration.incrementAndGet()
    synchronized(stateLock) { isRunning = false }
    audioThread = null
    mainHandler.post {
      cancelPendingAndroidRestart()
      destroyAndroidSpeechRecognizer()
    }
  }

  private fun switchToFallback(gen: Long) {
    if (isRunning && sessionGeneration.get() == gen) {
      usingAndroidFallback = true
      mainHandler.post { startAndroidSpeechFallback(gen) }
    }
  }

  @SuppressLint("MissingPermission")
  private fun runAudioLoop(gen: Long, loadedModel: Model) {
    var record: AudioRecord? = null
    var noiseSuppressor: NoiseSuppressor? = null
    var echoCanceler: AcousticEchoCanceler? = null
    var recognizer: Recognizer? = null
    try {
      recognizer = createVoskRecognizer(loadedModel)
      val minBuf =
        AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      record =
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          SAMPLE_RATE_HZ,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          maxOf(minBuf, FRAME_SAMPLES * 2 * 16),
        )
      if (record.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord failed to initialise")
      if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
      if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
      record.startRecording()

      val preRoll = ArrayDeque<ShortArray>()
      val buf = ShortArray(FRAME_SAMPLES)
      fun feed(frame: ShortArray) {
        val copy = frame.copyOf()
        calibrator.applyGain(copy, copy.size)
        if (recognizer.acceptWaveForm(copy, copy.size)) {
          handleFinalJson(recognizer.result)
        }
      }

      while (isRunning && sessionGeneration.get() == gen) {
        val n = record.read(buf, 0, FRAME_SAMPLES)
        if (n <= 0) continue
        val now = SystemClock.uptimeMillis()
        if (pendingRecalibrate) {
          pendingRecalibrate = false
          calibrator.recalibrate()
        }
        pendingSensitivity?.let {
          calibrator.sensitivity = it
          pendingSensitivity = null
        }
        if (muted) {
          if (calibrator.isGateOpen()) recognizer.reset()
          preRoll.clear()
          publishStatus(now, MicCalibrator.levelDb(buf, n), speaking = false)
          continue
        }
        val frame = if (n == buf.size) buf else buf.copyOf(n)
        val decision = calibrator.process(frame, frame.size, now)
        when (decision.event) {
          MicCalibrator.Event.SPEECH_START -> {
            recognizer.reset()
            preRoll.forEach { feed(it) }
            preRoll.clear()
            feed(frame)
          }
          MicCalibrator.Event.SPEECH_END -> {
            feed(frame)
            handleFinalJson(recognizer.finalResult)
          }
          MicCalibrator.Event.NONE -> {
            if (decision.gateOpen) {
              feed(frame)
            } else {
              preRoll.addLast(frame.copyOf())
              while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            }
          }
        }
        publishStatus(now, decision.levelDb, decision.gateOpen)
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Offline audio loop failed, falling back to platform recogniser", t)
      switchToFallback(gen)
    } finally {
      try {
        record?.stop()
      } catch (_: Throwable) {}
      record?.release()
      noiseSuppressor?.release()
      echoCanceler?.release()
      try {
        recognizer?.close()
      } catch (_: Throwable) {}
    }
  }

  private fun publishStatus(now: Long, levelDb: Float, speaking: Boolean) {
    if (now - lastStatusMs < STATUS_INTERVAL_MS) return
    lastStatusMs = now
    onMicStatus(
      MicStatus(
        levelDb = levelDb,
        noiseFloorDb = calibrator.noiseFloorDb,
        gateDb = calibrator.gateDb,
        speaking = speaking,
        calibrating = calibrator.calibrating,
        sensitivity = calibrator.sensitivity,
        gain = calibrator.currentGain(),
        engine = if (usingAndroidFallback) "SYSTEM" else "OFFLINE",
        lastHeard = lastHeard,
      )
    )
  }

  private fun handleFinalJson(json: String?) {
    val text = extractTranscript(json, "text")
    if (text.isEmpty() || text == "[unk]") return
    val confidence = extractMeanConfidence(json)
    val minConfidence = minConfidenceFor(calibrator.sensitivity)
    if (confidence != null && confidence < minConfidence) {
      lastHeard = "? $text"
      return
    }
    lastHeard = text
    dispatch(text)
  }

  private fun dispatch(transcript: String) {
    val command = parseVoiceCommand(transcript) ?: return
    when (command) {
      VoiceCommand.CALIBRATE_MIC -> recalibrate()
      VoiceCommand.SENSITIVITY_UP -> adjustSensitivity(+1)
      VoiceCommand.SENSITIVITY_DOWN -> adjustSensitivity(-1)
      else -> Unit
    }
    mainHandler.post {
      if (isRunning) emitWithDebounce(command)
    }
  }

  private fun createVoskRecognizer(loadedModel: Model): Recognizer {
    val rec =
      try {
        Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat(), COMMAND_GRAMMAR)
      } catch (t: Throwable) {
        Log.w(TAG, "Vosk grammar recognizer failed, retrying unrestricted", t)
        Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat())
      }
    try {
      rec.setWords(true)
    } catch (_: Throwable) {}
    return rec
  }

  private val androidSpeechListener =
    object : AndroidRecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {}

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
          lastStatusMs = now
          onMicStatus(MicStatus(levelDb = -60f + rmsdB * 4f, calibrating = false, engine = "SYSTEM", lastHeard = lastHeard))
        }
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {}

      override fun onError(error: Int) {
        if (!isRunning || !usingAndroidFallback) return
        val gen = sessionGeneration.get()
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
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
        if (!muted) {
          matches.firstOrNull { parseVoiceCommand(it) != null }?.let {
            lastHeard = it
            dispatch(it)
          }
        }
        if (isRunning && usingAndroidFallback) {
          scheduleAndroidSpeechRestart(sessionGeneration.get(), 160L, recreate = false)
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {}

      override fun onEvent(eventType: Int, params: Bundle?) {}
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
          SpeechRecognizer.createSpeechRecognizer(appContext).apply { setRecognitionListener(androidSpeechListener) }
      }
      val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
          putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
          if (androidPreferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
      androidSpeechRecognizer?.startListening(intent)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to start Android SpeechRecognizer fallback", t)
      scheduleAndroidSpeechRestart(gen, 700L, recreate = true)
    }
  }

  private fun scheduleAndroidSpeechRestart(gen: Long, delayMs: Long, recreate: Boolean) {
    cancelPendingAndroidRestart()
    val runnable = Runnable {
      pendingAndroidRestartRunnable = null
      if (!isRunning || !usingAndroidFallback || sessionGeneration.get() != gen) return@Runnable
      if (recreate) destroyAndroidSpeechRecognizer()
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
        if (!hasRequiredVoskAssets()) return null
        if (targetDir.exists()) targetDir.deleteRecursively()
        copyAssetFolder(ASSET_MODEL_DIR, targetDir)
        if (!hasRequiredVoskFiles(targetDir)) return null
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
    val am = appContext.assets.list("$ASSET_MODEL_DIR/am") ?: emptyArray()
    val graph = appContext.assets.list("$ASSET_MODEL_DIR/graph") ?: emptyArray()
    return am.contains("final.mdl") && (graph.contains("Gr.fst") || graph.contains("HCLr.fst"))
  }

  private fun hasRequiredVoskFiles(dir: File): Boolean =
    File(dir, "am/final.mdl").exists() &&
      (File(dir, "graph/Gr.fst").exists() || File(dir, "graph/HCLr.fst").exists())

  private fun copyAssetFolder(assetPath: String, targetDir: File) {
    val entries = appContext.assets.list(assetPath) ?: emptyArray()
    if (entries.isEmpty()) {
      targetDir.parentFile?.mkdirs()
      appContext.assets.open(assetPath).use { input -> FileOutputStream(targetDir).use { input.copyTo(it) } }
    } else {
      targetDir.mkdirs()
      for (entry in entries) copyAssetFolder("$assetPath/$entry", File(targetDir, entry))
    }
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
    private const val SAMPLE_RATE_HZ = 16000
    private const val FRAME_SAMPLES = 320 // 20 ms
    private const val PRE_ROLL_FRAMES = 15 // 300 ms
    private const val STATUS_INTERVAL_MS = 150L
    private const val COMMAND_DEBOUNCE_MS = 950L

    private val GRAMMAR_PHRASES =
      listOf(
        "dashboard open", "dashboard close", "open dashboard", "close dashboard",
        "mental", "mental health", "cognitive", "readiness", "isolation", "sleep", "vitals",
        "distance", "earth", "comms", "gravity", "exercise", "fitness", "environment", "suit",
        "suit status", "radiation", "shield", "shelter", "map", "meter", "drills", "drill", "emergencies",
        "emergency zero", "emergency one", "emergency two", "emergency three", "emergency four",
        "emergency five", "emergency six", "emergency seven", "emergency eight", "emergency nine",
        "emergency ten", "clear drill", "stop drill", "reset drills", "zoom in", "zoom out",
        "recenter", "recenter view", "center lock", "keep centered", "center map", "reset map",
        "end all emergencies", "end emergencies", "all clear", "acknowledge", "silence",
        "eye preview", "show eyes", "hide eyes", "calibrate", "calibrate microphone",
        "sensitivity up", "sensitivity down", "[unk]",
      )
    private val COMMAND_GRAMMAR = GRAMMAR_PHRASES.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    private val EMERGENCY_PATTERN =
      Regex("\\bemergency\\s+(10|ten|9|nine|8|eight|7|seven|6|six|5|five|4|four|3|three|2|two|1|one|0|zero)\\b")
    private val CLEAR_DRILL_PATTERN = Regex("\\b(clear drills?|stop drills?|reset drills?)\\b")
    private val CONF_PATTERN = Regex("\"conf\"\\s*:\\s*([0-9.]+)")

    fun minConfidenceFor(sensitivity: Int): Float = 0.62f + (3 - sensitivity.coerceIn(1, 5)) * 0.05f

    private fun parseEmergencyNumberToken(token: String): Int? =
      when (token) {
        "0", "zero" -> 0
        "1", "one" -> 1
        "2", "two" -> 2
        "3", "three" -> 3
        "4", "four" -> 4
        "5", "five" -> 5
        "6", "six" -> 6
        "7", "seven" -> 7
        "8", "eight" -> 8
        "9", "nine" -> 9
        "10", "ten" -> 10
        else -> null
      }

    internal fun extractTranscript(hypothesisJson: String?, key: String): String {
      if (hypothesisJson.isNullOrBlank()) return ""
      val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
      return pattern.find(hypothesisJson)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    internal fun extractMeanConfidence(json: String?): Float? {
      if (json.isNullOrBlank()) return null
      val values = CONF_PATTERN.findAll(json).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
      return if (values.isEmpty()) null else values.average().toFloat()
    }

    fun parseVoiceCommand(transcript: String): VoiceCommand? {
      val normalized =
        transcript.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
      if (normalized.isEmpty()) return null

      if (Regex("\\b(end all emergencies|end emergencies|all clear)\\b").containsMatchIn(normalized)) return VoiceCommand.END_ALL_EMERGENCIES
      if (CLEAR_DRILL_PATTERN.containsMatchIn(normalized)) return VoiceCommand.RunEmergency(0)
      EMERGENCY_PATTERN.find(normalized)?.let { match ->
        parseEmergencyNumberToken(match.groupValues[1])?.let { return VoiceCommand.RunEmergency(it) }
      }

      fun has(pattern: String) = Regex("\\b($pattern)\\b").containsMatchIn(normalized)
      return when {
        normalized.contains("dashboard open") || normalized.contains("open dashboard") -> VoiceCommand.OPEN_PERMANENT
        normalized.contains("dashboard close") || normalized.contains("close dashboard") -> VoiceCommand.CLOSE_PERMANENT
        has("calibrate|calibrate microphone") -> VoiceCommand.CALIBRATE_MIC
        has("sensitivity up") -> VoiceCommand.SENSITIVITY_UP
        has("sensitivity down") -> VoiceCommand.SENSITIVITY_DOWN
        has("zoom in") -> VoiceCommand.MAP_ZOOM_IN
        has("zoom out") -> VoiceCommand.MAP_ZOOM_OUT
        has("center map|reset map") -> VoiceCommand.MAP_RECENTER
        has("recenter|recenter view") -> VoiceCommand.RECENTER_VIEW
        has("center lock|keep centered") -> VoiceCommand.CENTER_LOCK
        has("acknowledge|silence") -> VoiceCommand.ACKNOWLEDGE
        has("eye preview|show eyes|hide eyes") -> VoiceCommand.TOGGLE_EYE_PREVIEW
        has("radiation|shield|shelter") -> VoiceCommand.NAVIGATE_RADIATION
        has("mental|mental health|cognitive|readiness|isolation|sleep|vitals") -> VoiceCommand.NAVIGATE_ISOLATION
        has("distance|earth|comms") -> VoiceCommand.NAVIGATE_DISTANCE
        has("gravity|exercise|fitness") -> VoiceCommand.NAVIGATE_GRAVITY
        has("environment|suit|suit status|map|meter") -> VoiceCommand.NAVIGATE_ENVIRONMENT
        has("drills?|emergencies") -> VoiceCommand.NAVIGATE_DRILLS
        // Presenter tuning (v6): the bare word "dashboard" no longer toggles; it is too common in speech.
        else -> null
      }
    }
  }
}
