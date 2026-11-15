package com.example.alert

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-speech for cognitive-load alerts. Speaks at full volume on the alarm stream and reports
 * speaking state so the voice recogniser can mute itself (no self-triggered commands).
 */
class AlertAnnouncer(context: Context, private val onSpeakingChanged: (Boolean) -> Unit) {
  private var tts: TextToSpeech? = null
  @Volatile private var ready = false
  private val ids = AtomicInteger(0)
  private var pending: String? = null

  init {
    tts =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          tts?.language = Locale.US
          tts?.setSpeechRate(1.05f)
          tts?.setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ALARM)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) = onSpeakingChanged(true)

              override fun onDone(utteranceId: String?) = onSpeakingChanged(false)

              @Deprecated("Deprecated in Java")
              override fun onError(utteranceId: String?) = onSpeakingChanged(false)
            }
          )
          ready = true
          pending?.let { speak(it) }
          pending = null
        } else {
          Log.w(TAG, "TextToSpeech init failed: $status")
        }
      }
  }

  fun speak(text: String) {
    val engine = tts
    if (!ready || engine == null) {
      pending = text
      return
    }
    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
    engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "alert-${ids.incrementAndGet()}")
  }

  fun stop() {
    tts?.stop()
    onSpeakingChanged(false)
  }

  fun shutdown() {
    tts?.shutdown()
    tts = null
    ready = false
  }

  companion object {
    private const val TAG = "AlertAnnouncer"
  }
}
