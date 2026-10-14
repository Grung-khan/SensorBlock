package com.sensorblock.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensorblock.app.domain.Issue
import com.sensorblock.app.ui.AppActions
import com.sensorblock.app.ui.UiState
import com.sensorblock.app.ui.components.DetailRow
import com.sensorblock.app.ui.components.GuardToggleCard
import com.sensorblock.app.ui.components.Panel
import com.sensorblock.app.ui.components.ProtectionHero
import com.sensorblock.app.ui.components.SectionHeader
import com.sensorblock.app.ui.components.StatusChip
import com.sensorblock.app.ui.components.WarningBanner
import com.sensorblock.app.ui.theme.sensorBlockColors

@Composable
fun HomeScreen(state: UiState, actions: AppActions, modifier: Modifier = Modifier) {
    val extras = sensorBlockColors
    var confirmMaster by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProtectionHero(
            headline = headline(state),
            detail = heroDetail(state),
            protected = state.policy.blockAllSensors ||
                state.policy.blockCamera ||
                state.policy.blockMicrophone,
            chips = listOf(
                "Sensors" to state.policy.blockAllSensors,
                "Camera" to state.policy.blockCamera,
                "Microphone" to state.policy.blockMicrophone,
            ),
        )

        BackendPanel(state, actions)

        SectionHeader(
            title = "Device-wide switches",
            subtitle = "These apply to every app and to the system itself.",
        )

        GuardToggleCard(
            title = "All sensors, camera and microphone",
            subtitle = "The complete kill switch. Blocks every sensor and silences recordings.",
            checked = state.policy.blockAllSensors,
            onCheckedChange = { wanted ->
                if (wanted) confirmMaster = true else actions.setBlockAllSensors(false)
            },
            icon = Icons.Filled.PowerSettingsNew,
            enabled = state.capabilities.canBlockAllSensors || state.policy.blockAllSensors,
            supportingText = when {
                state.capabilities.canBlockAllSensors -> null
                else -> "Needs Shizuku — the all-sensor switch is not reachable otherwise."
            },
            onInfo = null,
        )

        GuardToggleCard(
            title = "Camera",
            subtitle = "Every lens, for every app.",
            checked = state.policy.blockCamera,
            onCheckedChange = actions.setBlockCamera,
            icon = Icons.Filled.CameraAlt,
            blockedOverride = state.policy.blockAllSensors,
            enabled = state.capabilities.canBlockCamera || state.policy.blockCamera,
            supportingText = supportingCamera(state),
        )

        GuardToggleCard(
            title = "Microphone",
            subtitle = "Recording is refused and existing captures are silenced.",
            checked = state.policy.blockMicrophone,
            onCheckedChange = actions.setBlockMicrophone,
            icon = Icons.Filled.Mic,
            blockedOverride = state.policy.blockAllSensors,
            enabled = state.capabilities.canBlockMicrophone || state.policy.blockMicrophone,
            supportingText = supportingMicrophone(state),
        )

        if (state.issues.isNotEmpty()) {
            SectionHeader(
                title = "Attention",
                subtitle = "Only real, verified problems are listed here.",
            )
            state.issues.forEach { IssueRow(it) }
        }

        Panel {
            Text(
                text = "Layered enforcement",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "SensorBlock applies every primitive that does not conflict, so a " +
                    "block survives if one layer is bypassed. The camera, for example, is " +
                    "blocked by the privacy toggle, by device policy, and — when enabled — " +
                    "per app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            DetailRow(
                label = "Last applied",
                value = formatRelative(state.lastAppliedAt),
            )
        }
    }

    val pending = confirmMaster
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmMaster = null },
            icon = { Icon(Icons.Filled.Block, contentDescription = null) },
            title = { Text("Block everything?") },
            text = {
                Text(
                    "This stops every sensor, refuses the camera and silences the " +
                        "microphone for every app and for the system. Your device may " +
                        "lose auto-rotate, auto-brightness, proximity-during-call and " +
                        "step counting until you turn it back off.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmMaster = null
                    actions.setBlockAllSensors(true)
                }) { Text("Block everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMaster = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BackendPanel(state: UiState, actions: AppActions) {
    val caps = state.capabilities
    val extras = sensorBlockColors

    Panel {
        SectionHeader(title = "Privileged access")

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Shizuku shell", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        caps.sensorPrivacyServiceReachable && caps.shellServiceReady ->
                            "Connected as uid 2000. Every block is available."
                        caps.shizukuPermissionGranted ->
                            "Authorised, but the shell channel is still starting."
                        caps.shizukuBinderAlive ->
                            "Running — SensorBlock needs your permission."
                        caps.shizukuInstalled ->
                            "Installed, but the Shizuku server is not running."
                        else -> "Not installed. Only the device-policy camera block is available."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(
                text = if (caps.sensorPrivacyServiceReachable) "Ready" else "Off",
                color = if (caps.sensorPrivacyServiceReachable) extras.protected.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = actions.requestShizuku) {
                Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (caps.shizukuPermissionGranted) "Re-check" else "Grant permission")
            }
            TextButton(onClick = actions.openShizuku) { Text("Open Shizuku") }
        }

        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ShieldMoon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Device policy", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        caps.deviceOwner ->
                            "Device owner. Hard camera and microphone restrictions are enforced by the system."
                        caps.deviceAdminActive ->
                            "Device admin. The camera is blocked by CameraService itself. " +
                                "Microphone hardening needs device owner."
                        else ->
                            "Not active. Enabling device admin adds a camera block that " +
                                "survives independently of Shizuku."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(
                text = when {
                    caps.deviceOwner -> "Owner"
                    caps.deviceAdminActive -> "Admin"
                    else -> "Off"
                },
                color = if (caps.deviceAdminActive) extras.protected.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun IssueRow(issue: Issue) {
    val extras = sensorBlockColors
    val color: Color = when (issue.severity) {
        Issue.Severity.ERROR -> MaterialTheme.colorScheme.error
        Issue.Severity.WARNING -> extras.caution
        Issue.Severity.INFO -> MaterialTheme.colorScheme.primary
    }
    Panel(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (issue.severity == Issue.Severity.INFO) Icons.Filled.CloudSync
                else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = issue.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = issue.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (issue.fix != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = issue.fix,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Shown while a reconcile pass is running, so the UI never lies about state. */
@Composable
fun ApplyingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Applying and verifying…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun headline(state: UiState): String = when {
    state.policy.blockAllSensors -> "Everything is blocked"
    state.policy.activeSwitchCount > 0 -> "Partly blocked"
    else -> "Exposed"
}

private fun heroDetail(state: UiState): String = when {
    state.policy.blockAllSensors ->
        "Every sensor stopped, the camera refused and recordings silenced. " +
            "No app can bypass this without root."
    state.policy.activeSwitchCount > 0 ->
        "Some protections are on. The rows below show exactly what is and is not covered."
    else ->
        "All sensors, the camera and the microphone are available to every app. " +
            "Turn on a switch below to block them."
}

private fun supportingCamera(state: UiState): String? = when {
    state.capabilities.deviceAdminActive && state.capabilities.sensorPrivacyServiceReachable ->
        "Two independent layers active: camera privacy toggle and device policy."
    state.capabilities.deviceAdminActive -> "Enforced by device policy (CameraService)."
    !state.capabilities.canBlockCamera -> "Needs Shizuku or device admin."
    else -> null
}

private fun supportingMicrophone(state: UiState): String? = when {
    state.capabilities.deviceOwner -> "Both the privacy toggle and the unmute restriction are set."
    !state.capabilities.canBlockMicrophone ->
        "Needs Shizuku. The unmute restriction alone requires device owner."
    else -> null
}

internal fun formatRelative(epochMs: Long): String {
    if (epochMs <= 0L) return "never"
    val delta = System.currentTimeMillis() - epochMs
    return when {
        delta < 5_000 -> "just now"
        delta < 60_000 -> "${delta / 1_000}s ago"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}
