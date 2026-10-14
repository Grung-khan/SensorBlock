package com.sensorblock.app.domain

/**
 * The privileged identity that performs an enforcement action.
 *
 * SensorBlock layers all available identities so a block survives if one layer
 * is bypassed. See AGENTS.md §4 (capability matrix) and §5.2.
 */
enum class BackendKind(val label: String, val blurb: String) {
    SHIZUKU(
        "Shizuku",
        "Runs as uid 2000 (shell), the only non-root identity allowed to use the " +
            "sensor-privacy service. Covers every sensor, camera and microphone.",
    ),
    DEVICE_POLICY(
        "Device policy",
        "Uses DevicePolicyManager for a hard camera restriction that survives " +
            "independently of Shizuku. Needs the app to be an active device admin " +
            "(or device owner for the microphone restriction).",
    ),
}

/** One switchable enforcement primitive. Every toggle maps onto a set of these. */
enum class Lever {
    /** `ISensorPrivacyManager.setSensorPrivacy(true)` — all sensors + camera + mic silencing. */
    ALL_SENSOR_PRIVACY,
    /** Per-sensor privacy toggle for the camera (`SensorPrivacyManager.Sensors.CAMERA`). */
    CAMERA_PRIVACY,
    /** Per-sensor privacy toggle for the microphone (`SensorPrivacyManager.Sensors.MICROPHONE`). */
    MICROPHONE_PRIVACY,
    /** `DevicePolicyManager.setCameraDisabled` / `DISALLOW_CAMERA`. */
    CAMERA_ADMIN,
    /** `DevicePolicyManager.addUserRestriction(DISALLOW_UNMUTE_MICROPHONE)`. */
    MICROPHONE_ADMIN,
    ;
}

/**
 * AppOps we can flip per package. Names are the ones accepted by `appops set`,
 * so we never need the numeric op codes.
 */
enum class AppOpKind(
    val opName: String,
    val label: String,
    val blurb: String,
) {
    CAMERA(
        "CAMERA",
        "Camera",
        "Refuses camera access for this app. Applies to every lens.",
    ),
    RECORD_AUDIO(
        "RECORD_AUDIO",
        "Microphone",
        "Refuses audio capture for this app; recordings come back silent.",
    ),
    BODY_SENSORS(
        "BODY_SENSORS",
        "Body sensors",
        "Refuses heart-rate and other body sensors for this app.",
    ),
    ACTIVITY_RECOGNITION(
        "ACTIVITY_RECOGNITION",
        "Activity",
        "Refuses step counting and detected-activity data for this app.",
    ),
}

/** Where a sensor row sits in the UI's honesty model (AGENTS.md §5.4). */
enum class SensorGroup(val title: String, val explanation: String) {
    INDEPENDENT(
        "Independent",
        "These have a dedicated switch in Android, so SensorBlock toggles only them.",
    ),
    GROUPED(
        "Grouped hardware sensors",
        "Android has no per-sensor switch for these. Turning any one off engages " +
            "the device-wide all-sensor block, which stops every sensor listed here " +
            "at once. Individual control would require root.",
    ),
}

/** A sensor as reported by `SensorManager`. */
data class SensorInfo(
    val handle: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val type: Int,
    val typeName: String,
    val maxRange: Float,
    val resolution: Float,
    val powerMilliAmp: Float,
    val minDelayMicros: Int,
    val isWakeUp: Boolean,
    val isDynamic: Boolean,
    val reportingMode: Int,
) {
    val isOneShot: Boolean get() = reportingMode == 0
    val isOnChange: Boolean get() = reportingMode == 1
    val isContinuous: Boolean get() = reportingMode == 2
}

/** What the user asked SensorBlock to enforce. Persisted; the source of truth for reconcile. */
data class DesiredPolicy(
    val blockAllSensors: Boolean = false,
    val blockCamera: Boolean = false,
    val blockMicrophone: Boolean = false,
    /**
     * Packages we have *touched*, mapped to the sensors-idle state they should
     * have (`cmd sensorservice set-uid-state`). A package is kept in the map
     * even after being switched back on, so reconcile can undo it — that is
     * what makes the toggle exactly reversible.
     */
    val appSensorIdle: Map<String, Boolean> = emptyMap(),
    /**
     * Packages we have *touched*, mapped to the AppOps that must be ignored.
     * An empty set means "managed, nothing blocked" and lets reconcile restore
     * the ops to `default`.
     */
    val appOpBlocks: Map<String, Set<AppOpKind>> = emptyMap(),
) {
    /** Apps that currently have at least one per-app block engaged. */
    val blockedApps: Set<String>
        get() = buildSet {
            appSensorIdle.filterValues { it }.keys.forEach(::add)
            appOpBlocks.filterValues { it.isNotEmpty() }.keys.forEach(::add)
        }

    val isEmpty: Boolean
        get() = !blockAllSensors && !blockCamera && !blockMicrophone && blockedApps.isEmpty()

    /** Number of device-wide switches currently engaged. */
    val activeSwitchCount: Int
        get() = (if (blockAllSensors) 1 else 0) +
            (if (blockCamera) 1 else 0) +
            (if (blockMicrophone) 1 else 0)
}

/** What the OS actually reports right now. `null` means "could not read". */
data class ObservedState(
    val allSensorPrivacy: Boolean? = null,
    val cameraPrivacyToggle: Boolean? = null,
    val microphonePrivacyToggle: Boolean? = null,
    val cameraAdminDisabled: Boolean? = null,
    val cameraUserRestriction: Boolean? = null,
    val microphoneRestricted: Boolean? = null,
    /** package -> set of AppOps currently ignored/denied. */
    val blockedAppOps: Map<String, Set<AppOpKind>> = emptyMap(),
    /** package -> whether its sensors are currently forced idle. */
    val appSensorIdle: Map<String, Boolean> = emptyMap(),
    val readAtEpochMs: Long = 0L,
) {
    val allSensorsBlocked: Boolean get() = allSensorPrivacy == true
    val cameraBlocked: Boolean
        get() = allSensorPrivacy == true || cameraPrivacyToggle == true ||
            cameraAdminDisabled == true || cameraUserRestriction == true
    val microphoneBlocked: Boolean
        get() = allSensorPrivacy == true || microphonePrivacyToggle == true ||
            microphoneRestricted == true
}

/** What the current device + identities can actually do. */
data class Capabilities(
    val shizukuInstalled: Boolean = false,
    val shizukuBinderAlive: Boolean = false,
    val shizukuVersion: Int = 0,
    val shizukuPermissionGranted: Boolean = false,
    val shellServiceReady: Boolean = false,
    val sensorPrivacyServiceReachable: Boolean = false,
    /** Sensor ids (SensorPrivacyManager.Sensors) that the device supports toggling. */
    val toggleableSensors: Set<Int> = emptySet(),
    val deviceAdminActive: Boolean = false,
    val deviceOwner: Boolean = false,
) {
    val canBlockAllSensors: Boolean get() = sensorPrivacyServiceReachable
    val canBlockCamera: Boolean
        get() = sensorPrivacyServiceReachable || deviceAdminActive || deviceOwner
    val canBlockMicrophone: Boolean
        get() = sensorPrivacyServiceReachable || deviceOwner
    val canControlApps: Boolean get() = shellServiceReady
    val anyBackendReady: Boolean get() = sensorPrivacyServiceReachable || deviceAdminActive
}

/** A human-readable problem to surface in the UI/diagnostics. */
data class Issue(
    val severity: Severity,
    val title: String,
    val detail: String,
    val fix: String? = null,
) {
    enum class Severity { INFO, WARNING, ERROR }
}

/** Result of one reconcile pass. */
data class ReconcileReport(
    val actions: List<Action> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val observed: ObservedState = ObservedState(),
) {
    val changed: List<Action> get() = actions.filter { it.changed }
    val failures: List<Action> get() = actions.filter { !it.success }

    data class Action(
        val target: String,
        val backend: BackendKind,
        val desired: Boolean,
        val previous: Boolean?,
        val success: Boolean,
        val detail: String? = null,
    ) {
        val changed: Boolean get() = previous != desired
    }
}

/** A launchable app for the per-app matrix. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
)
