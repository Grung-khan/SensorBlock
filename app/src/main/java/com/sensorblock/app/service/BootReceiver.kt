package com.sensorblock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sensorblock.app.SensorBlockApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-applies the policy on boot and after an in-place update.
 *
 * The persisted primitives (sensor privacy, AppOps, device policy) are already
 * restored by the system by the time this runs; what this really covers is the
 * in-memory `cmd sensorservice set-uid-state` overrides and kicking Shizuku back
 * into shape.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        Log.i(TAG, "boot-ish event: $action")

        val app = SensorBlockApp.from(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val desired = app.policyStore.current()
                if (desired.isEmpty) {
                    Log.i(TAG, "no policy stored; nothing to re-assert")
                    return@launch
                }
                ReassertService.start(context)
            } catch (t: Throwable) {
                Log.e(TAG, "boot re-assert failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
