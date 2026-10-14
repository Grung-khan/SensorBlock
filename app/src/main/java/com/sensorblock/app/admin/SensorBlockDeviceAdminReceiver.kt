package com.sensorblock.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Backing receiver for SensorBlock's device-admin / device-owner privileges.
 *
 * Why it exists (AGENTS.md §3.3): a device admin can call
 * `DevicePolicyManager.setCameraDisabled`, which is a *hard* camera block
 * enforced by `CameraService` and is independent of Shizuku. A device **owner**
 * (installed with `adb shell dpm set-device-owner`) additionally gets the
 * `DISALLOW_CAMERA` and `DISALLOW_UNMUTE_MICROPHONE` user restrictions.
 *
 * A device owner does **not** get `MANAGE_SENSOR_PRIVACY`, so the all-sensor
 * switch always has to go through Shizuku.
 */
class SensorBlockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
    }

    companion object {
        private const val TAG = "SensorBlockAdmin"
    }
}
