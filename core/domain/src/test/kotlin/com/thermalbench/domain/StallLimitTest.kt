package com.thermalbench.domain

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.usecase.withStallLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StallLimitTest {

    @Test
    fun `a generation that answers in time returns its result`() = runBlocking {
        assertThat(withStallLimit(5.seconds, Dispatchers.IO) { 42 }).isEqualTo(42)
    }

    @Test
    fun `a generation blocked in uninterruptible code is abandoned at the limit`() = runBlocking {
        val never = CountDownLatch(1)
        val started = System.nanoTime()
        // Blocks its thread the way a stuck native call does, ignoring cancellation.
        val result = withStallLimit(200.milliseconds, Dispatchers.IO) {
            never.awaitUninterruptibly()
            "unreachable"
        }
        assertThat(result).isNull()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
        never.countDown()
    }

    private fun CountDownLatch.awaitUninterruptibly() {
        while (true) {
            try {
                await()
                return
            } catch (_: InterruptedException) {
                // Keep blocking, as native code would.
            }
        }
    }
}
