package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.eye.EyeSample
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/** Normalised (0..1, mirrored like a selfie) eye overlay points for the preview. */
data class EyeOverlay(
  val leftIris: Pair<Float, Float>,
  val rightIris: Pair<Float, Float>,
  val eyeContour: List<Pair<Float, Float>>,
)

/**
 * Hidden front-camera analyzer: MediaPipe Face Landmarker with blendshapes. Runs continuously
 * (preview or not) and reports per-frame eyelid closure and iris gaze offset. When [previewEnabled]
 * is set it additionally publishes a small mirrored bitmap for the Mental tile.
 */
class FaceEyeAnalyzer(
  context: Context,
  private val onEyeSample: (EyeSample) -> Unit,
  private val onPreviewFrame: (Bitmap, EyeOverlay?) -> Unit = { _, _ -> },
) : ImageAnalysis.Analyzer {

  private val faceLandmarker: FaceLandmarker? = createFaceLandmarker(context.applicationContext)
  private val matrix = Matrix()
  @Volatile private var isClosed = false
  @Volatile var previewEnabled: Boolean = false
  private var lastFrameTimeMs = 0L
  private var lastPreviewMs = 0L
  @Volatile private var pendingPreview: Bitmap? = null

  val isAvailable: Boolean
    get() = faceLandmarker != null

  override fun analyze(imageProxy: ImageProxy) {
    val landmarker = faceLandmarker
    if (isClosed || landmarker == null) {
      imageProxy.close()
      return
    }
    val now = SystemClock.uptimeMillis()
    if (now - lastFrameTimeMs < MIN_FRAME_INTERVAL_MS) {
      imageProxy.close()
      return
    }
    try {
      val raw = imageProxy.toBitmap()
      val rotation = imageProxy.imageInfo.rotationDegrees
      val upright =
        if (rotation != 0) {
          matrix.reset()
          matrix.postRotate(rotation.toFloat())
          Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false).also { raw.recycle() }
        } else {
          raw
        }
      if (previewEnabled && now - lastPreviewMs >= PREVIEW_INTERVAL_MS) {
        lastPreviewMs = now
        val scale = PREVIEW_WIDTH_PX.toFloat() / upright.width
        matrix.reset()
        matrix.postScale(-scale, scale) // mirror like a selfie view
        pendingPreview = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, matrix, true)
      }
      val ts = if (now <= lastFrameTimeMs) lastFrameTimeMs + 1 else now
      lastFrameTimeMs = ts
      landmarker.detectAsync(BitmapImageBuilder(upright).build(), ts)
    } catch (t: Throwable) {
      Log.w(TAG, "Front frame skipped", t)
    } finally {
      imageProxy.close()
    }
  }

  private fun handleResult(result: FaceLandmarkerResult) {
    if (isClosed) return
    val ts = result.timestampMs()
    val faces = result.faceLandmarks()
    if (faces.isEmpty()) {
      onEyeSample(EyeSample(timestampMs = ts, faceDetected = false))
      pendingPreview?.let {
        pendingPreview = null
        onPreviewFrame(it, null)
      }
      return
    }
    val lm = faces[0]
    var blinkL = 0f
    var blinkR = 0f
    val shapes = result.faceBlendshapes()
    if (shapes.isPresent && shapes.get().isNotEmpty()) {
      for (c in shapes.get()[0]) {
        when (c.categoryName()) {
          "eyeBlinkLeft" -> blinkL = c.score()
          "eyeBlinkRight" -> blinkR = c.score()
        }
      }
    }
    var gazeX = 0f
    var gazeY = 0f
    var overlay: EyeOverlay? = null
    if (lm.size >= 478) {
      fun gaze(iris: Int, outer: Int, inner: Int, upper: Int, lower: Int): Pair<Float, Float> {
        val w = kotlin.math.abs(lm[outer].x() - lm[inner].x()).coerceAtLeast(1e-4f)
        val cx = (lm[outer].x() + lm[inner].x()) * 0.5f
        val cy = (lm[upper].y() + lm[lower].y()) * 0.5f
        return ((lm[iris].x() - cx) / (w * 0.5f)) to ((lm[iris].y() - cy) / (w * 0.5f))
      }
      val (lx, ly) = gaze(LEFT_IRIS, 33, 133, 159, 145)
      val (rx, ry) = gaze(RIGHT_IRIS, 263, 362, 386, 374)
      gazeX = (lx + rx) * 0.5f
      gazeY = (ly + ry) * 0.5f
      fun mirrored(i: Int) = (1f - lm[i].x()) to lm[i].y()
      overlay =
        EyeOverlay(
          leftIris = mirrored(LEFT_IRIS),
          rightIris = mirrored(RIGHT_IRIS),
          eyeContour = EYE_CONTOUR.map { mirrored(it) },
        )
    }
    onEyeSample(EyeSample(ts, true, closureLeft = blinkL, closureRight = blinkR, gazeX = gazeX, gazeY = gazeY))
    pendingPreview?.let {
      pendingPreview = null
      onPreviewFrame(it, overlay)
    }
  }

  fun close() {
    isClosed = true
    try {
      faceLandmarker?.close()
    } catch (t: Throwable) {
      Log.w(TAG, "Error closing FaceLandmarker", t)
    }
  }

  private fun createFaceLandmarker(context: Context): FaceLandmarker? =
    tryBuild(context, Delegate.GPU) ?: tryBuild(context, Delegate.CPU)

  private fun tryBuild(context: Context, delegate: Delegate): FaceLandmarker? {
    return try {
      val options =
        FaceLandmarker.FaceLandmarkerOptions.builder()
          .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_PATH).setDelegate(delegate).build())
          .setRunningMode(RunningMode.LIVE_STREAM)
          .setNumFaces(1)
          .setMinFaceDetectionConfidence(0.5f)
          .setMinFacePresenceConfidence(0.5f)
          .setMinTrackingConfidence(0.5f)
          .setOutputFaceBlendshapes(true)
          .setResultListener { result: FaceLandmarkerResult, _ -> handleResult(result) }
          .setErrorListener { error -> Log.w(TAG, "FaceLandmarker error", error) }
          .build()
      FaceLandmarker.createFromOptions(context, options)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to initialize FaceLandmarker with $delegate", t)
      null
    }
  }

  companion object {
    private const val TAG = "FaceEyeAnalyzer"
    private const val MODEL_ASSET_PATH = "face_landmarker.task"
    private const val MIN_FRAME_INTERVAL_MS = 45L // ~22 fps cap; blinks last 100-400 ms
    private const val PREVIEW_INTERVAL_MS = 90L
    private const val PREVIEW_WIDTH_PX = 240
    private const val LEFT_IRIS = 468
    private const val RIGHT_IRIS = 473
    private val EYE_CONTOUR = listOf(33, 159, 133, 145, 362, 386, 263, 374)
  }
}
