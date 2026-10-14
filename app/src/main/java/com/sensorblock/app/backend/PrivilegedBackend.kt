package com.sensorblock.app.backend

import com.sensorblock.app.domain.BackendKind
import com.sensorblock.app.domain.Capabilities
import com.sensorblock.app.domain.DesiredPolicy
import com.sensorblock.app.domain.ObservedState
import com.sensorblock.app.domain.ReconcileReport

/**
 * A privileged identity that can enforce (and un-enforce) part of the policy.
 *
 * Implementations must be:
 *  - **idempotent** — [reconcile] compares [DesiredPolicy] against
 *    [ObservedState] and issues only the diffs, so calling it twice is a no-op;
 *  - **reversible** — turning a target off must undo exactly what turning it on
 *    did, with no residue (AGENTS.md §5.1);
 *  - **honest** — a backend that cannot read a field leaves it `null` in
 *    [ObservedState] instead of guessing.
 */
interface PrivilegedBackend {

    val kind: BackendKind

    /** What this identity can currently do. Cheap; safe to call often. */
    suspend fun capabilities(): Capabilities

    /** Reads only the fields this backend owns; others are left at their defaults. */
    suspend fun observe(): ObservedState

    /** Issues the diffs needed to move [observed] towards [desired]. */
    suspend fun reconcile(
        desired: DesiredPolicy,
        observed: ObservedState,
    ): List<ReconcileReport.Action>

    /** Frees resources (binder connections, user services). */
    fun close() {}
}

/** Overlays [other] on top of this state, keeping this state's non-null values. */
fun ObservedState.overlayMissing(other: ObservedState): ObservedState = ObservedState(
    allSensorPrivacy = allSensorPrivacy ?: other.allSensorPrivacy,
    cameraPrivacyToggle = cameraPrivacyToggle ?: other.cameraPrivacyToggle,
    microphonePrivacyToggle = microphonePrivacyToggle ?: other.microphonePrivacyToggle,
    cameraAdminDisabled = cameraAdminDisabled ?: other.cameraAdminDisabled,
    cameraUserRestriction = cameraUserRestriction ?: other.cameraUserRestriction,
    microphoneRestricted = microphoneRestricted ?: other.microphoneRestricted,
    blockedAppOps = blockedAppOps + other.blockedAppOps,
    appSensorIdle = appSensorIdle + other.appSensorIdle,
    readAtEpochMs = maxOf(readAtEpochMs, other.readAtEpochMs),
)

/** Unions two capability readings. */
fun Capabilities.merge(other: Capabilities): Capabilities = Capabilities(
    shizukuInstalled = shizukuInstalled || other.shizukuInstalled,
    shizukuBinderAlive = shizukuBinderAlive || other.shizukuBinderAlive,
    shizukuVersion = maxOf(shizukuVersion, other.shizukuVersion),
    shizukuPermissionGranted = shizukuPermissionGranted || other.shizukuPermissionGranted,
    shellServiceReady = shellServiceReady || other.shellServiceReady,
    sensorPrivacyServiceReachable =
        sensorPrivacyServiceReachable || other.sensorPrivacyServiceReachable,
    toggleableSensors = toggleableSensors + other.toggleableSensors,
    deviceAdminActive = deviceAdminActive || other.deviceAdminActive,
    deviceOwner = deviceOwner || other.deviceOwner,
)
