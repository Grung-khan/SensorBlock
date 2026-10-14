package com.sensorblock.app.backend

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.sensorblock.app.domain.AppOpKind
import com.sensorblock.app.domain.BackendKind
import com.sensorblock.app.domain.Capabilities
import com.sensorblock.app.domain.DesiredPolicy
import com.sensorblock.app.domain.ObservedState
import com.sensorblock.app.domain.ReconcileReport
import rikka.shizuku.Shizuku

/**
 * The primary backend. Everything goes to the system *with uid 2000*.
 *
 * Two channels are used:
 *  1. `ShizukuBinderWrapper` + the mirrored `ISensorPrivacyManager` AIDL for the
 *     sensor-privacy service (the only place that can flip the all-sensor switch).
 *  2. A Shizuku **UserService** running as uid 2000 for the primitives that only
 *     exist as shell commands (`appops`, `cmd sensorservice`).
 */
class ShizukuBackend(private val context: Context) : PrivilegedBackend {

    override val kind: BackendKind = BackendKind.SHIZUKU

    private val shell = ShellServiceClient(context)

    @Volatile
    private var sensorPrivacy: SensorPrivacyClient? = null

    @Volatile
    private var lastError: String? = null

    /** Last error encountered while talking to the privileged channels, if any. */
    fun lastError(): String? = lastError

    // ---------------------------------------------------------------- probe ---

    override suspend fun capabilities(): Capabilities {
        val installed = isPackageInstalled(SHIZUKU_PACKAGE)
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val version = if (alive) runCatching { Shizuku.getVersion() }.getOrDefault(0) else 0
        val granted = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val client = if (granted) sensorPrivacy() else null
        val toggles = buildSet {
            if (client != null) {
                // `supportsSensorToggle` is advisory and unreliable: some OEM
                // ROMs (e.g. this OEM ROM) return false
                // for both toggle types while `setToggleSensorPrivacy` still
                // works and `isCombinedToggleSensorPrivacyEnabled` reports it.
                // The sensor-privacy service only ever exposes these two, so
                // treat them as toggleable whenever the service is reachable;
                // reconcile verifies the real result afterwards.
                add(SensorPrivacyClient.SENSOR_CAMERA)
                add(SensorPrivacyClient.SENSOR_MICROPHONE)
            }
        }
        val shellReady = if (granted) runCatching { shell.acquire(SHELL_PROBE_TIMEOUT_MS) }.getOrNull() != null else false

        return Capabilities(
            shizukuInstalled = installed,
            shizukuBinderAlive = alive,
            shizukuVersion = version,
            shizukuPermissionGranted = granted,
            shellServiceReady = shellReady,
            sensorPrivacyServiceReachable = client != null,
            toggleableSensors = toggles,
        )
    }

    /** Runs [cmd] as uid 2000 and returns the result; UNAVAILABLE when Shizuku is down. */
    suspend fun runShell(cmd: String): ShellResult = shell.run(cmd)

    /** Resolves (and caches) the sensor-privacy binder. */
    fun sensorPrivacy(): SensorPrivacyClient? {
        sensorPrivacy?.let { return it }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return null
        val client = SensorPrivacyClient.connect() ?: return null
        sensorPrivacy = client
        lastError = null
        return client
    }

    // -------------------------------------------------------------- observe ---

    override suspend fun observe(): ObservedState {
        val now = System.currentTimeMillis()
        val client = sensorPrivacy()
        val all = runSafely("isSensorPrivacyEnabled") { client?.isAllSensorsBlocked() }
        val camera = runSafely("isCameraPrivacyEnabled") {
            client?.isToggleEnabled(SensorPrivacyClient.SENSOR_CAMERA)
        }
        val microphone = runSafely("isMicPrivacyEnabled") {
            client?.isToggleEnabled(SensorPrivacyClient.SENSOR_MICROPHONE)
        }

        val desired = lastDesiredForObserve
        val appOps = if (desired != null) readAppOps(desired.appOpBlocks.keys) else emptyMap()
        val sensorIdle = if (desired != null) readSensorIdle(desired.appSensorIdle.keys) else emptyMap()

        return ObservedState(
            allSensorPrivacy = all,
            cameraPrivacyToggle = camera,
            microphonePrivacyToggle = microphone,
            blockedAppOps = appOps,
            appSensorIdle = sensorIdle,
            readAtEpochMs = now,
        )
    }

    /**
     * AppOps and uid-state reads are per-package, so we only probe the packages
     * the user actually manages. This is set by [reconcile] right before it asks
     * for an observation; it is a cache key, not user state.
     */
    @Volatile
    private var lastDesiredForObserve: DesiredPolicy? = null

    /** Warms the observation cache so [observe] knows which packages to probe. */
    fun primeObservation(desired: DesiredPolicy) {
        lastDesiredForObserve = desired
    }

    private suspend fun readAppOps(packages: Set<String>): Map<String, Set<AppOpKind>> {
        if (packages.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Set<AppOpKind>>()
        for (pkg in packages) {
            val blocked = mutableSetOf<AppOpKind>()
            for (kind in AppOpKind.entries) {
                val result = shell.run("appops get $pkg ${kind.opName}")
                val mode = parseAppOpMode(result.stdout)
                if (mode == AppOpMode.IGNORE || mode == AppOpMode.DENY) blocked += kind
            }
            out[pkg] = blocked
        }
        return out
    }

    private suspend fun readSensorIdle(packages: Set<String>): Map<String, Boolean> {
        if (packages.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Boolean>()
        for (pkg in packages) {
            val result = shell.run("cmd sensorservice get-uid-state $pkg")
            // Prints exactly "idle" or "active" (SensorService::handleGetUidState).
            out[pkg] = result.stdout.contains("idle")
        }
        return out
    }

    // ------------------------------------------------------------ reconcile ---

    override suspend fun reconcile(
        desired: DesiredPolicy,
        observed: ObservedState,
    ): List<ReconcileReport.Action> {
        primeObservation(desired)
        val actions = mutableListOf<ReconcileReport.Action>()

        // 1. The device-wide all-sensor kill switch.
        actions += leverAction(
            target = "All sensors, camera and microphone",
            desired = desired.blockAllSensors,
            previous = observed.allSensorPrivacy,
        ) { blocked ->
            requireClient().setAllSensorsBlocked(blocked)
        }

        // 2. Camera and microphone privacy toggles. Driven by the *effective*
        //    values so the master switch also blocks each of them — the all-sensor
        //    switch does not cover the camera on some OEM ROMs (verified on an OEM ROM).
        actions += leverAction(
            target = "Camera privacy toggle",
            desired = desired.effectiveBlockCamera,
            previous = observed.cameraPrivacyToggle,
        ) { blocked ->
            requireClient().setToggleEnabled(SensorPrivacyClient.SENSOR_CAMERA, blocked)
        }

        actions += leverAction(
            target = "Microphone privacy toggle",
            desired = desired.effectiveBlockMicrophone,
            previous = observed.microphonePrivacyToggle,
        ) { blocked ->
            requireClient().setToggleEnabled(SensorPrivacyClient.SENSOR_MICROPHONE, blocked)
        }

        // 3. Per-app sensor kill (cmd sensorservice set-uid-state).
        for ((pkg, shouldBeIdle) in desired.appSensorIdle) {
            val previous = observed.appSensorIdle[pkg]
            if (previous == shouldBeIdle) continue
            val cmd = if (shouldBeIdle) {
                "cmd sensorservice set-uid-state $pkg idle"
            } else {
                "cmd sensorservice reset-uid-state $pkg"
            }
            val ok = shell.run(cmd).ok
            actions += ReconcileReport.Action(
                target = "$pkg · all sensors",
                backend = kind,
                desired = shouldBeIdle,
                previous = previous,
                success = ok,
                detail = if (ok) null else "set-uid-state failed",
            )
        }

        // 4. Per-app AppOps.
        for ((pkg, blockedOps) in desired.appOpBlocks) {
            val current = observed.blockedAppOps[pkg] ?: emptySet()
            for (op in AppOpKind.entries) {
                val shouldBlock = op in blockedOps
                val wasBlocked = op in current
                if (shouldBlock == wasBlocked) continue
                val mode = if (shouldBlock) "ignore" else "default"
                val ok = shell.run("appops set $pkg ${op.opName} $mode").ok
                actions += ReconcileReport.Action(
                    target = "$pkg · ${op.label}",
                    backend = kind,
                    desired = shouldBlock,
                    previous = wasBlocked,
                    success = ok,
                    detail = if (ok) null else "appops set failed",
                )
            }
        }

        return actions
    }

    /**
     * Builds an action for a device-wide lever: reads the previous value, writes
     * only when it differs, and reports exactly what happened.
     */
    private inline fun leverAction(
        target: String,
        desired: Boolean,
        previous: Boolean?,
        crossinline write: (Boolean) -> Unit,
    ): ReconcileReport.Action {
        if (previous == desired) {
            return ReconcileReport.Action(
                target = target,
                backend = BackendKind.SHIZUKU,
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
                backend = BackendKind.SHIZUKU,
                desired = desired,
                previous = previous,
                success = true,
            )
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "failed to set $target=$desired", t)
            ReconcileReport.Action(
                target = target,
                backend = BackendKind.SHIZUKU,
                desired = desired,
                previous = previous,
                success = false,
                detail = lastError,
            )
        }
    }

    private fun requireClient(): SensorPrivacyClient =
        sensorPrivacy() ?: throw IllegalStateException(
            "Sensor-privacy service unreachable. Is Shizuku running and authorised?",
        )

    override fun close() {
        shell.release()
        sensorPrivacy = null
    }

    private fun <T> runSafely(what: String, block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        lastError = "$what: ${t.javaClass.simpleName}: ${t.message}"
        Log.w(TAG, "$what failed", t)
        null
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (t: PackageManager.NameNotFoundException) {
        false
    } catch (t: Throwable) {
        false
    }

    companion object {
        private const val TAG = "ShizukuBackend"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHELL_PROBE_TIMEOUT_MS = 6_000L

        /**
         * Parses `appops get <pkg> <OP>` output, which is either
         * `CAMERA: allow` or `CAMERA: ignore; time=...`.
         */
        fun parseAppOpMode(output: String): AppOpMode {
            val body = output.substringAfter(':').trim()
            val mode = body.substringBefore(';').trim().lowercase()
            return when {
                mode.startsWith("ignore") -> AppOpMode.IGNORE
                mode.startsWith("deny") -> AppOpMode.DENY
                mode.startsWith("allow") -> AppOpMode.ALLOW
                mode.startsWith("default") -> AppOpMode.DEFAULT
                else -> AppOpMode.UNKNOWN
            }
        }
    }

    enum class AppOpMode { ALLOW, IGNORE, DENY, DEFAULT, UNKNOWN }
}
