package com.thermalbench.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.thermalbench.domain.model.DeviceDescriptor
import com.thermalbench.domain.port.DeviceProfiler
import com.thermalbench.domain.port.FrequencySource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Collected once per run. Everything here is public `Build` data plus a pseudonymous id. */
@Singleton
class AndroidDeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val frequency: FrequencySource,
    private val thermal: PowerManagerThermalSource,
    private val sensors: ThermalZoneSource,
) : DeviceProfiler {

    override suspend fun profile(): DeviceDescriptor {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val maxFreq = frequency.readCpuMaxFrequenciesKhz()
        val curFreq = frequency.readCpuFrequenciesKhz()
        val hasSoc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        return DeviceDescriptor(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            socManufacturer = if (hasSoc) Build.SOC_MANUFACTURER.takeUnless { it == Build.UNKNOWN } else null,
            socModel = if (hasSoc) Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN } else null,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = mem.totalMem,
            cpuMaxFrequenciesKhz = maxFreq,
            cpuFrequencyReadable = curFreq != null,
            thermalHeadroomSupported = thermal.headroomSupported,
            anonymousDeviceId = anonymousId(),
            gpuFrequencyReadable = frequency.readGpuFrequencyKhz() != null,
            thermalSensorNames = sensors.names(),
        )
    }

    /**
     * SHA-256 of the build fingerprint and the app-scoped ANDROID_ID, truncated.
     * Stable for one install on one device; not linkable across apps.
     */
    @Suppress("HardwareIds")
    private fun anonymousId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${Build.FINGERPRINT}|$androidId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(ANON_ID_LENGTH)
    }

    private companion object {
        const val ANON_ID_LENGTH = 16
    }
}
