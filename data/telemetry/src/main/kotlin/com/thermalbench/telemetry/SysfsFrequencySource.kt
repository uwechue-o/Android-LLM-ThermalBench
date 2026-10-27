package com.thermalbench.telemetry

import com.thermalbench.domain.port.FrequencySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads `scaling_cur_freq` under `/sys/devices/system/cpu/cpuN/cpufreq` for
 * every core N. Vendor dependent and increasingly restricted; returns null
 * unless every core is readable, so a partial list never masquerades as the
 * whole SoC.
 */
@Singleton
class SysfsFrequencySource @Inject constructor() : FrequencySource {

    private val cpuDirs: List<File> by lazy {
        File("/sys/devices/system/cpu")
            .listFiles { f -> f.isDirectory && CPU_DIR.matches(f.name) }
            ?.sortedBy { it.name.removePrefix("cpu").toInt() }
            ?: emptyList()
    }

    override suspend fun readCpuFrequenciesKhz(): List<Long>? = readAll("scaling_cur_freq")

    override suspend fun readCpuMaxFrequenciesKhz(): List<Long>? = readAll("cpuinfo_max_freq")

    /**
     * The first readable GPU clock node among the vendors' known locations:
     * Qualcomm's kgsl for Adreno, Mali's own node, then any devfreq device
     * named for a GPU. Found once; null on a device that exposes none.
     */
    private val gpuClock: File? by lazy {
        val devfreq = File("/sys/class/devfreq")
            .listFiles { f -> GPU_DEVFREQ.containsMatchIn(f.name) }
            ?.map { File(it, "cur_freq") }
            .orEmpty()
        val mali = File("/sys/devices/platform")
            .listFiles { f -> f.name.endsWith(".mali") }
            ?.map { File(it, "cur_freq") }
            .orEmpty()
        (KNOWN_GPU_CLOCKS.map(::File) + mali + devfreq)
            .firstOrNull { file -> runCatching { file.readText().trim().toLong() > 0 }.getOrDefault(false) }
    }

    override suspend fun readGpuFrequencyKhz(): Long? = withContext(Dispatchers.IO) {
        val raw = gpuClock?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
            ?: return@withContext null
        // kgsl and devfreq report hertz, Mali's own node kilohertz. A GPU clock
        // is between 100 MHz and a few GHz, so the magnitude says which.
        if (raw >= HERTZ_THRESHOLD) raw / HZ_PER_KHZ else raw
    }

    /** True when some GPU clock node is readable on this device. */
    val gpuFrequencyReadable: Boolean get() = gpuClock != null

    private suspend fun readAll(fileName: String): List<Long>? = withContext(Dispatchers.IO) {
        if (cpuDirs.isEmpty()) return@withContext null
        val values = ArrayList<Long>(cpuDirs.size)
        for (dir in cpuDirs) {
            val value = runCatching {
                File(dir, "cpufreq/$fileName").readText().trim().toLong()
            }.getOrNull() ?: return@withContext null
            values += value
        }
        values
    }

    private companion object {
        val CPU_DIR = Regex("cpu[0-9]+")
        val GPU_DEVFREQ = Regex("gpu|kgsl|mali", RegexOption.IGNORE_CASE)
        val KNOWN_GPU_CLOCKS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        )
        const val HERTZ_THRESHOLD = 10_000_000L
        const val HZ_PER_KHZ = 1000L
    }
}
