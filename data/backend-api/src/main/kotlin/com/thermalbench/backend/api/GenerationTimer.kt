package com.thermalbench.backend.api

import com.thermalbench.domain.model.GenerationResult
import com.thermalbench.domain.port.MonotonicClock

/**
 * Turns a stream of tokens into the timings the engine records.
 *
 * Every adapter uses this rather than timing itself, so time-to-first-token
 * means the same thing on every backend: the interval from the call into the
 * runtime to the moment the first token is observable. Adapters that cannot
 * stream report the first token at the end of generation, which the timer
 * records honestly rather than estimating.
 */
class GenerationTimer(private val clock: MonotonicClock) {

    private var startNanos: Long = 0
    private var firstTokenNanos: Long? = null
    private val tokenNanos = ArrayList<Long>(INITIAL_CAPACITY)
    private val text = StringBuilder()
    private var tokenCount = 0

    fun start() {
        startNanos = clock.nanos()
        firstTokenNanos = null
        tokenNanos.clear()
        text.setLength(0)
        tokenCount = 0
    }

    /** Call as each token becomes observable. [piece] may be empty for a token with no text. */
    fun onToken(piece: String) {
        val now = clock.nanos()
        if (firstTokenNanos == null) firstTokenNanos = now
        tokenNanos += now
        text.append(piece)
        tokenCount++
    }

    /**
     * For non-streaming runtimes: records [count] tokens all arriving now.
     * Time-to-first-token then equals the whole generation, which is the truth
     * for such a backend and is what makes the difference visible in a report.
     */
    fun onBatch(fullText: String, count: Int) {
        val now = clock.nanos()
        if (firstTokenNanos == null) firstTokenNanos = now
        text.append(fullText)
        tokenCount += count
        repeat(count) { tokenNanos += now }
    }

    /**
     * [endNanos] defaults to now. Pass the timestamp of the last token that
     * counts when a runtime keeps generating past the requested length: the
     * measurement then covers exactly the tokens the workload asked for, and
     * anything the backend produces afterwards is outside the window.
     */
    fun finish(
        promptTokenCount: Int,
        outputTokenCount: Int = tokenCount,
        endNanos: Long = clock.nanos(),
    ): GenerationResult = GenerationResult(
        startElapsedNanos = startNanos,
        firstTokenElapsedNanos = firstTokenNanos,
        endElapsedNanos = endNanos,
        promptTokenCount = promptTokenCount,
        outputTokenCount = outputTokenCount,
        text = text.toString(),
        tokenElapsedNanos = tokenNanos.toList(),
    )

    val observedTokenCount: Int get() = tokenCount

    /** Monotonic timestamp of the [n]th token (1-based), or null if it never arrived. */
    fun timestampOfToken(n: Int): Long? = tokenNanos.getOrNull(n - 1)

    /** Text accumulated so far. */
    fun textSoFar(): String = text.toString()

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}
