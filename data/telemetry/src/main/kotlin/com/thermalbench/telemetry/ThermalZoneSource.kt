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
                if (!isTemperatureZone(name, celsius)) return@mapNotNull null
                Zone(name, temp)
            }
            .orEmpty()
    }

    /** Names of the readable sensors, in kernel order; empty when none are readable. */
    suspend fun names(): List<String> = withContext(Dispatchers.IO) { zones.map { it.name } }

    suspend fun read(): List<SensorTemperature>? = withContext(Dispatchers.IO) {
        if (zones.isEmpty()) return@withContext null
        // A zone that stops answering mid-run is left out of that reading
        // rather than reported as a temperature it did not give. So is one
        // that answers with a sentinel: OnePlus 8T modem zones pass discovery,
        // then report -273 while the modem is idle.
        zones.mapNotNull { zone ->
            readCelsius(zone.temp)
                ?.takeIf { isTemperatureZone(zone.name, it) }
                ?.let { SensorTemperature(zone.name, it) }
        }
            .ifEmpty { null }
    }

    private fun readCelsius(file: File): Float? {
        val raw = runCatching { file.readText().trim().toLong() }.getOrNull() ?: return null
        // Millidegrees on almost every kernel; a few drivers report whole degrees.
        return if (abs(raw) >= MILLI_THRESHOLD) raw / MILLI else raw.toFloat()
    }

    internal companion object {
        private const val THERMAL_ROOT = "/sys/class/thermal"
        private val ZONE_DIR = Regex("thermal_zone[0-9]+")
        private const val MILLI = 1000f
        private const val MILLI_THRESHOLD = 1000L
        private val PLAUSIBLE_CELSIUS = -40f..150f

        /** The reading an unpopulated sensor reports forever on Qualcomm kernels. */
        private const val UNPOPULATED_CELSIUS = -40f

        /**
         * Zones that sit under /sys/class/thermal but measure something else:
         * `soc` is the battery charge in percent, `*-lvl*` zones are battery
         * current-limit (bcl), current (ibat) and voltage (vbat, vph) trip
         * levels. Their values fall inside the plausible range, so the name is
         * the only way to tell them from a thermometer.
         */
        private val NON_TEMPERATURE = Regex("""^soc$|-lvl\d*$""", RegexOption.IGNORE_CASE)

        /** Whether a zone, read once at discovery, is a temperature sensor worth recording. */
        fun isTemperatureZone(name: String, celsius: Float): Boolean =
            celsius in PLAUSIBLE_CELSIUS &&
                celsius != UNPOPULATED_CELSIUS &&
                !NON_TEMPERATURE.containsMatchIn(name)
    }
}
