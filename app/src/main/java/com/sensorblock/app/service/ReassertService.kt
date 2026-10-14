package com.sensorblock.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sensorblock.app.MainActivity
import com.sensorblock.app.R
import com.sensorblock.app.SensorBlockApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Re-asserts the persisted policy at the moments the OS forgets volatile state:
 * after a reboot, after a package change, and whenever Shizuku (re)connects.
 *
 * Only *volatile* primitives actually need this (AGENTS.md §3.6): the
 * sensor-privacy switches, AppOps and device-policy restrictions all persist on
 * their own. `cmd sensorservice set-uid-state` is in-memory only, which is why
 * the service keeps running while any per-app sensor block is engaged.
 */
class ReassertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reassertJob: Job? = null

    private var started = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received; re-asserting policy")
        reassert()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died; waiting for reconnect")
    }

    override fun onCreate() {
        super.onCreate()
        if (isShizukuUsable()) {
            runCatching {
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startForegroundCompat()
        }
        reassert()
        return START_STICKY
    }

    private fun reassert() {
        if (reassertJob?.isActive == true) return
        reassertJob = scope.launch {
            val app = SensorBlockApp.from(this@ReassertService)
            val desired = app.policyStore.current()
            val report = app.policyEngine.apply(desired)
            app.policyStore.noteApplied(System.currentTimeMillis())
            if (report.failures.isNotEmpty()) {
                Log.w(TAG, "re-assert had failures: " + report.failures.joinToString { it.target })
            }
            // Nothing volatile is engaged -> the OS already persists everything
            // we asked for, so there is no reason to hold a foreground service.
            val needsResident = desired.appSensorIdle.values.any { it }
            if (!needsResident && !desired.isEmpty) {
                Log.i(TAG, "policy is fully persistent; stopping the re-assert service")
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                0,
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SensorBlockApp.CHANNEL_REASSERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_reassert_title))
            .setContentText(getString(R.string.notif_reassert_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun isShizukuUsable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override fun onDestroy() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ReassertService"
        private const val NOTIFICATION_ID = 0x5342

        fun start(context: android.content.Context) {
            val intent = Intent(context, ReassertService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start re-assert service", it) }
        }
    }
}
