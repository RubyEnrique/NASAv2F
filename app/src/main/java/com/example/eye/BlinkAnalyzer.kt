package com.example.eye

import kotlin.math.sqrt

/*
 * Pure-Kotlin blink / eyelid analytics fed by the hidden front camera (MediaPipe Face Landmarker
 * blendshapes). No Android imports so it is unit-testable.
 *
 * Reference values used to interpret the numbers (see CognitiveLoadModel for how they are used):
 *  - Resting spontaneous blink rate in adults is commonly cited around 15-20 /min; population
 *    default here is 17 /min. Individual variation is large, so a personal baseline is learned
 *    from the first minutes of tracking.
 *  - Demanding visual / cognitive tasks suppress blinking (reading averages ~10 /min with a mean
 *    blink duration of ~129 ms, Nature Sci. Reports 2025; surgeons show fewer and shorter blinks as
 *    mental workload rises).
 *  - Fatigue and time-on-task raise blink rate (FAA AM-94/17: ~12 -> ~16 /min) and lengthen blinks.
 *  - PERCLOS = proportion of time the eyes are >= 80% closed over a 1 minute window.
 */

/** One processed front-camera frame. Closure values are 0 (open) .. 1 (fully closed). */
data class EyeSample(
  val timestampMs: Long,
  val faceDetected: Boolean,
  val closureLeft: Float = 0f,
  val closureRight: Float = 0f,
  val gazeX: Float = 0f,
  val gazeY: Float = 0f,
)

enum class EyeTrackingStatus {
  OFF,
  STARTING,
  ACTIVE,
  UNSUPPORTED_DEVICE,
  MODEL_ERROR,
}

data class EyeMetrics(
  val faceDetected: Boolean = false,
  val totalBlinks: Int = 0,
  /** Blinks per minute over the last 60 s of face-visible time, null until enough data. */
  val blinkRatePerMin: Float? = null,
  /** Personal resting baseline (blinks/min) once calibrated, else null. */
  val baselineBlinkRate: Float? = null,
  val meanBlinkDurationMs: Float? = null,
  val longBlinkCount: Int = 0,
  /** Fraction of face-visible time in the last 60 s with eyes >= 80% closed. */
  val perclos: Float? = null,
  val gazeDispersion: Float? = null,
  /** Fraction of frames in the last 60 s where a face was found. */
  val faceCoverage: Float = 0f,
  val eyeOpenness: Float = 1f,
  /** 0..1 progress toward the personal blink-rate baseline. */
  val calibrationProgress: Float = 0f,
) {
  val effectiveBaseline: Float
    get() = baselineBlinkRate ?: BlinkAnalyzer.POPULATION_BASELINE_PER_MIN

  /** True when there is enough recent face data to trust the ocular metrics. */
  val isReliable: Boolean
    get() = blinkRatePerMin != null && faceCoverage >= 0.35f
}

class BlinkAnalyzer(
  private val windowMs: Long = 60_000L,
  private val calibrationObservedMs: Long = 120_000L,
) {
  private data class FrameRecord(val ts: Long, val face: Boolean, val closed80: Boolean, val gx: Float, val gy: Float)

  private data class BlinkEvent(val endTs: Long, val durationMs: Long)

  private val frames = ArrayDeque<FrameRecord>()
  private val blinks = ArrayDeque<BlinkEvent>()
  private var totalBlinks = 0
  private var blinkStartMs: Long? = null
  private var lastTs: Long? = null
  private var lastOpenness = 1f
  private var lastFace = false

  // Personal baseline calibration accumulators.
  private var calibObservedMs = 0L
  private var calibBlinks = 0
  private var baseline: Float? = null

  fun reset() {
    frames.clear()
    blinks.clear()
    totalBlinks = 0
    blinkStartMs = null
    lastTs = null
    lastOpenness = 1f
    lastFace = false
    calibObservedMs = 0L
    calibBlinks = 0
    baseline = null
  }

  fun process(sample: EyeSample) {
    val prevTs = lastTs
    if (prevTs != null && sample.timestampMs <= prevTs) return
    val dt = if (prevTs == null) 0L else (sample.timestampMs - prevTs).coerceAtMost(MAX_FRAME_GAP_MS)
    lastTs = sample.timestampMs
    lastFace = sample.faceDetected

    if (!sample.faceDetected) {
      blinkStartMs = null
      frames.addLast(FrameRecord(sample.timestampMs, face = false, closed80 = false, gx = 0f, gy = 0f))
      trim(sample.timestampMs)
      return
    }

    val closure = ((sample.closureLeft + sample.closureRight) * 0.5f).coerceIn(0f, 1f)
    lastOpenness = 1f - closure

    if (baseline == null) {
      calibObservedMs += dt
    }

    val start = blinkStartMs
    if (start == null) {
      if (closure >= CLOSE_THRESHOLD) blinkStartMs = sample.timestampMs
    } else if (closure <= OPEN_THRESHOLD) {
      val duration = sample.timestampMs - start
      blinkStartMs = null
      if (duration in MIN_BLINK_MS..MAX_BLINK_MS) {
        blinks.addLast(BlinkEvent(sample.timestampMs, duration))
        totalBlinks++
        if (baseline == null) calibBlinks++
      }
    }

    if (baseline == null && calibObservedMs >= calibrationObservedMs) {
      val minutes = calibObservedMs / 60_000f
      baseline = (calibBlinks / minutes).coerceIn(MIN_BASELINE, MAX_BASELINE)
    }

    frames.addLast(
      FrameRecord(
        sample.timestampMs,
        face = true,
        closed80 = closure >= PERCLOS_CLOSURE,
        gx = sample.gazeX,
        gy = sample.gazeY,
      )
    )
    trim(sample.timestampMs)
  }

  private fun trim(nowMs: Long) {
    while (frames.isNotEmpty() && nowMs - frames.first().ts > windowMs) frames.removeFirst()
    while (blinks.isNotEmpty() && nowMs - blinks.first().endTs > windowMs) blinks.removeFirst()
  }

  fun metrics(): EyeMetrics {
    if (frames.isEmpty()) {
      return EyeMetrics(baselineBlinkRate = baseline, calibrationProgress = calibrationProgress(), totalBlinks = totalBlinks)
    }
    var faceFrames = 0
    var closedFrames = 0
    var observedMs = 0L
    var sx = 0.0
    var sy = 0.0
    var sxx = 0.0
    var syy = 0.0
    var prev: FrameRecord? = null
    for (f in frames) {
      if (f.face) {
        faceFrames++
        if (f.closed80) closedFrames++
        sx += f.gx
        sy += f.gy
        sxx += f.gx * f.gx
        syy += f.gy * f.gy
        val p = prev
        if (p != null && p.face) observedMs += (f.ts - p.ts).coerceAtMost(MAX_FRAME_GAP_MS)
      }
      prev = f
    }
    val coverage = faceFrames.toFloat() / frames.size
    val rate =
      if (observedMs >= MIN_OBSERVED_FOR_RATE_MS) blinks.size / (observedMs / 60_000f) else null
    val meanDur = if (blinks.isNotEmpty()) blinks.map { it.durationMs }.average().toFloat() else null
    val perclos = if (faceFrames >= MIN_FRAMES_FOR_PERCLOS) closedFrames.toFloat() / faceFrames else null
    val dispersion =
      if (faceFrames >= MIN_FRAMES_FOR_PERCLOS) {
        val n = faceFrames.toDouble()
        val vx = (sxx / n - (sx / n) * (sx / n)).coerceAtLeast(0.0)
        val vy = (syy / n - (sy / n) * (sy / n)).coerceAtLeast(0.0)
        sqrt(vx + vy).toFloat()
      } else {
        null
      }
    return EyeMetrics(
      faceDetected = lastFace,
      totalBlinks = totalBlinks,
      blinkRatePerMin = rate,
      baselineBlinkRate = baseline,
      meanBlinkDurationMs = meanDur,
      longBlinkCount = blinks.count { it.durationMs >= LONG_BLINK_MS },
      perclos = perclos,
      gazeDispersion = dispersion,
      faceCoverage = coverage,
      eyeOpenness = if (lastFace) lastOpenness else 1f,
      calibrationProgress = calibrationProgress(),
    )
  }

  private fun calibrationProgress(): Float =
    if (baseline != null) 1f else (calibObservedMs.toFloat() / calibrationObservedMs).coerceIn(0f, 1f)

  companion object {
    const val POPULATION_BASELINE_PER_MIN = 17f
    const val CLOSE_THRESHOLD = 0.50f
    const val OPEN_THRESHOLD = 0.35f
    const val PERCLOS_CLOSURE = 0.80f
    const val MIN_BLINK_MS = 50L
    const val MAX_BLINK_MS = 1_500L
    const val LONG_BLINK_MS = 400L
    private const val MIN_BASELINE = 6f
    private const val MAX_BASELINE = 32f
    private const val MAX_FRAME_GAP_MS = 500L
    private const val MIN_OBSERVED_FOR_RATE_MS = 15_000L
    private const val MIN_FRAMES_FOR_PERCLOS = 30
  }
}
