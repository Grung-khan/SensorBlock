package com.sensorblock.app.backend

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.sensorblock.app.admin.SensorBlockDeviceAdminReceiver
import com.sensorblock.app.domain.BackendKind
import com.sensorblock.app.domain.Capabilities
import com.sensorblock.app.domain.DesiredPolicy
import com.sensorblock.app.domain.ObservedState
import com.sensorblock.app.domain.ReconcileReport

/**
 * The layered hardening backend (AGENTS.md §3.3).
 *
 * What it can do depends on how far the app has been elevated:
 *
 * | level          | camera                                        | microphone                       |
 * |----------------|-----------------------------------------------|----------------------------------|
 * | device admin   | `setCameraDisabled` (CameraService level)      | –                                |
 * | device owner   | + `DISALLOW_CAMERA` user restriction           | `DISALLOW_UNMUTE_MICROPHONE`     |
 *
 * Both are enforced by the system itself, so they keep working even while
 * Shizuku is down. That is the point of layering: the camera stays blocked by
 * two independent mechanisms when both are available.
 */
class DevicePolicyBackend(private val context: Context) : PrivilegedBackend {

    override val kind: BackendKind = BackendKind.DEVICE_POLICY

    private val dpm: DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private val admin = ComponentName(context, SensorBlockDeviceAdminReceiver::class.java)

    fun isAdminActive(): Boolean = runCatching { dpm?.isAdminActive(admin) == true }
        .getOrDefault(false)

    fun isDeviceOwner(): Boolean = runCatching {
        dpm?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    /** True when the app can call the admin/owner-only camera APIs. */
    private fun hasCameraPrivilege(): Boolean = isAdminActive() || isDeviceOwner()

    /** True when the app can add user restrictions (owner only on Android 13). */
    private fun hasRestrictionPrivilege(): Boolean = isDeviceOwner()

    // ---------------------------------------------------------------- probe ---

    override suspend fun capabilities(): Capabilities = Capabilities(
        deviceAdminActive = isAdminActive(),
        deviceOwner = isDeviceOwner(),
    )

    // -------------------------------------------------------------- observe ---

    override suspend fun observe(): ObservedState = ObservedState(
        cameraAdminDisabled = if (hasCameraPrivilege()) {
            runCatching { dpm?.getCameraDisabled(admin) }.getOrNull()
        } else {
            null
        },
        cameraUserRestriction = readRestriction(UserManager.DISALLOW_CAMERA),
        microphoneRestricted = readRestriction(UserManager.DISALLOW_UNMUTE_MICROPHONE),
        readAtEpochMs = System.currentTimeMillis(),
    )

    private fun readRestriction(key: String): Boolean? {
        if (!hasRestrictionPrivilege()) return null
        return runCatching {
            dpm?.getUserRestrictions(admin)?.getBoolean(key)
        }.getOrNull()
    }

    // ------------------------------------------------------------ reconcile ---

    override suspend fun reconcile(
        desired: DesiredPolicy,
        observed: ObservedState,
    ): List<ReconcileReport.Action> {
        val actions = mutableListOf<ReconcileReport.Action>()

        // Hard camera block. Applies whenever camera blocking is requested by
        // *any* camera toggle, so the layers reinforce each other.
        val wantCameraBlocked = desired.blockCamera || desired.blockAllSensors
        actions += adminAction(
            target = "Camera disabled by device policy",
            desired = wantCameraBlocked,
            previous = observed.cameraAdminDisabled,
            available = hasCameraPrivilege(),
            unavailableReason = "SensorBlock is not an active device admin",
        ) { blocked -> dpm?.setCameraDisabled(admin, blocked) }

        if (hasRestrictionPrivilege()) {
            actions += adminAction(
                target = "Camera user restriction (no_camera)",
                desired = wantCameraBlocked,
                previous = observed.cameraUserRestriction,
                available = true,
                unavailableReason = null,
            ) { blocked ->
                setRestriction(UserManager.DISALLOW_CAMERA, blocked)
            }

            val wantMicRestricted = desired.blockMicrophone || desired.blockAllSensors
            actions += adminAction(
                target = "Microphone unmute restriction (no_unmute_microphone)",
                desired = wantMicRestricted,
                previous = observed.microphoneRestricted,
                available = true,
                unavailableReason = null,
            ) { restricted ->
                setRestriction(UserManager.DISALLOW_UNMUTE_MICROPHONE, restricted)
            }
        } else if (desired.blockMicrophone || desired.blockAllSensors) {
            actions += ReconcileReport.Action(
                target = "Microphone unmute restriction (no_unmute_microphone)",
                backend = kind,
                desired = true,
                previous = null,
                success = true,
                detail = "skipped: needs device owner; Shizuku already mutes the microphone",
            )
        }

        return actions
    }

    private fun setRestriction(key: String, enabled: Boolean) {
        val manager = dpm ?: return
        if (enabled) manager.addUserRestriction(admin, key)
        else manager.clearUserRestriction(admin, key)
    }

    private inline fun adminAction(
        target: String,
        desired: Boolean,
        previous: Boolean?,
        available: Boolean,
        unavailableReason: String?,
        write: (Boolean) -> Unit,
    ): ReconcileReport.Action {
        if (!available) {
            return ReconcileReport.Action(
                target = target,
                backend = BackendKind.DEVICE_POLICY,
                desired = desired,
                previous = previous,
                success = true,
                detail = "skipped: ${unavailableReason ?: "unavailable"}",
            )
        }
        if (previous == desired) {
            return ReconcileReport.Action(
                target = target,
                backend = BackendKind.DEVICE_POLICY,
                desired = desired,
                previous = previous,
                success = true,
                detail = "already correct",
            )
        }
        return try {
            write(desired)
            ReconcileReport.Action(
                target = target,
                backend = BackendKind.DEVICE_POLICY,
                desired = desired,
                previous = previous,
                success = true,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "failed to set $target=$desired", t)
            ReconcileReport.Action(
                target = target,
                backend = BackendKind.DEVICE_POLICY,
                desired = desired,
                previous = previous,
                success = false,
                detail = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    companion object {
        private const val TAG = "DevicePolicyBackend"

        /** The ADB command that makes SensorBlock the device owner. */
        fun deviceOwnerCommand(packageName: String): String =
            "adb shell dpm set-device-owner $packageName/" +
                SensorBlockDeviceAdminReceiver::class.java.name
    }
}
