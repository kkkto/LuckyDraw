package com.example.luckydraw

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Owns one Pleos speech session while the activity is visible. */
class FortuneVoiceGuide(
    context: Context,
    private val onStateChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("fortune_voice", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var sdk: TextToSpeech? = null
    private var eventListener: EventListener? = null
    private var initialized = false
    private var foreground = false
    private var pendingText: String? = null

    val enabled: Boolean
        get() = preferences.getBoolean("enabled", true)

    var hasError = false
        private set

    fun onStart() {
        foreground = true
        if (enabled) ensureStarted()
    }

    fun onStop() {
        foreground = false
        cancel()
        release()
    }

    fun speak(text: String) {
        if (!enabled) return
        pendingText = text
        if (foreground) {
            ensureStarted()
            speakPending()
        }
    }

    fun cancel() {
        pendingText = null
        runCatching { sdk?.stop() }
    }

    fun setEnabled(value: Boolean, currentText: String?) {
        preferences.edit().putBoolean("enabled", value).apply()
        hasError = false
        if (value) {
            pendingText = currentText
            if (foreground) {
                ensureStarted()
                speakPending()
            }
        } else {
            cancel()
            release()
        }
        onStateChanged()
    }

    fun retry(text: String) {
        if (!enabled) return
        hasError = false
        onStateChanged()
        speak(text)
    }

    private fun ensureStarted() {
        if (!foreground || !enabled || sdk != null) return
        if (hasError) {
            hasError = false
            onStateChanged()
        }
        val instance = runCatching { TextToSpeech(appContext, Mode.ON_DEVICE) }
            .getOrElse {
                hasError = true
                pendingText = null
                onStateChanged()
                return
            }
        sdk = instance
        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() = Unit
            override fun onStop() = Unit
            override fun onError(errorType: ErrorType) {
                main.post { fail(instance) }
            }
        }
        eventListener = listener
        runCatching {
            instance.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        if (sdk !== instance || !foreground || !enabled) return@post
                        if (runCatching { instance.setLocale(Locale.KOREA) }.isFailure) {
                            fail(instance)
                            return@post
                        }
                        initialized = true
                        speakPending()
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post { fail(instance) }
                }
            })
            instance.addEventListener(listener)
        }.onFailure { fail(instance) }
    }

    private fun speakPending() {
        if (!foreground || !enabled || !initialized) return
        val instance = sdk ?: return
        val text = pendingText ?: return
        pendingText = null
        runCatching { instance.speak(text) }.onFailure { fail(instance) }
    }

    private fun fail(instance: TextToSpeech) {
        if (sdk !== instance) return
        pendingText = null
        hasError = true
        release()
        onStateChanged()
    }

    private fun release() {
        val instance = sdk ?: return
        sdk = null
        initialized = false
        eventListener?.let { listener ->
            runCatching { instance.removeEventListener(listener) }
        }
        eventListener = null
        runCatching { instance.release() }
    }
}
