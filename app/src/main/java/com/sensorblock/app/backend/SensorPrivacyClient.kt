package com.sensorblock.app.backend

import android.hardware.ISensorPrivacyManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Thin, typed client for the `sensor_privacy` system service.
 *
 * It is the single most important primitive in SensorBlock:
 *  - [setAllSensorsBlocked] is the device-wide kill switch: every physical
 *    sensor stops delivering data, the camera is refused, and every
 *    `AudioRecord` client is silenced (AGENTS.md §3.1).
 *  - [setToggleEnabled] blocks the camera or the microphone on its own.
 *
 * All state lives in the OS (`/data/system/sensor_privacy*.xml`) and survives
 * reboots, so this class is stateless beyond its binder handle.
 */
class SensorPrivacyClient private constructor(
    private val service: ISensorPrivacyManager,
) {

    /** Reads the device-wide all-sensor switch. */
    fun isAllSensorsBlocked(): Boolean = service.isSensorPrivacyEnabled

    /** Flips the device-wide all-sensor switch (sensors + camera + mic silencing). */
    fun setAllSensorsBlocked(blocked: Boolean) {
        service.setSensorPrivacy(blocked)
    }

    /** Reads the per-sensor privacy toggle for [sensor]. */
    fun isToggleEnabled(sensor: Int): Boolean =
        service.isToggleSensorPrivacyEnabled(TOGGLE_TYPE_SOFTWARE, sensor)

    /** Sets the per-sensor privacy toggle for [sensor]. */
    fun setToggleEnabled(sensor: Int, enabled: Boolean) {
        service.setToggleSensorPrivacy(USER_CURRENT, SOURCE_SHELL, sensor, enabled)
    }

    /** Whether the device implements a software toggle for [sensor] at all. */
    fun supportsToggle(sensor: Int): Boolean = try {
        service.supportsSensorToggle(TOGGLE_TYPE_SOFTWARE, sensor)
    } catch (t: Throwable) {
        Log.w(TAG, "supportsSensorToggle($sensor) failed", t)
        false
    }

    companion object {
        private const val TAG = "SensorPrivacyClient"

        /** `SensorPrivacyManager.Sensors.MICROPHONE`. */
        const val SENSOR_MICROPHONE = 1

        /** `SensorPrivacyManager.Sensors.CAMERA`. */
        const val SENSOR_CAMERA = 2

        /** `SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE`. */
        const val TOGGLE_TYPE_SOFTWARE = 0

        /**
         * `SensorPrivacyManager.Sources.SHELL`. Only used for the toggle
         * interaction log; it is not permission-relevant.
         */
        const val SOURCE_SHELL = 4

        /**
         * `android.os.UserHandle.USER_CURRENT` is `@hide` in the public SDK, so it
         * cannot be referenced directly. The value `-2` is the documented platform
         * constant (aosp `core/java/android/os/UserHandle.java:59`). The service
         * resolves it to the active user in `setToggleSensorPrivacy`, so this avoids
         * the `@hide` `UserHandle.myUserId()` entirely.
         */
        private const val USER_CURRENT = -2

        private const val SERVICE_NAME = "sensor_privacy"

        /**
         * Resolves the service through Shizuku so the call arrives with uid 2000
         * (shell) — the only non-root identity holding `MANAGE_SENSOR_PRIVACY`.
         *
         * Transaction codes come from the mirrored AIDL
         * (`android/hardware/ISensorPrivacyManager.aidl`); nothing here
         * hard-codes a transaction number.
         */
        fun connect(): SensorPrivacyClient? {
            return try {
                val service = SystemServiceHelper.getSystemService(SERVICE_NAME)
                if (service == null) {
                    Log.w(TAG, "$SERVICE_NAME not present on this device")
                    return null
                }
                wrap(ISensorPrivacyManager.Stub.asInterface(ShizukuBinderWrapper(service)))
            } catch (t: Throwable) {
                Log.e(TAG, "could not resolve $SERVICE_NAME", t)
                null
            }
        }

        fun wrap(service: ISensorPrivacyManager): SensorPrivacyClient =
            SensorPrivacyClient(service)
    }
}
