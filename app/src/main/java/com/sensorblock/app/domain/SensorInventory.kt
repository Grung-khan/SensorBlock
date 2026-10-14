package com.sensorblock.app.domain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * Enumerates every sensor the device exposes via `SensorManager`.
 *
 * Note (AGENTS.md §3.5): `SensorManager` is only the *hardware* sensor list.
 * The camera and the microphone are not sensors here — they are covered by the
 * sensor-privacy service and are modelled separately as [BlockTarget]s.
 */
class SensorInventory(private val context: Context) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Every sensor, stable order: wake-up sensors first, then by type then name. */
    fun all(): List<SensorInfo> {
        val manager = sensorManager ?: return emptyList()
        val sensors = try {
            manager.getSensorList(Sensor.TYPE_ALL)
        } catch (t: Throwable) {
            emptyList()
        }
        return sensors
            .map { it.toInfo() }
            .sortedWith(
                compareByDescending<SensorInfo> { it.isWakeUp }
                    .thenBy { it.type }
                    .thenBy { it.name },
            )
    }

    /** Convenience for the UI: the number of distinct vendor-provided sensors. */
    fun count(): Int = all().count { !it.isDynamic }

    private fun Sensor.toInfo(): SensorInfo = SensorInfo(
        handle = handle,
        name = name,
        vendor = vendor,
        version = version,
        type = type,
        typeName = typeName(this),
        maxRange = maxRange,
        resolution = resolution,
        powerMilliAmp = power,
        minDelayMicros = minDelay,
        isWakeUp = isWakeUpSensor,
        isDynamic = isDynamicSensor,
        reportingMode = reportingMode,
    )

    companion object {
        /** `Sensor.getStringType()` is API 20+, but returns null for private types. */
        fun typeName(sensor: Sensor): String {
            val fromPlatform = try {
                sensor.stringType
            } catch (t: Throwable) {
                null
            }
            if (!fromPlatform.isNullOrBlank()) return fromPlatform
            return "android.sensor.type_${sensor.type}"
        }
    }
}
