package com.sensorblock.app.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensorblock.app.SensorBlockApp
import com.sensorblock.app.domain.AppEntry
import com.sensorblock.app.domain.AppOpKind
import com.sensorblock.app.domain.Capabilities
import com.sensorblock.app.domain.DesiredPolicy
import com.sensorblock.app.domain.Issue
import com.sensorblock.app.domain.ObservedState
import com.sensorblock.app.domain.ReconcileReport
import com.sensorblock.app.domain.SelfTest
import com.sensorblock.app.domain.SensorInfo
import com.sensorblock.app.service.ReassertService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Everything the UI renders, in one immutable snapshot. */
data class UiState(
    val loading: Boolean = true,
    val policy: DesiredPolicy = DesiredPolicy(),
    val observed: ObservedState = ObservedState(),
    val capabilities: Capabilities = Capabilities(),
    val sensors: List<SensorInfo> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val lastReport: ReconcileReport? = null,
    val lastAppliedAt: Long = 0L,
    val applying: Boolean = false,
    val probes: List<SelfTest.Probe> = emptyList(),
    val testing: Boolean = false,
    val message: String? = null,
    val appSearch: String = "",
    val showSystemApps: Boolean = false,
) {
    val policyInSync: Boolean
        get() {
            if (applying) return true
            val o = observed
            return policy.blockAllSensors == (o.allSensorPrivacy == true) &&
                policy.effectiveBlockCamera == (o.cameraPrivacyToggle == true) &&
                policy.effectiveBlockMicrophone == (o.microphonePrivacyToggle == true)
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = SensorBlockApp.from(application)
    private val engine = app.policyEngine
    private val store = app.policyStore
    private val selfTest = SelfTest(application, engine)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        viewModelScope.launch {
            refreshCapabilities()
            if (!_state.value.policy.isEmpty) applyNow(reason = "Shizuku connected")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        viewModelScope.launch { refreshCapabilities() }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
            viewModelScope.launch {
                refreshCapabilities()
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    message("Shizuku permission granted")
                    if (!_state.value.policy.isEmpty) applyNow(reason = "permission granted")
                } else {
                    message("Shizuku permission denied")
                }
            }
        }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
        viewModelScope.launch {
            store.policy.collect { policy ->
                _state.update { it.copy(policy = policy) }
            }
        }
        viewModelScope.launch { bootstrap() }
    }

    // ------------------------------------------------------------- lifecycle ---

    private suspend fun bootstrap() {
        val sensors = app.sensorInventory.all()
        val apps = app.appInventory.launchable(includeSystem = false)
        _state.update { it.copy(sensors = sensors, apps = apps, loading = false) }
        refreshCapabilities()
        refreshObserved()
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshCapabilities()
            refreshObserved()
            _state.update { it.copy(lastAppliedAt = store.lastAppliedAt()) }
        }
    }

    private suspend fun refreshCapabilities() {
        val capabilities = engine.capabilities()
        _state.update { it.copy(capabilities = capabilities) }
    }

    private suspend fun refreshObserved() {
        val desired = _state.value.policy
        val (observed, issues) = engine.diagnose(desired)
        _state.update { it.copy(observed = observed, issues = issues) }
    }

    fun loadApps(includeSystem: Boolean) {
        viewModelScope.launch {
            val apps = app.appInventory.launchable(includeSystem)
            _state.update { it.copy(apps = apps, showSystemApps = includeSystem) }
        }
    }

    fun setAppSearch(query: String) = _state.update { it.copy(appSearch = query) }

    fun message(text: String?) = _state.update { it.copy(message = text) }

    // ---------------------------------------------------------------- Shizuku ---

    /**
     * Requests the Shizuku permission. On Android 11+ the Shizuku app shows a
     * standard runtime-permission dialog; the result arrives through
     * [permissionListener].
     */
    fun requestShizuku() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            message("Shizuku is not running. Start the Shizuku server, then try again.")
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            viewModelScope.launch { refreshCapabilities() }
            return
        }
        runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }
            .onFailure { message("Could not request Shizuku permission: ${it.message}") }
    }

    fun openShizuku() {
        val context = getApplication<Application>()
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
                .onFailure { message("Shizuku is not installed") }
        } else {
            message("Shizuku is not installed")
        }
    }

    fun openDeveloperOptions() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { message("Could not open developer options") }
    }

    // -------------------------------------------------------------- mutations ---

    fun setBlockAllSensors(blocked: Boolean) = mutate { it.copy(blockAllSensors = blocked) }

    fun setBlockCamera(blocked: Boolean) = mutate { it.copy(blockCamera = blocked) }

    fun setBlockMicrophone(blocked: Boolean) = mutate { it.copy(blockMicrophone = blocked) }

    fun setAppSensors(blocked: Boolean, packageName: String) = mutate { policy ->
        policy.copy(appSensorIdle = policy.appSensorIdle + (packageName to blocked))
    }

    fun setAppOp(blocked: Boolean, packageName: String, kind: AppOpKind) = mutate { policy ->
        val current = policy.appOpBlocks[packageName] ?: emptySet()
        val next = if (blocked) current + kind else current - kind
        policy.copy(appOpBlocks = policy.appOpBlocks + (packageName to next))
    }

    /** Blocks or restores everything for one app at once. */
    fun setAllForApp(blocked: Boolean, packageName: String) = mutate { policy ->
        policy.copy(
            appSensorIdle = policy.appSensorIdle + (packageName to blocked),
            appOpBlocks = policy.appOpBlocks + (
                packageName to if (blocked) AppOpKind.entries.toSet() else emptySet()
                ),
        )
    }

    private fun mutate(transform: (DesiredPolicy) -> DesiredPolicy) {
        viewModelScope.launch {
            store.update(transform)
            val desired = store.current()
            ensureServiceIfNeeded(desired)
            applyNow(reason = null)
        }
    }

    /**
     * Keeps the re-assert service alive only while a volatile primitive (per-app
     * sensor blocks) is engaged.
     */
    private fun ensureServiceIfNeeded(desired: DesiredPolicy) {
        val needsResident = desired.appSensorIdle.values.any { it }
        if (needsResident || !desired.isEmpty) {
            ReassertService.start(getApplication())
        }
    }

    fun applyNow(reason: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(applying = true) }
            val desired = store.current()
            val report = engine.apply(desired)
            store.noteApplied(System.currentTimeMillis())
            _state.update {
                it.copy(
                    applying = false,
                    lastReport = report,
                    observed = report.observed,
                    issues = report.issues,
                    lastAppliedAt = System.currentTimeMillis(),
                    message = reason ?: messageFor(report),
                )
            }
        }
    }

    fun runSelfTest() {
        viewModelScope.launch {
            _state.update { it.copy(testing = true, probes = emptyList()) }
            val results = selfTest.runAll()
            _state.update { it.copy(testing = false, probes = results) }
            refreshCapabilities()
            refreshObserved()
        }
    }

    private fun messageFor(report: ReconcileReport): String? = when {
        report.failures.isNotEmpty() -> "${report.failures.size} step(s) failed — see Diagnostics"
        report.changed.isEmpty() -> null
        report.changed.size == 1 -> "Applied: ${report.changed.first().target}"
        else -> "Applied ${report.changed.size} changes"
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
        super.onCleared()
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 0x5342
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
