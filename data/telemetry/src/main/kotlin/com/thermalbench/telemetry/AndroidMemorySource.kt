package com.thermalbench.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.thermalbench.domain.model.MemorySample
import com.thermalbench.domain.port.MemorySource
import com.thermalbench.domain.port.MonotonicClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process RSS from `/proc/self/statm` (cheap, exact), falling back to PSS from
 * [Debug.MemoryInfo] when procfs is unreadable. System-wide figures from
 * [ActivityManager.getMemoryInfo].
 */
@Singleton
class AndroidMemorySource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
) : MemorySource {

    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    private val pageSize: Long = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }
        .getOrDefault(DEFAULT_PAGE_SIZE)

    override suspend fun snapshot(): MemorySample = withContext(Dispatchers.IO) {
        val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        MemorySample(
            elapsedNanos = clock.nanos(),
            rssBytes = readRssBytes() ?: pssBytes(),
            availableSystemBytes = info.availMem,
            lowMemory = info.lowMemory,
        )
    }

    private fun readRssBytes(): Long? = runCatching {
        val fields = File("/proc/self/statm").readText().trim().split(' ')
        fields[1].toLong() * pageSize
    }.getOrNull()

    private fun pssBytes(): Long {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return memInfo.totalPss.toLong() * 1024L
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 4096L
    }
}
