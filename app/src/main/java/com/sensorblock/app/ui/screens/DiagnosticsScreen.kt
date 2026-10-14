package com.sensorblock.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensorblock.app.backend.SensorPrivacyClient
import com.sensorblock.app.domain.SelfTest
import com.sensorblock.app.ui.AppActions
import com.sensorblock.app.ui.UiState
import com.sensorblock.app.ui.components.DetailRow
import com.sensorblock.app.ui.components.EmptyState
import com.sensorblock.app.ui.components.Panel
import com.sensorblock.app.ui.components.SectionHeader
import com.sensorblock.app.ui.components.StatusChip
import com.sensorblock.app.ui.theme.sensorBlockColors

@Composable
fun DiagnosticsScreen(state: UiState, actions: AppActions, modifier: Modifier = Modifier) {
    val caps = state.capabilities
    val observed = state.observed

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Self-test",
                subtitle = "SensorBlock tries to use each thing and reports what the OS did.",
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.runSelfTest, enabled = !state.testing) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.testing) "Running…" else "Run self-test")
                }
                FilledTonalButton(onClick = actions.refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
            }
        }

        if (state.probes.isEmpty() && !state.testing) {
            item {
                EmptyState(
                    title = "No results yet",
                    subtitle = "Run the self-test once to prove the block is working.",
                    icon = Icons.Filled.HelpOutline,
                )
            }
        } else {
            items(state.probes, key = { it.name }) { probe -> ProbeCard(probe) }
        }

        item {
            SectionHeader(
                title = "Observed state",
                subtitle = "Read back from the OS, not from SensorBlock's intent.",
            )
        }

        item {
            Panel {
                DetailRow("All-sensor switch", triState(observed.allSensorPrivacy))
                DetailRow("Camera privacy toggle", triState(observed.cameraPrivacyToggle))
                DetailRow("Microphone privacy toggle", triState(observed.microphonePrivacyToggle))
                DetailRow(
                    "Per-app AppOps managed",
                    state.policy.appOpBlocks.keys.size.toString(),
                )
                DetailRow(
                    "Per-app sensor blocks",
                    state.policy.appSensorIdle.count { it.value }.toString(),
                )
                DetailRow("Read at", formatRelative(observed.readAtEpochMs))
            }
        }

        item {
            SectionHeader(title = "Privileged access")
        }

        item {
            Panel {
                DetailRow("Shizuku installed", yesNo(caps.shizukuInstalled))
                DetailRow("Shizuku server alive", yesNo(caps.shizukuBinderAlive))
                DetailRow("Shizuku version", caps.shizukuVersion.toString())
                DetailRow("Permission granted", yesNo(caps.shizukuPermissionGranted))
                DetailRow("Shell channel (uid 2000)", yesNo(caps.shellServiceReady))
                DetailRow("sensor_privacy reachable", yesNo(caps.sensorPrivacyServiceReachable))
                DetailRow(
                    "Toggleable sensors",
                    caps.toggleableSensors.joinToString { sensorName(it) }
                        .ifEmpty { "none reported" },
                )
            }
        }

        val report = state.lastReport
        if (report != null && report.actions.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Last reconcile pass",
                    subtitle = "${report.changed.size} change(s), ${report.failures.size} failure(s)",
                )
            }
            items(report.actions.size) { index ->
                val action = report.actions[index]
                Panel(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (action.success) Icons.Filled.CheckCircle
                            else Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = if (action.success) sensorBlockColors.protected
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = action.target,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(
                            text = if (action.desired) "on" else "off",
                            color = if (action.desired) sensorBlockColors.protected.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val note = action.detail ?: buildString {
                        if (action.changed) append("changed from ") else append("already ")
                        if (action.changed) {
                            append(if (action.previous == true) "on" else "off")
                        } else {
                            append("correct")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${action.backend.label} · $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Independent verification",
                subtitle = "The same checks from a desktop shell.",
            )
        }

        item {
            Panel {
                Text("ADB reference", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                CommandBlock(
                    listOf(
                        "# all-sensor privacy (nuclear switch, transaction 9)",
                        "adb shell service call sensor_privacy 9 i32 1",
                        "adb shell service call sensor_privacy 9 i32 0",
                        "",
                        "# camera / microphone privacy toggles",
                        "adb shell cmd sensor_privacy enable  0 camera",
                        "adb shell cmd sensor_privacy disable 0 camera",
                        "adb shell cmd sensor_privacy enable  0 microphone",
                        "",
                        "# per-app (uid 2000 identity) ",
                        "adb shell cmd sensorservice set-uid-state <pkg> idle",
                        "adb shell appops set <pkg> RECORD_AUDIO ignore",
                        "",
                        "# observe",
                        "adb shell dumpsys sensorservice | head -40",
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProbeCard(probe: SelfTest.Probe) {
    val extras = sensorBlockColors
    val (color, icon) = when (probe.verdict) {
        SelfTest.Verdict.PASS -> extras.protected to Icons.Filled.CheckCircle
        SelfTest.Verdict.BLOCKED -> extras.protected to Icons.Filled.Bolt
        SelfTest.Verdict.ALLOWED -> extras.caution to Icons.Filled.ReportProblem
        SelfTest.Verdict.INCONCLUSIVE -> MaterialTheme.colorScheme.primary to Icons.Filled.HelpOutline
        SelfTest.Verdict.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Filled.HelpOutline
        SelfTest.Verdict.ERROR -> MaterialTheme.colorScheme.error to Icons.Filled.ErrorOutline
    }
    val label = when (probe.verdict) {
        SelfTest.Verdict.PASS -> "works"
        SelfTest.Verdict.BLOCKED -> "blocked"
        SelfTest.Verdict.ALLOWED -> "not blocked"
        SelfTest.Verdict.INCONCLUSIVE -> "unclear"
        SelfTest.Verdict.UNSUPPORTED -> "unsupported"
        SelfTest.Verdict.ERROR -> "error"
    }

    Panel(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = probe.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                text = label,
                color = color.copy(alpha = 0.18f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = probe.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (probe.evidence != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(6.dp))
            Text(
                text = probe.evidence,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommandBlock(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        lines.forEach { line ->
            Text(
                text = line.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (line.startsWith("#")) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

private fun triState(value: Boolean?): String = when (value) {
    true -> "on"
    false -> "off"
    null -> "unknown"
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun sensorName(sensor: Int): String = when (sensor) {
    SensorPrivacyClient.SENSOR_CAMERA -> "camera"
    SensorPrivacyClient.SENSOR_MICROPHONE -> "microphone"
    else -> "sensor#$sensor"
}
