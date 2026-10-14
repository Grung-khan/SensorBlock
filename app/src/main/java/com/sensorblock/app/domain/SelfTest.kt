package com.sensorblock.app.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.sensorblock.app.backend.SensorPrivacyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Proves the block actually works, instead of trusting the toggles.
 *
 * Each probe *tries to use the thing* and reports what the OS did. This is the
 * only honest way to answer "is it really blocked?" (AGENTS.md §7).
 */
class SelfTest(private val context: Context, private val engine: PolicyEngine) {

    enum class Verdict { PASS, BLOCKED, ALLOWED, INCONCLUSIVE, UNSUPPORTED, ERROR }

    data class Probe(
        val name: String,
        val verdict: Verdict,
        val detail: String,
        val evidence: String? = null,
    )

    suspend fun runAll(): List<Probe> = withContext(Dispatchers.IO) {
        buildList {
            add(guard("Privileged identity") { privilegedIdentityProbe() })
            add(guard("Sensor-privacy service") { sensorPrivacyServiceProbe() })
            add(guard("Sensor data") { sensorDataProbe() })
            add(guard("Camera") { cameraProbe() })
            add(guard("Microphone") { microphoneProbe() })
        }
    }

    /**
     * Runs one probe, converting any escaping throwable into an ERROR probe so a
     * single failure never hides the rest of the diagnostics.
     */
    private suspend fun guard(name: String, block: suspend () -> Probe): Probe = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "probe '$name' threw", t)
        Probe(
            name = name,
            verdict = Verdict.ERROR,
            detail = "Probe failed: ${t.javaClass.simpleName}: ${t.message}",
        )
    }

    // ---------------------------------------------------------------- probes ---

    /** Proves which uid the privileged channel actually runs as. */
    private suspend fun privilegedIdentityProbe(): Probe {
        val result = engine.shizuku.runShell("id")
        if (!result.ok) {
            return Probe(
                name = "Privileged identity",
                verdict = Verdict.UNSUPPORTED,
                detail = "Shizuku shell channel unavailable, so per-app blocks cannot be applied.",
                evidence = result.raw.trim().take(200),
            )
        }
        val stdout = result.stdoutTrimmed()
        val isShell = stdout.contains("uid=2000") || stdout.contains("(shell)")
        return Probe(
            name = "Privileged identity",
            verdict = if (isShell) Verdict.PASS else Verdict.INCONCLUSIVE,
            detail = if (isShell) {
                "Commands run as uid 2000 (shell), the identity that holds MANAGE_SENSOR_PRIVACY."
            } else {
                "Unexpected identity; privileged commands may not be permitted."
            },
            evidence = stdout,
        )
    }

    private fun sensorPrivacyServiceProbe(): Probe {
        val client = engine.shizuku.sensorPrivacy()
            ?: return Probe(
                name = "Sensor-privacy service",
                verdict = Verdict.UNSUPPORTED,
                detail = "Could not reach the sensor_privacy service. Is Shizuku authorised?",
            )
        return try {
            val all = client.isAllSensorsBlocked()
            val camera = client.isToggleEnabled(SensorPrivacyClient.SENSOR_CAMERA)
            val mic = client.isToggleEnabled(SensorPrivacyClient.SENSOR_MICROPHONE)
            Probe(
                name = "Sensor-privacy service",
                verdict = Verdict.PASS,
                detail = "Service reachable. All-sensor=${onOff(all)}, " +
                    "camera toggle=${onOff(camera)}, microphone toggle=${onOff(mic)}.",
                evidence = "isSensorPrivacyEnabled=$all, camera=$camera, microphone=$mic",
            )
        } catch (t: Throwable) {
            Probe(
                name = "Sensor-privacy service",
                verdict = Verdict.ERROR,
                detail = "Reading the service failed: ${t.javaClass.simpleName}.",
                evidence = t.message,
            )
        }
    }

    /**
     * Registers a listener on a fast sensor and counts samples. With the
     * all-sensor block active, `SensorService` stops delivering to every app.
     */
    private suspend fun sensorDataProbe(): Probe = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@withContext Probe(
                name = "Sensor data",
                verdict = Verdict.UNSUPPORTED,
                detail = "No SensorManager on this device.",
            )
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: manager.getSensorList(Sensor.TYPE_ALL).firstOrNull { !it.isWakeUpSensor }
            ?: return@withContext Probe(
                name = "Sensor data",
                verdict = Verdict.UNSUPPORTED,
                detail = "The device exposes no readable sensor.",
            )

        val thread = HandlerThread("self-test-sensors").apply { start() }
        val handler = Handler(thread.looper)
        var samples = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                samples++
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        try {
            val registered = manager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                handler,
            )
            if (!registered) {
                return@withContext Probe(
                    name = "Sensor data",
                    verdict = Verdict.ERROR,
                    detail = "Could not register a listener for ${sensor.name}.",
                )
            }
            delay(SENSOR_SAMPLE_WINDOW_MS)
            manager.unregisterListener(listener)
            Probe(
                name = "Sensor data",
                verdict = if (samples == 0) Verdict.BLOCKED else Verdict.ALLOWED,
                detail = if (samples == 0) {
                    "${sensor.name} delivered no samples in ${SENSOR_SAMPLE_WINDOW_MS} ms — sensors are blocked."
                } else {
                    "${sensor.name} delivered $samples samples — sensors are readable."
                },
                evidence = "sensor=${sensor.name} samples=$samples",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sensor probe failed", t)
            Probe(
                name = "Sensor data",
                verdict = Verdict.ERROR,
                detail = "Sensor probe failed: ${t.javaClass.simpleName}.",
                evidence = t.message,
            )
        } finally {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    /** Tries to open the back camera; a block shows up as CAMERA_DISABLED/SecurityException. */
    private suspend fun cameraProbe(): Probe = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return@withContext Probe(
                name = "Camera",
                verdict = Verdict.UNSUPPORTED,
                detail = "No CameraManager on this device.",
            )
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        } catch (t: Throwable) {
            null
        } ?: return@withContext Probe(
            name = "Camera",
            verdict = Verdict.UNSUPPORTED,
            detail = "No camera lens reported.",
        )

        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Probe(
                name = "Camera",
                verdict = Verdict.UNSUPPORTED,
                detail = "SensorBlock itself has not been granted the camera permission, " +
                    "so it cannot prove the camera is unreachable. Grant it to run this check.",
            )
        }

        try {
            val handlerThread = HandlerThread("self-test-camera").apply { start() }
            val handler = Handler(handlerThread.looper)
            val outcome = try {
                withTimeout(CAMERA_OPEN_TIMEOUT_MS) { openCamera(manager, cameraId, handler) }
            } finally {
                handlerThread.quitSafely()
            }
            when (outcome) {
                is CameraOutcome.Opened -> {
                    outcome.device.close()
                    Probe(
                        name = "Camera",
                        verdict = Verdict.ALLOWED,
                        detail = "Camera $cameraId opened successfully — the camera is reachable.",
                        evidence = "openCamera($cameraId) succeeded",
                    )
                }

                is CameraOutcome.Failed -> {
                    val blocked = outcome.reason == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ||
                        outcome.disconnected
                    Probe(
                        name = "Camera",
                        verdict = if (blocked) Verdict.BLOCKED else Verdict.ALLOWED,
                        detail = if (blocked) {
                            "The camera was refused by the system — the block is effective."
                        } else {
                            "The camera could not be opened, but not because of a policy block."
                        },
                        evidence = outcome.message,
                    )
                }
            }
        } catch (t: TimeoutCancellationException) {
            Probe(
                name = "Camera",
                verdict = Verdict.INCONCLUSIVE,
                detail = "The camera did not respond within ${CAMERA_OPEN_TIMEOUT_MS} ms.",
            )
        } catch (t: SecurityException) {
            Probe(
                name = "Camera",
                verdict = Verdict.BLOCKED,
                detail = "The system threw SecurityException when opening the camera — blocked.",
                evidence = t.message,
            )
        } catch (t: CameraAccessException) {
            Probe(
                name = "Camera",
                verdict = if (t.reason == CameraAccessException.CAMERA_DISABLED) {
                    Verdict.BLOCKED
                } else {
                    Verdict.INCONCLUSIVE
                },
                detail = "CameraAccessException with reason ${t.reason}.",
                evidence = t.message,
            )
        }
    }

    /** Records a short buffer; blocked recordings come back as pure silence. */
    private suspend fun microphoneProbe(): Probe = withContext(Dispatchers.IO) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val muted = runCatching { audioManager?.isMicrophoneMute == true }.getOrDefault(false)

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Probe(
                name = "Microphone",
                verdict = Verdict.UNSUPPORTED,
                detail = "SensorBlock itself has not been granted the microphone permission, " +
                    "so it cannot prove recordings are silenced." +
                    if (muted) " The system does report the microphone as muted." else "",
            )
        }

        val sampleRate = 44_100
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Probe(
                    name = "Microphone",
                    verdict = Verdict.BLOCKED,
                    detail = "AudioRecord refused to initialise — the microphone is unavailable.",
                    evidence = "state=${record.state}",
                )
            }
            val buffer = ShortArray(minBuffer)
            record.startRecording()
            // Discard the first buffers: a freshly started AudioRecord returns
            // silence while the mic warms up, which would look like a block.
            val warmup = ShortArray(minBuffer)
            repeat(WARMUP_BUFFERS) { record.read(warmup, 0, warmup.size) }
            var peak = 0
            var totalRead = 0
            val deadline = SystemClock.elapsedRealtime() + RECORD_WINDOW_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    totalRead += read
                    val localPeak = buffer.take(read).maxOf { kotlin.math.abs(it.toInt()) }
                    if (localPeak > peak) peak = localPeak
                }
            }

            Probe(
                name = "Microphone",
                verdict = if (peak == 0) Verdict.BLOCKED else Verdict.ALLOWED,
                detail = if (peak == 0) {
                    "A $RECORD_WINDOW_MS ms capture after warm-up returned pure silence — recordings are blocked."
                } else {
                    "A $RECORD_WINDOW_MS ms capture returned live audio (peak $peak) — the microphone is open."
                },
                evidence = "read=$totalRead peak=$peak micMuted=$muted",
            )
        } catch (t: SecurityException) {
            Probe(
                name = "Microphone",
                verdict = Verdict.BLOCKED,
                detail = "Recording was refused with SecurityException — blocked.",
                evidence = t.message,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "microphone probe failed", t)
            Probe(
                name = "Microphone",
                verdict = Verdict.ERROR,
                detail = "Microphone probe failed: ${t.javaClass.simpleName}.",
                evidence = t.message,
            )
        } finally {
            runCatching {
                record?.stop()
            }
            runCatching {
                record?.release()
            }
        }
    }

    // --------------------------------------------------------------- helpers ---

    private sealed interface CameraOutcome {
        data class Opened(val device: CameraDevice) : CameraOutcome
        data class Failed(val reason: Int, val disconnected: Boolean, val message: String) :
            CameraOutcome
    }

    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler,
    ): CameraOutcome =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) continuation.resume(CameraOutcome.Opened(camera))
                    else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resume(
                            CameraOutcome.Failed(
                                reason = CameraDevice.StateCallback.ERROR_CAMERA_DISABLED,
                                disconnected = true,
                                message = "onDisconnected",
                            ),
                        )
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resume(
                            CameraOutcome.Failed(error, disconnected = false, message = "onError($error)"),
                        )
                    }
                }
            }
            continuation.invokeOnCancellation { /* the state callback guards on isActive */ }
            try {
                manager.openCamera(cameraId, callback, handler)
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(t))
                }
            }
        }

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    private companion object {
        const val TAG = "SelfTest"
        const val SENSOR_SAMPLE_WINDOW_MS = 1_200L
        const val CAMERA_OPEN_TIMEOUT_MS = 2_500L
        const val RECORD_WINDOW_MS = 1_500L
        const val WARMUP_BUFFERS = 4
    }
}
