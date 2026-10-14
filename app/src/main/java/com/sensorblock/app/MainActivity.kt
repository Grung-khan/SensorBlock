package com.sensorblock.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorblock.app.ui.AppActions
import com.sensorblock.app.ui.MainViewModel
import com.sensorblock.app.ui.screens.ApplyingIndicator
import com.sensorblock.app.ui.screens.AppsScreen
import com.sensorblock.app.ui.screens.DiagnosticsScreen
import com.sensorblock.app.ui.screens.HomeScreen
import com.sensorblock.app.ui.screens.SensorsScreen
import com.sensorblock.app.ui.theme.SensorBlockTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SensorBlockTheme {
                SensorBlockRoot(viewModel)
            }
        }
    }
}

private enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Filled.Shield),
    SENSORS("Sensors", Icons.Filled.Sensors),
    APPS("Apps", Icons.Filled.Apps),
    DIAGNOSTICS("Diagnostics", Icons.Filled.BugReport),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorBlockRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }

    val actions = remember(viewModel) {
        AppActions(
            setBlockAllSensors = viewModel::setBlockAllSensors,
            setBlockCamera = viewModel::setBlockCamera,
            setBlockMicrophone = viewModel::setBlockMicrophone,
            setAppOp = viewModel::setAppOp,
            setAppSensors = viewModel::setAppSensors,
            setAllForApp = viewModel::setAllForApp,
            loadApps = viewModel::loadApps,
            setAppSearch = viewModel::setAppSearch,
            reapply = { viewModel.applyNow(reason = null) },
            refresh = viewModel::refreshAll,
            requestShizuku = viewModel::requestShizuku,
            openShizuku = viewModel::openShizuku,
            openDeveloperOptions = viewModel::openDeveloperOptions,
            requestAddTile = viewModel::requestAddTile,
            runSelfTest = viewModel::runSelfTest,
            consumeMessage = { viewModel.message(null) },
        )
    }

    // Ask for notification permission so the enforcement service can show
    // itself; the blocks work either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        actions.consumeMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (destination) {
                            Destination.HOME -> "SensorBlock"
                            else -> destination.label
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = actions.refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh state")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = destination,
                label = "destination",
            ) { target ->
                when (target) {
                    Destination.HOME -> HomeScreen(state, actions)
                    Destination.SENSORS -> SensorsScreen(state, actions)
                    Destination.APPS -> AppsScreen(state, actions)
                    Destination.DIAGNOSTICS -> DiagnosticsScreen(state, actions)
                }
            }
            if (state.applying) {
                ApplyingIndicator(visible = true, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter))
            }
        }
    }
}
