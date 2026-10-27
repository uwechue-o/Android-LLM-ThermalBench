package com.thermalbench.runner

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Speaks run progress, so an operator can follow a cool-down with the screen
 * off: turning the screen on to look would itself restart the precondition
 * window.
 *
 * Audio goes out as navigation guidance, the way a satnav prompt does: it plays
 * with the ringer on silent and follows the media volume. The engine is
 * started only when there is something to say and [release]d before any
 * iteration runs, so nothing of it is resident while the device is measured.
 * A device without a speech engine stays silent rather than failing the run.
 */
class SpokenCues(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private val utteranceIds = AtomicInteger()
    private val waiters = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Queues [text]; it is spoken once the engine is up. */
    @Synchronized
    fun say(text: String) {
        if (ready) speak(text) else pending.addLast(text).also { start() }
    }

    /** Says [text] and waits until it has been heard, so a shutdown cannot cut it off. */
    suspend fun sayAndWait(text: String, timeout: Duration = SPEECH_TIMEOUT) {
        val done = CompletableDeferred<Unit>()
        synchronized(this) {
            val id = "cue-${utteranceIds.incrementAndGet()}"
            waiters[id] = done
            if (ready) speak(text, id) else pending.addLast("$id\u0000$text").also { start() }
        }
        // A missing or stuck engine must not hold the service open.
        withTimeoutOrNull(timeout) { done.await() }
    }

    @Synchronized
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pending.clear()
        waiters.values.forEach { it.complete(Unit) }
        waiters.clear()
    }

    private fun start() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "no speech engine; cues stay silent")
            release()
            return
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Required override on older API levels")
            override fun onError(utteranceId: String?) = finished(utteranceId)
        })
        ready = true
        while (pending.isNotEmpty()) {
            val queued = pending.removeFirst()
            val id = queued.substringBefore('\u0000', missingDelimiterValue = "")
            speak(queued.substringAfter('\u0000'), id.ifEmpty { null })
        }
    }

    private fun speak(text: String, id: String? = null) {
        // Logged as it is handed to the engine, so what the phone said can be
        // read back afterwards rather than reconstructed from the readings.
        Log.i(TAG, "said \"$text\"")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id ?: "cue-${utteranceIds.incrementAndGet()}")
    }

    @Synchronized
    private fun finished(utteranceId: String?) {
        waiters.remove(utteranceId)?.complete(Unit)
    }

    private companion object {
        const val TAG = "ThermalBench"
        val SPEECH_TIMEOUT = 10.seconds
    }
}
