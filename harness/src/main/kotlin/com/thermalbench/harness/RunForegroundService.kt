package com.thermalbench.harness

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the harness process in the foreground for the length of a run. It does
 * no work of its own; the benchmark runs on the instrumentation thread.
 * [HarnessApplication] starts it when the process starts.
 *
 * `am instrument` leaves the process ranked as background, and vendor
 * freezers act on that rank. (Keeping the CPU awake is a separate matter:
 * [HeadlessRunner] holds a wake lock for that.) OnePlus's HANS froze the
 * harness mid-generation about 25 seconds after the screen changed state, even
 * with the package on the battery-optimisation whitelist, and the run hung with
 * no error. A process that holds a foreground service is not frozen, but the
 * system only grants one while the app is not background-restricted.
 */
class RunForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.harness_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.harness_name))
            .setContentText("Benchmark running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "ThermalBench"
        private const val CHANNEL_ID = "harness-run"
        private const val NOTIFICATION_ID = 1

        /**
         * Puts the process in the foreground. A device that refuses (the app is
         * background-restricted, say) still gets the run, with a warning that it
         * may be frozen.
         */
        fun start(context: Context) {
            // A background-restricted app's startForeground() is refused with
            // nothing but a system log line, so say it here, with the fix.
            if (context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) {
                Log.w(
                    TAG,
                    "the harness is background-restricted, so it cannot hold the foreground and the " +
                        "vendor may freeze this run. Lift it with: adb shell cmd appops set " +
                        "${context.packageName} RUN_ANY_IN_BACKGROUND allow",
                )
            }
            runCatching {
                context.startForegroundService(Intent(context, RunForegroundService::class.java))
            }.onFailure {
                Log.w(TAG, "could not hold the foreground; the vendor may freeze this run: $it")
            }
        }
    }
}
