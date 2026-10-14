package com.sensorblock.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-build-only harness so enforcement can be driven from `adb` without
 * tapping the UI. Delegates to [DebugActions]; see that file for usage.
 * Never part of a release build.
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DebugActions.run(
                    context = context,
                    action = intent.action,
                    blockAll = intent.getBooleanExtra(DebugActions.EXTRA_ALL, false),
                    blockCamera = intent.getBooleanExtra(DebugActions.EXTRA_CAMERA, false),
                    blockMic = intent.getBooleanExtra(DebugActions.EXTRA_MIC, false),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
