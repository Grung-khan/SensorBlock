package com.sensorblock.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensorblock.app.domain.AppEntry
import com.sensorblock.app.domain.AppOpKind
import com.sensorblock.app.ui.AppActions
import com.sensorblock.app.ui.UiState
import com.sensorblock.app.ui.components.EmptyState
import com.sensorblock.app.ui.components.Panel
import com.sensorblock.app.ui.components.SectionHeader
import com.sensorblock.app.ui.components.StatusChip
import com.sensorblock.app.ui.components.WarningBanner
import com.sensorblock.app.ui.theme.sensorBlockColors

@Composable
fun AppsScreen(state: UiState, actions: AppActions, modifier: Modifier = Modifier) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val filtered = remember(state.apps, state.appSearch) {
        val q = state.appSearch.trim()
        if (q.isEmpty()) state.apps
        else state.apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Per-app control",
                subtitle = "Narrow a block to one app instead of the whole device.",
            )
        }

        if (!state.capabilities.canControlApps) {
            item {
                WarningBanner(
                    "Per-app controls need Shizuku. Start the Shizuku server and grant " +
                        "SensorBlock permission to enable them.",
                )
            }
        }

        item {
            Column {
                OutlinedTextField(
                    value = state.appSearch,
                    onValueChange = actions.setAppSearch,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search apps") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.showSystemApps,
                        onClick = { actions.loadApps(false) },
                        label = { Text("User apps") },
                    )
                    FilterChip(
                        selected = state.showSystemApps,
                        onClick = { actions.loadApps(true) },
                        label = { Text("Include system") },
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    title = "No apps to show",
                    subtitle = "Nothing matched the current filter.",
                    icon = Icons.Filled.Apps,
                )
            }
        } else {
            items(filtered, key = { it.packageName }) { app ->
                AppCard(
                    app = app,
                    state = state,
                    actions = actions,
                    expanded = expanded[app.packageName] == true,
                    onExpand = { expanded[app.packageName] = expanded[app.packageName] != true },
                )
            }
        }

        item {
            Panel {
                Text("How per-app blocks work", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Camera, microphone, body sensors and activity are blocked with " +
                        "AppOps, which the OS enforces whenever the app asks for access. " +
                        "\u201CAll sensors\u201D additionally forces the app's uid idle in " +
                        "SensorService, so it receives no sensor events at all. The uid-idle " +
                        "override lives in memory, which is why SensorBlock keeps a small " +
                        "foreground service while any app is restricted this way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppCard(
    app: AppEntry,
    state: UiState,
    actions: AppActions,
    expanded: Boolean,
    onExpand: () -> Unit,
) {
    val extras = sensorBlockColors
    val blockedOps = state.policy.appOpBlocks[app.packageName] ?: emptySet()
    val sensorsIdle = state.policy.appSensorIdle[app.packageName] == true
    val anyBlocked = blockedOps.isNotEmpty() || sensorsIdle

    val observedOps = state.observed.blockedAppOps[app.packageName] ?: emptySet()
    val observedIdle = state.observed.appSensorIdle[app.packageName] == true
    val verified = observedOps == blockedOps && observedIdle == sensorsIdle

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (anyBlocked) extras.protected.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (anyBlocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = if (anyBlocked) extras.protected else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (anyBlocked) {
                    StatusChip(
                        text = if (verified) "Blocked" else "Applying…",
                        color = extras.protected.copy(alpha = 0.22f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(4.dp))

                    OpRow(
                        title = "All sensors",
                        subtitle = "Forces the app's uid idle: no sensor events at all.",
                        checked = sensorsIdle,
                        enabled = state.capabilities.canControlApps || sensorsIdle,
                        onCheckedChange = { actions.setAppSensors(it, app.packageName) },
                    )

                    AppOpKind.entries.forEach { op ->
                        OpRow(
                            title = op.label,
                            subtitle = op.blurb,
                            checked = op in blockedOps,
                            enabled = state.capabilities.canControlApps || op in blockedOps,
                            onCheckedChange = { actions.setAppOp(it, app.packageName, op) },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { actions.setAllForApp(true, app.packageName) },
                            enabled = state.capabilities.canControlApps || anyBlocked,
                        ) { Text("Block everything") }
                        TextButton(onClick = { actions.setAllForApp(false, app.packageName) }) {
                            Text("Restore")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
