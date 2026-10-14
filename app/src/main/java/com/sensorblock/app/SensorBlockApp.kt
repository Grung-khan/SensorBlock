package com.sensorblock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.sensorblock.app.domain.AppInventory
import com.sensorblock.app.domain.PolicyEngine
import com.sensorblock.app.domain.PolicyStore
import com.sensorblock.app.domain.SensorInventory
import org.lsposed.hiddenapibypass.HiddenApiBypass

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
        exemptHiddenApis()
        createNotificationChannels()
    }

    /**
     * The sensor-privacy service is driven through a hand-mirrored copy of the
     * `@hide` `android.hardware.ISensorPrivacyManager` AIDL. On API 28+ the runtime
     * blocks references to non-SDK interfaces from an app process, so the mirrored
     * `Stub.asInterface` call in [com.sensorblock.app.backend.SensorPrivacyClient]
     * throws before the Shizuku-wrapped binder is ever used. Exempting the hidden
     * APIs here restores the call; the privileged identity still comes from Shizuku.
     */
    private fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }.onFailure {
            Log.e(TAG, "could not add hidden-API exemptions", it)
        }
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
        private const val TAG = "SensorBlockApp"
        const val CHANNEL_REASSERT = "reassert"

        fun from(context: Context): SensorBlockApp =
            context.applicationContext as SensorBlockApp
    }
}
