package com.sensorblock.app.debug

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-build-only trigger reachable via `am start`. This exists because the
 * target device's ROM drops manifest-broadcast deliveries to the app, but
 * activity starts work. Runs the same [DebugActions] and finishes.
 */
class DebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        val all = intent.getBooleanExtra(DebugActions.EXTRA_ALL, false)
        val camera = intent.getBooleanExtra(DebugActions.EXTRA_CAMERA, false)
        val mic = intent.getBooleanExtra(DebugActions.EXTRA_MIC, false)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DebugActions.run(applicationContext, action, all, camera, mic)
            } finally {
                finish()
            }
        }
    }
}
