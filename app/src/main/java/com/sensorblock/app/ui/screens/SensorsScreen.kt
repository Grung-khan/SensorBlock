package com.sensorblock.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensorblock.app.domain.SensorGroup
import com.sensorblock.app.domain.SensorInfo
import com.sensorblock.app.ui.AppActions
import com.sensorblock.app.ui.UiState
import com.sensorblock.app.ui.components.DetailRow
import com.sensorblock.app.ui.components.EmptyState
import com.sensorblock.app.ui.components.GuardToggleCard
import com.sensorblock.app.ui.components.Panel
import com.sensorblock.app.ui.components.SectionHeader
import com.sensorblock.app.ui.theme.sensorBlockColors

@Composable
fun SensorsScreen(state: UiState, actions: AppActions, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var explainGrouped by remember { mutableStateOf(false) }
    var confirmEnable by remember { mutableStateOf(false) }

    val filtered = remember(state.sensors, query) {
        if (query.isBlank()) {
            state.sensors
        } else {
            state.sensors.filter { sensor ->
                sensor.name.contains(query, ignoreCase = true) ||
                    sensor.typeName.contains(query, ignoreCase = true) ||
                    sensor.vendor.contains(query, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = SensorGroup.INDEPENDENT.title,
                subtitle = SensorGroup.INDEPENDENT.explanation,
            )
        }

        item {
            GuardToggleCard(
                title = "Camera",
                subtitle = "All lenses, every app.",
                checked = state.policy.blockCamera,
                onCheckedChange = actions.setBlockCamera,
                icon = Icons.Filled.CameraAlt,
                blockedOverride = state.policy.blockAllSensors,
                enabled = state.capabilities.canBlockCamera || state.policy.blockCamera,
            )
        }

        item {
            GuardToggleCard(
                title = "Microphone",
                subtitle = "Recording refused; captures silenced.",
                checked = state.policy.blockMicrophone,
                onCheckedChange = actions.setBlockMicrophone,
                icon = Icons.Filled.Mic,
                blockedOverride = state.policy.blockAllSensors,
                enabled = state.capabilities.canBlockMicrophone || state.policy.blockMicrophone,
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(
                    title = SensorGroup.GROUPED.title,
                    subtitle = "${state.sensors.size} detected · blocked together",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { explainGrouped = true }) {
                    Icon(Icons.Filled.Info, contentDescription = "Why are these grouped?")
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = sensorBlockColors.protected.copy(
                        alpha = if (state.policy.blockAllSensors) 0.16f else 0.06f,
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Sensors,
                        contentDescription = null,
                        tint = sensorBlockColors.protected,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Block every sensor in this list",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "One switch controls them all — Android has no per-sensor privacy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.policy.blockAllSensors,
                        onCheckedChange = { wanted ->
                            if (wanted) confirmEnable = true else actions.setBlockAllSensors(false)
                        },
                        enabled = state.capabilities.canBlockAllSensors ||
                            state.policy.blockAllSensors,
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filter sensors") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }

        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    title = "No sensors match",
                    subtitle = if (state.sensors.isEmpty()) {
                        "This device reported no hardware sensors."
                    } else {
                        "Try a different filter."
                    },
                    icon = Icons.Filled.Sensors,
                )
            }
        } else {
            items(filtered, key = { "${it.handle}-${it.name}" }) { sensor ->
                SensorRow(
                    sensor = sensor,
                    blocked = state.policy.blockAllSensors,
                    onToggle = { wanted ->
                        if (wanted) confirmEnable = true else actions.setBlockAllSensors(false)
                    },
                    enabled = state.capabilities.canBlockAllSensors ||
                        state.policy.blockAllSensors,
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Panel {
                Text("Why can't I block one sensor only?", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Android 13 exposes per-sensor privacy for the camera and the " +
                        "microphone only. For everything else the framework offers a single " +
                        "device-wide switch, so SensorBlock engages that rather than " +
                        "pretending to offer granularity it cannot deliver. Blocking an " +
                        "individual accelerometer or gyroscope requires root or a custom ROM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (explainGrouped) {
        AlertDialog(
            onDismissRequest = { explainGrouped = false },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text("Grouped hardware sensors") },
            text = { Text(SensorGroup.GROUPED.explanation) },
            confirmButton = {
                TextButton(onClick = { explainGrouped = false }) { Text("Got it") }
            },
        )
    }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            icon = { Icon(Icons.Filled.Sensors, contentDescription = null) },
            title = { Text("Block every sensor?") },
            text = {
                Text(
                    "Android applies this device-wide, so all ${state.sensors.size} sensors " +
                        "stop at once. Auto-rotate, auto-brightness, proximity during calls " +
                        "and step counting will stop working until you turn it off.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnable = false
                    actions.setBlockAllSensors(true)
                }) { Text("Block all sensors") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SensorRow(
    sensor: SensorInfo,
    blocked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Panel(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = sensor.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${sensor.typeName} · ${sensor.vendor}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = blocked, onCheckedChange = onToggle, enabled = enabled)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))
        DetailRow("Range", "±${trimFloat(sensor.maxRange)}")
        DetailRow("Resolution", trimFloat(sensor.resolution))
        DetailRow("Power", "${trimFloat(sensor.powerMilliAmp)} mA")
        DetailRow("Min delay", "${sensor.minDelayMicros} µs")
        DetailRow(
            "Flags",
            buildList {
                if (sensor.isWakeUp) add("wake-up")
                if (sensor.isDynamic) add("dynamic")
                if (sensor.isContinuous) add("continuous")
                if (sensor.isOnChange) add("on-change")
                if (sensor.isOneShot) add("one-shot")
            }.joinToString(", ").ifEmpty { "—" },
        )
    }
}

internal fun trimFloat(value: Float): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    return when {
        value == 0f -> "0"
        kotlin.math.abs(value) >= 100 -> String.format("%.0f", value)
        kotlin.math.abs(value) >= 1 -> String.format("%.2f", value)
        else -> String.format("%.4f", value)
    }
}
