package com.sensorblock.app.domain

import android.content.Context
import com.sensorblock.app.backend.DevicePolicyBackend
import com.sensorblock.app.backend.PrivilegedBackend
import com.sensorblock.app.backend.ShizukuBackend
import com.sensorblock.app.backend.merge
import com.sensorblock.app.backend.overlayMissing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The heart of SensorBlock: turns a [DesiredPolicy] into enforcement actions.
 *
 * The contract is **`reconcile(desiredState)`**, never "fire and forget":
 *
 *   1. read what the OS currently does ([ObservedState]);
 *   2. compute the diffs and issue only those;
 *   3. re-read to *verify*, and surface anything that did not take.
 *
 * Because every step is a diff against observed reality, this is idempotent
 * (running it twice does nothing the second time) and exactly reversible
 * (turning a toggle off produces the inverse diff).
 */
class PolicyEngine(private val context: Context) {

    val shizuku = ShizukuBackend(context)
    val devicePolicy = DevicePolicyBackend(context)

    private val backends: List<PrivilegedBackend> = listOf(shizuku, devicePolicy)

    /** Merged view of what the current device and identities can do. */
    suspend fun capabilities(): Capabilities = withContext(Dispatchers.IO) {
        var merged = Capabilities()
        for (backend in backends) {
            merged = merged.merge(runCatching { backend.capabilities() }.getOrDefault(Capabilities()))
        }
        merged
    }

    /** Reads the observed state. [desired] tells the shell backend which packages to probe. */
    suspend fun observe(desired: DesiredPolicy): ObservedState = withContext(Dispatchers.IO) {
        shizuku.primeObservation(desired)
        var merged = ObservedState()
        for (backend in backends) {
            val partial = runCatching { backend.observe() }.getOrDefault(ObservedState())
            merged = merged.overlayMissing(partial)
        }
        merged
    }

    /** Reads the observed state and reports problems, without changing anything. */
    suspend fun diagnose(desired: DesiredPolicy): Pair<ObservedState, List<Issue>> =
        withContext(Dispatchers.IO) {
            val observed = observe(desired)
            observed to issues(desired, observed, capabilities())
        }

    /** Applies [desired] and verifies the result. */
    suspend fun apply(desired: DesiredPolicy): ReconcileReport = withContext(Dispatchers.IO) {
        val before = observe(desired)
        val actions = mutableListOf<ReconcileReport.Action>()
        for (backend in backends) {
            actions += runCatching { backend.reconcile(desired, before) }
                .getOrElse { throwable ->
                    listOf(
                        ReconcileReport.Action(
                            target = backend.kind.label,
                            backend = backend.kind,
                            desired = true,
                            previous = null,
                            success = false,
                            detail = "${throwable.javaClass.simpleName}: ${throwable.message}",
                        ),
                    )
                }
        }
        val after = observe(desired)
        ReconcileReport(
            actions = actions,
            issues = issues(desired, after, capabilities()),
            observed = after,
        )
    }

    /** Compare intent with reality and report anything worth telling the user. */
    private fun issues(
        desired: DesiredPolicy,
        observed: ObservedState,
        capabilities: Capabilities,
    ): List<Issue> = buildList {
        if (desired.blockAllSensors && observed.allSensorPrivacy != true) {
            add(
                Issue(
                    severity = Issue.Severity.ERROR,
                    title = "All-sensor block is not active",
                    detail = "The sensor-privacy service did not accept the change.",
                    fix = "Start Shizuku and grant SensorBlock permission, then re-apply.",
                ),
            )
        }
        if (desired.effectiveBlockCamera && !observed.cameraBlocked) {
            add(
                Issue(
                    severity = Issue.Severity.ERROR,
                    title = "Camera block is not active",
                    detail = "Neither the camera privacy toggle nor device policy took effect.",
                    fix = "Grant Shizuku permission, or enable SensorBlock as a device admin.",
                ),
            )
        }
        if (desired.effectiveBlockMicrophone && !observed.microphoneBlocked) {
            add(
                Issue(
                    severity = Issue.Severity.ERROR,
                    title = "Microphone block is not active",
                    detail = "Neither the microphone privacy toggle nor a user restriction took effect.",
                    fix = "Grant Shizuku permission.",
                ),
            )
        }
        if (!capabilities.shizukuPermissionGranted) {
            add(
                Issue(
                    severity = Issue.Severity.WARNING,
                    title = "Shizuku is not authorised",
                    detail = "Without Shizuku, only the device-admin camera block is available.",
                    fix = "Install Shizuku, start its server, then grant SensorBlock permission.",
                ),
            )
        }
        if (!capabilities.deviceOwner && desired.effectiveBlockMicrophone) {
            add(
                Issue(
                    severity = Issue.Severity.INFO,
                    title = "Microphone hardening layer is unavailable",
                    detail = "The no_unmute_microphone restriction needs SensorBlock to be the device owner. " +
                        "The Shizuku microphone toggle is still blocking audio capture.",
                    fix = DevicePolicyBackend.deviceOwnerCommand(context.packageName),
                ),
            )
        }
        if (desired.isEmpty && !observed.allSensorsBlocked) {
            add(
                Issue(
                    severity = Issue.Severity.INFO,
                    title = "Nothing is blocked",
                    detail = "Every sensor, the camera and the microphone are available to all apps.",
                ),
            )
        }
    }

    fun close() {
        backends.forEach { runCatching { it.close() } }
    }
}
