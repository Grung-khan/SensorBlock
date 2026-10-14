package com.sensorblock.app.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.policyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sensorblock_policy",
)

/**
 * Persists the user's *desired* policy.
 *
 * SensorBlock is deliberately stateless-ish: the OS is the source of truth for
 * observed state, and this store only records intent so the app can re-assert it
 * after a reboot, a Shizuku restart or a package change (AGENTS.md §3.6).
 *
 * Per-app entries are **kept even when switched off** (as `pkg -> empty`), so a
 * later reconcile knows they must be restored rather than left blocked.
 */
class PolicyStore(private val context: Context) {

    val policy: Flow<DesiredPolicy> = context.policyDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs.toPolicy() }

    suspend fun current(): DesiredPolicy = policy.first()

    suspend fun update(transform: (DesiredPolicy) -> DesiredPolicy) {
        context.policyDataStore.edit { prefs ->
            val next = transform(prefs.toPolicy())
            prefs[KEY_BLOCK_ALL_SENSORS] = next.blockAllSensors
            prefs[KEY_BLOCK_CAMERA] = next.blockCamera
            prefs[KEY_BLOCK_MICROPHONE] = next.blockMicrophone
            prefs[KEY_APP_SENSOR_IDLE] = next.appSensorIdle
                .map { (pkg, idle) -> "$pkg${FIELD_SEP}${if (idle) "1" else "0"}" }
                .toSet()
            prefs[KEY_APP_OP_BLOCKS] = next.appOpBlocks
                .flatMap { (pkg, ops) -> ops.map { "$pkg${FIELD_SEP}${it.name}" } }
                .toSet()
            // Keep a separate record of touched packages so an op set that is now
            // empty is not silently forgotten.
            prefs[KEY_APP_OP_MANAGED] = next.appOpBlocks.keys
        }
    }

    /** Records when a reconcile pass last agreed with the policy. */
    suspend fun noteApplied(epochMs: Long) {
        context.policyDataStore.edit { it[KEY_LAST_APPLIED_AT] = epochMs.toString() }
    }

    suspend fun lastAppliedAt(): Long =
        context.policyDataStore.data.map { it[KEY_LAST_APPLIED_AT]?.toLongOrNull() ?: 0L }.first()

    private fun Preferences.toPolicy(): DesiredPolicy {
        val sensorIdle = buildMap {
            this@toPolicy[KEY_APP_SENSOR_IDLE].orEmpty().forEach { entry ->
                val sep = entry.lastIndexOf(FIELD_SEP)
                if (sep <= 0) return@forEach
                put(entry.substring(0, sep), entry.substring(sep + 1) == "1")
            }
        }

        val opBlocks = mutableMapOf<String, Set<AppOpKind>>()
        this[KEY_APP_OP_MANAGED].orEmpty().forEach { pkg -> opBlocks[pkg] = emptySet() }
        this[KEY_APP_OP_BLOCKS].orEmpty().forEach { entry ->
            val sep = entry.lastIndexOf(FIELD_SEP)
            if (sep <= 0) return@forEach
            val pkg = entry.substring(0, sep)
            val kind = AppOpKind.entries.firstOrNull { it.name == entry.substring(sep + 1) }
                ?: return@forEach
            opBlocks[pkg] = (opBlocks[pkg] ?: emptySet()) + kind
        }

        return DesiredPolicy(
            blockAllSensors = this[KEY_BLOCK_ALL_SENSORS] ?: false,
            blockCamera = this[KEY_BLOCK_CAMERA] ?: false,
            blockMicrophone = this[KEY_BLOCK_MICROPHONE] ?: false,
            appSensorIdle = sensorIdle,
            appOpBlocks = opBlocks,
        )
    }

    private companion object {
        /** Package names cannot contain `|`, so it is a safe separator. */
        const val FIELD_SEP = "|"

        val KEY_BLOCK_ALL_SENSORS = booleanPreferencesKey("block_all_sensors")
        val KEY_BLOCK_CAMERA = booleanPreferencesKey("block_camera")
        val KEY_BLOCK_MICROPHONE = booleanPreferencesKey("block_microphone")
        val KEY_APP_SENSOR_IDLE = stringSetPreferencesKey("app_sensor_idle")
        val KEY_APP_OP_BLOCKS = stringSetPreferencesKey("app_op_blocks")
        val KEY_APP_OP_MANAGED = stringSetPreferencesKey("app_op_managed")
        val KEY_LAST_APPLIED_AT = stringPreferencesKey("last_applied_at")
    }
}
