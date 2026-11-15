package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.gesture.HandLandmarkPoint
import com.example.gesture.HandObservation
import com.example.gesture.HandPose
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class MediaPipeHandAnalyzer(
  context: Context,
  private val onHandsDetected: (List<HandObservation>, Long) -> Unit,
) : ImageAnalysis.Analyzer {

  private val gestureRecognizer: GestureRecognizer? = createGestureRecognizer(context.applicationContext)
  private val handLandmarker: HandLandmarker? =
    if (gestureRecognizer == null) createHandLandmarker(context.applicationContext) else null
  private val rotationMatrix = Matrix()
  @Volatile private var isClosed = false
  private var lastFrameTimeMs = 0L

  override fun analyze(imageProxy: ImageProxy) {
    val recognizer = gestureRecognizer
    val landmarker = handLandmarker
    if (isClosed || (recognizer == null && landmarker == null)) {
      imageProxy.close()
      return
    }

    var rawBitmap: Bitmap? = null
    var rotatedBitmap: Bitmap? = null
    try {
      rawBitmap = imageProxy.toBitmap()
      val rotationDegrees = imageProxy.imageInfo.rotationDegrees
      val uprightBitmap =
        if (rotationDegrees != 0) {
          rotationMatrix.reset()
          rotationMatrix.postRotate(rotationDegrees.toFloat())
          Bitmap.createBitmap(
              rawBitmap,
              0,
              0,
              rawBitmap.width,
              rawBitmap.height,
              rotationMatrix,
              false,
            )
            .also { rotatedBitmap = it }
        } else {
          rawBitmap
        }

      var frameTimeMs = SystemClock.uptimeMillis()
      if (frameTimeMs <= lastFrameTimeMs) {
        frameTimeMs = lastFrameTimeMs + 1L
      }
      lastFrameTimeMs = frameTimeMs

      val mpImage = BitmapImageBuilder(uprightBitmap).build()
      if (recognizer != null) {
        recognizer.recognizeAsync(mpImage, frameTimeMs)
      } else {
        landmarker?.detectAsync(mpImage, frameTimeMs)
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Frame analysis skipped", t)
    } finally {
      if (rotatedBitmap != null && rotatedBitmap !== rawBitmap) {
        rawBitmap?.recycle()
      }
      imageProxy.close()
    }
  }

  fun close() {
    isClosed = true
    try {
      gestureRecognizer?.close()
      handLandmarker?.close()
    } catch (t: Throwable) {
      Log.w(TAG, "Error closing HandLandmarker", t)
    }
  }

  private fun createGestureRecognizer(context: Context): GestureRecognizer? {
    return tryBuildRecognizer(context, Delegate.GPU) ?: tryBuildRecognizer(context, Delegate.CPU)
  }

  private fun tryBuildRecognizer(context: Context, delegate: Delegate): GestureRecognizer? {
    return try {
      val baseOptions =
        BaseOptions.builder().setModelAssetPath(GESTURE_MODEL_ASSET_PATH).setDelegate(delegate).build()
      val options =
        GestureRecognizer.GestureRecognizerOptions.builder()
          .setBaseOptions(baseOptions)
          .setRunningMode(RunningMode.LIVE_STREAM)
          .setNumHands(2)
          .setMinHandDetectionConfidence(0.5f)
          .setMinHandPresenceConfidence(0.5f)
          .setMinTrackingConfidence(0.5f)
          .setResultListener { result: GestureRecognizerResult, _ ->
            if (!isClosed) {
              val landmarks = result.landmarks()
              val gestures = result.gestures()
              val hands =
                landmarks.mapIndexed { idx, hand ->
                  val top = gestures.getOrNull(idx)?.firstOrNull()
                  HandObservation(
                    landmarks = hand.map { lm -> HandLandmarkPoint(lm.x(), lm.y(), lm.z()) },
                    pose = HandPose.fromCategory(top?.categoryName()),
                    poseScore = top?.score() ?: 0f,
                  )
                }
              onHandsDetected(hands, result.timestampMs())
            }
          }
          .setErrorListener { error -> Log.w(TAG, "MediaPipe gesture error", error) }
          .build()
      GestureRecognizer.createFromOptions(context, options)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to initialize GestureRecognizer with $delegate", t)
      null
    }
  }

  private fun createHandLandmarker(context: Context): HandLandmarker? {
    return tryBuildLandmarker(context, Delegate.GPU) ?: tryBuildLandmarker(context, Delegate.CPU)
  }

  private fun tryBuildLandmarker(context: Context, delegate: Delegate): HandLandmarker? {
    return try {
      val baseOptions =
        BaseOptions.builder()
          .setModelAssetPath(MODEL_ASSET_PATH)
          .setDelegate(delegate)
          .build()
      val options =
        HandLandmarker.HandLandmarkerOptions.builder()
          .setBaseOptions(baseOptions)
          .setRunningMode(RunningMode.LIVE_STREAM)
          .setNumHands(2)
          .setMinHandDetectionConfidence(0.5f)
          .setMinHandPresenceConfidence(0.5f)
          .setMinTrackingConfidence(0.5f)
          .setResultListener { result: HandLandmarkerResult, _ ->
            if (!isClosed) {
              val hands =
                result.landmarks().map { hand ->
                  HandObservation(
                    hand.map { landmark -> HandLandmarkPoint(landmark.x(), landmark.y(), landmark.z()) }
                  )
                }
              onHandsDetected(hands, result.timestampMs())
            }
          }
          .setErrorListener { error -> Log.w(TAG, "MediaPipe error", error) }
          .build()
      HandLandmarker.createFromOptions(context, options)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to initialize HandLandmarker with $delegate", t)
      null
    }
  }

  companion object {
    private const val TAG = "MediaPipeHandAnalyzer"
    private const val MODEL_ASSET_PATH = "hand_landmarker.task"
    private const val GESTURE_MODEL_ASSET_PATH = "gesture_recognizer.task"
  }
}
