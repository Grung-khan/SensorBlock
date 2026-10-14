package com.sensorblock.app.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.sensorblock.app.R
import com.sensorblock.app.SensorBlockApp
import com.sensorblock.app.domain.Capabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Quick Settings master switch.
 *
 * One tap flips [com.sensorblock.app.domain.DesiredPolicy.blockAllSensors] and
 * re-runs the normal reconcile, so it blocks every sensor *and* the camera and
 * the microphone — the master switch is a superset (see Models.kt). It writes the
 * same policy the Home screen uses, so the two controls always agree.
 *
 * The tile is shown ACTIVE while blocking, INACTIVE otherwise, and UNAVAILABLE
 * when Shizuku is not reachable (nothing could be enforced). It does not require
 * the device to be unlocked: enforcement is a background binder call with no UI.
 */
class MasterTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        busy = true
        scope.launch {
            try {
                val app = SensorBlockApp.from(this@MasterTileService)
                val store = app.policyStore
                val next = !store.current().blockAllSensors
                store.update { it.copy(blockAllSensors = next) }
                val report = app.policyEngine.apply(store.current())
                store.noteApplied(System.currentTimeMillis())
                if (report.failures.isNotEmpty()) {
                    Log.w(TAG, "master toggle had ${report.failures.size} failed step(s)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "master toggle failed", t)
            } finally {
                busy = false
                refresh()
            }
        }
    }

    /** Reads the device state and paints the tile to match reality. */
    private fun refresh() {
        scope.launch {
            val app = SensorBlockApp.from(this@MasterTileService)
            val policy = app.policyStore.current()
            val caps = runCatching { app.policyEngine.capabilities() }
                .getOrDefault(Capabilities())
            val observed = runCatching { app.policyEngine.observe(policy).allSensorPrivacy }
                .getOrNull()
            val blocked = observed ?: policy.blockAllSensors

            val tile = qsTile ?: return@launch
            tile.state = when {
                !caps.sensorPrivacyServiceReachable -> Tile.STATE_UNAVAILABLE
                blocked -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.label = getString(
                when {
                    !caps.sensorPrivacyServiceReachable -> R.string.tile_master_unavailable
                    blocked -> R.string.tile_master_on
                    else -> R.string.tile_master_off
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.stateDescription = getString(R.string.tile_master_desc)
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MasterTileService"
    }
}
