package com.sensorblock.app.ui

import com.sensorblock.app.domain.AppOpKind

/**
 * All user-initiated operations, collected into one object so screens stay
 * stateless and easy to preview.
 */
data class AppActions(
    val setBlockAllSensors: (Boolean) -> Unit = {},
    val setBlockCamera: (Boolean) -> Unit = {},
    val setBlockMicrophone: (Boolean) -> Unit = {},
    val setAppOp: (Boolean, String, AppOpKind) -> Unit = { _, _, _ -> },
    val setAppSensors: (Boolean, String) -> Unit = { _, _ -> },
    val setAllForApp: (Boolean, String) -> Unit = { _, _ -> },
    val loadApps: (Boolean) -> Unit = {},
    val setAppSearch: (String) -> Unit = {},
    val reapply: () -> Unit = {},
    val refresh: () -> Unit = {},
    val requestShizuku: () -> Unit = {},
    val openShizuku: () -> Unit = {},
    val openDeveloperOptions: () -> Unit = {},
    val requestAddTile: () -> Unit = {},
    val runSelfTest: () -> Unit = {},
    val consumeMessage: () -> Unit = {},
)
