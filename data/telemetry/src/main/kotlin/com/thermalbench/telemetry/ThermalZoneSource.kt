package com.thermalbench.telemetry

import com.thermalbench.domain.model.SensorTemperature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Every temperature sensor the kernel exposes under `/sys/class/thermal` that
 * this process may read: CPU clusters, GPU, modem, skin and battery, named as
 * the vendor names them.
 *
 * Android offers apps no API for individual sensors; `PowerManager` reports
 * only the aggregate status and headroom. Which zones are readable depends on
 * the vendor's SELinux policy, so the set is discovered once, and a device
 * that allows none reports null rather than an empty reading that looks like
 * a cold phone.
 */
@Singleton
class ThermalZoneSource @Inject constructor() {

    private class Zone(val name: String, val temp: File)

    private val zones: List<Zone> by lazy {
        File(THERMAL_ROOT)
            .listFiles { f -> ZONE_DIR.matches(f.name) }
            ?.sortedBy { it.name.removePrefix("thermal_zone").toInt() }
            ?.mapNotNull { dir ->
                val name = runCatching { File(dir, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
                val temp = File(dir, "temp")
                // Readable now, and a plausible temperature: some zones exist
                // only as placeholders and report 0 or a sentinel forever.
                val celsius = readCelsius(temp) ?: return@mapNotNull null
                if (celsius !in PLAUSIBLE_CELSIUS) return@mapNotNull null
                Zone(name, temp)
            }
            .orEmpty()
    }

    /** Names of the readable sensors, in kernel order; empty when none are readable. */
    suspend fun names(): List<String> = withContext(Dispatchers.IO) { zones.map { it.name } }

    suspend fun read(): List<SensorTemperature>? = withContext(Dispatchers.IO) {
        if (zones.isEmpty()) return@withContext null
        // A zone that stops answering mid-run is left out of that reading
        // rather than reported as a temperature it did not give.
        zones.mapNotNull { zone -> readCelsius(zone.temp)?.let { SensorTemperature(zone.name, it) } }
            .ifEmpty { null }
    }

    private fun readCelsius(file: File): Float? {
        val raw = runCatching { file.readText().trim().toLong() }.getOrNull() ?: return null
        // Millidegrees on almost every kernel; a few drivers report whole degrees.
        return if (abs(raw) >= MILLI_THRESHOLD) raw / MILLI else raw.toFloat()
    }

    private companion object {
        const val THERMAL_ROOT = "/sys/class/thermal"
        val ZONE_DIR = Regex("thermal_zone[0-9]+")
        const val MILLI = 1000f
        const val MILLI_THRESHOLD = 1000L
        val PLAUSIBLE_CELSIUS = -40f..150f
    }
}
