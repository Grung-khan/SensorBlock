package com.sensorblock.app.debug

import android.content.Context
import android.util.Log
import com.sensorblock.app.SensorBlockApp
import com.sensorblock.app.domain.DesiredPolicy
import com.sensorblock.app.domain.SelfTest
import java.io.File

/**
 * Shared body of the debug harness. Lives in the `debug` source set only and is
 * called by [DebugReceiver] and [DebugActivity]. Drives the *real*
 * [com.sensorblock.app.domain.PolicyEngine] + [SelfTest].
 *
 * Results are both logged (tag [TAG]) and appended to
 * `<externalFilesDir>/debug.log`, because the target device's ROM suppresses
 * app logcat output. Pull with:
 *   adb shell cat /sdcard/Android/data/<pkg>/files/debug.log
 */
object DebugActions {
    const val TAG = "SensorBlockDbg"

    private val buffer = mutableListOf<String>()

    private fun emit(context: Context, line: String) {
        Log.i(TAG, line)
        synchronized(buffer) { buffer += line }
        runCatching { File(context.filesDir, "debug.log").appendText(line + "\n") }
        runCatching {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) File(dir, "debug.log").appendText(line + "\n")
        }
    }

    suspend fun run(
        context: Context,
        action: String?,
        blockAll: Boolean = false,
        blockCamera: Boolean = false,
        blockMic: Boolean = false,
    ) {
        val app = context.applicationContext as SensorBlockApp
        emit(context, "=== ${System.currentTimeMillis()} action=$action all=$blockAll camera=$blockCamera mic=$blockMic ===")
        try {
            when (action) {
                ACTIONS.POLICY -> {
                    val desired = DesiredPolicy(
                        blockAllSensors = blockAll,
                        blockCamera = blockCamera,
                        blockMicrophone = blockMic,
                    )
                    emit(context, "requested policy=$desired")
                    app.policyStore.update { desired }
                    val report = app.policyEngine.apply(desired)
                    report.actions.forEach {
                        emit(
                            context,
                            "action ${it.target}: desired=${it.desired} previous=${it.previous} " +
                                "success=${it.success} ${it.detail ?: ""}",
                        )
                    }
                    report.issues.forEach {
                        emit(context, "issue [${it.severity}] ${it.title}: ${it.detail}")
                    }
                    emit(context, "observed=${report.observed}")
                }

                ACTIONS.CAPS -> emit(context, "capabilities=${app.policyEngine.capabilities()}")

                ACTIONS.OBSERVE -> {
                    val desired = app.policyStore.current()
                    emit(context, "observed=${app.policyEngine.observe(desired)}")
                }

                ACTIONS.SELFTEST -> SelfTest(context, app.policyEngine).runAll().forEach {
                    emit(context, "probe ${it.name} [${it.verdict}] ${it.detail} | ${it.evidence ?: ""}")
                }

                else -> emit(context, "unknown action $action")
            }
        } catch (t: Throwable) {
            emit(context, "debug action failed: ${t.javaClass.name}: ${t.message}")
            Log.e(TAG, "debug action failed", t)
        }
    }

    object ACTIONS {
        const val POLICY = "com.sensorblock.app.debug.POLICY"
        const val CAPS = "com.sensorblock.app.debug.CAPS"
        const val OBSERVE = "com.sensorblock.app.debug.OBSERVE"
        const val SELFTEST = "com.sensorblock.app.debug.SELFTEST"
    }

    const val EXTRA_ALL = "blockAll"
    const val EXTRA_CAMERA = "blockCamera"
    const val EXTRA_MIC = "blockMicrophone"
}
