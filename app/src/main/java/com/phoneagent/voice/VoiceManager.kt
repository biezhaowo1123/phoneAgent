package com.phoneagent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

class VoiceManager(private val context: Context) {

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady

    private var tts: TextToSpeech? = null
    var ttsSpeed: Float = 1.0f
    var ttsPitch: Float = 1.0f

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tts?.language = Locale.CHINESE } catch (_: Exception) {}
                    tts?.setSpeechRate(ttsSpeed)
                    tts?.setPitch(ttsPitch)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
                        override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { _isSpeaking.value = false }
                    })
                    _ttsReady.value = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VoiceManager", "TTS init failed", e)
        }
    }

    fun speak(text: String) {
        if (_ttsReady.value && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun updateTtsSettings(speed: Float = ttsSpeed, pitch: Float = ttsPitch) {
        ttsSpeed = speed
        ttsPitch = pitch
        tts?.setSpeechRate(speed)
        tts?.setPitch(pitch)
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }
}
