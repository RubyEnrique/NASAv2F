package com.example.voice

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

data class MicStatus(
  val levelDb: Float = -90f,
  val noiseFloorDb: Float = -60f,
  val gateDb: Float = -51f,
  val speaking: Boolean = false,
  val calibrating: Boolean = false,
  val sensitivity: Int = MicCalibrator.DEFAULT_SENSITIVITY,
  val gain: Float = 1f,
  val engine: String = "OFFLINE",
  val lastHeard: String = "",
)

/**
 * Energy-based voice-activity gate with automatic noise-floor calibration and gain control.
 * Pure Kotlin (no Android) so it can be unit-tested with synthetic audio.
 *
 *  - Calibration: the first [calibrationMs] of audio set the noise floor (median frame level).
 *  - Gate: opens when the level exceeds floor + margin(sensitivity) for 2 consecutive frames and
 *    closes after [hangoverMs] below (gate - 3 dB). Sensitivity 1 (strict) .. 5 (very sensitive).
 *  - The floor keeps adapting slowly while the gate is closed so fans / wind are tracked.
 *  - AGC: per-utterance gain that brings speech peaks toward [targetPeakDb], clamped 1x..6x.
 */
class MicCalibrator(
  sensitivity: Int = DEFAULT_SENSITIVITY,
  private val calibrationMs: Long = 1_500L,
  private val hangoverMs: Long = 420L,
  private val maxUtteranceMs: Long = 4_500L,
  private val targetPeakDb: Float = -14f,
) {
  enum class Event { NONE, SPEECH_START, SPEECH_END }

  data class Decision(val event: Event, val gateOpen: Boolean, val levelDb: Float, val gain: Float)

  var sensitivity: Int = sensitivity.coerceIn(1, 5)
    set(value) {
      field = value.coerceIn(1, 5)
    }

  var noiseFloorDb: Float = -60f
    private set

  var calibrating: Boolean = true
    private set

  private var calibStartMs: Long = -1L
  private val calibLevels = ArrayList<Float>()
  private var aboveCount = 0
  private var gateOpen = false
  private var gateOpenedMs = 0L
  private var lastAboveMs = 0L
  private var utterancePeakDb = -90f
  private var gain = 1f
  private var loudSinceMs = -1L

  val gateDb: Float
    get() = noiseFloorDb + marginDb(sensitivity)

  fun recalibrate() {
    calibrating = true
    calibStartMs = -1L
    calibLevels.clear()
    aboveCount = 0
    gateOpen = false
  }

  fun process(frame: ShortArray, length: Int, nowMs: Long): Decision {
    val level = levelDb(frame, length)

    if (calibrating) {
      if (calibStartMs < 0) calibStartMs = nowMs
      calibLevels.add(level)
      if (nowMs - calibStartMs >= calibrationMs && calibLevels.size >= 5) {
        val sorted = calibLevels.sorted()
        noiseFloorDb = sorted[sorted.size / 2].coerceIn(-90f, -20f)
        calibrating = false
      }
      return Decision(Event.NONE, false, level, gain)
    }

    val gate = gateDb
    if (!gateOpen) {
      if (level >= gate) {
        aboveCount++
        if (aboveCount >= 2) {
          gateOpen = true
          gateOpenedMs = nowMs
          lastAboveMs = nowMs
          utterancePeakDb = level
          return Decision(Event.SPEECH_START, true, level, gain)
        }
      } else {
        aboveCount = 0
        // Track the floor slowly while quiet.
        noiseFloorDb += 0.02f * (level - noiseFloorDb)
      }
      // A steady loud background (fan, wind) that never drops should become the new floor.
      if (level >= gate) {
        if (loudSinceMs < 0) loudSinceMs = nowMs
        if (nowMs - loudSinceMs > 6_000L) noiseFloorDb += 0.01f * (level - noiseFloorDb)
      } else {
        loudSinceMs = -1L
      }
      return Decision(Event.NONE, false, level, gain)
    }

    if (level > utterancePeakDb) utterancePeakDb = level
    if (level >= gate - 3f) lastAboveMs = nowMs
    val ended = nowMs - lastAboveMs >= hangoverMs || nowMs - gateOpenedMs >= maxUtteranceMs
    if (ended) {
      gateOpen = false
      aboveCount = 0
      gain = 10f.pow((targetPeakDb - utterancePeakDb) / 20f).coerceIn(1f, 6f)
      return Decision(Event.SPEECH_END, false, level, gain)
    }
    return Decision(Event.NONE, true, level, gain)
  }

  /** Applies the current gain in place with clipping. */
  fun applyGain(frame: ShortArray, length: Int) {
    if (gain <= 1.001f) return
    for (i in 0 until length) {
      frame[i] = (frame[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
  }

  fun currentGain(): Float = gain

  fun isGateOpen(): Boolean = gateOpen

  companion object {
    /** Presenter tuning (v6): start strict; say "sensitivity up" if it stops hearing you. */
    const val DEFAULT_SENSITIVITY = 2

    fun marginDb(sensitivity: Int): Float =
      when (sensitivity.coerceIn(1, 5)) {
        1 -> 16f
        2 -> 12f
        3 -> 9f
        4 -> 6f
        else -> 4f
      }

    fun levelDb(frame: ShortArray, length: Int): Float {
      if (length <= 0) return -90f
      var sum = 0.0
      for (i in 0 until length) {
        val v = frame[i].toDouble()
        sum += v * v
      }
      val rms = sqrt(sum / length)
      return if (rms < 1.0) -90f else (20.0 * log10(rms / 32768.0)).toFloat().coerceAtLeast(-90f)
    }
  }
}
