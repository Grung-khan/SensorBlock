package com.sensorblock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.sensorblock.app.domain.AppInventory
import com.sensorblock.app.domain.PolicyEngine
import com.sensorblock.app.domain.PolicyStore
import com.sensorblock.app.domain.SensorInventory

/**
 * Manual dependency container. A handful of singletons shared by the UI and the
 * re-assert service; no DI framework needed for an app this size.
 */
class SensorBlockApp : Application() {

    val policyStore: PolicyStore by lazy { PolicyStore(this) }
    val policyEngine: PolicyEngine by lazy { PolicyEngine(this) }
    val sensorInventory: SensorInventory by lazy { SensorInventory(this) }
    val appInventory: AppInventory by lazy { AppInventory(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_REASSERT,
            getString(R.string.notif_channel_reassert),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_reassert_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_REASSERT = "reassert"

        fun from(context: Context): SensorBlockApp =
            context.applicationContext as SensorBlockApp
    }
}
