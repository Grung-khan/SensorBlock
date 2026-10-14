package com.sensorblock.app.domain

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists installed apps for the per-app matrix.
 *
 * Only apps with a launcher entry are shown by default: those are the ones a
 * user actually wants to restrict. System components can be revealed on demand
 * because blocking a system package is rarely wise.
 */
class AppInventory(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    suspend fun launchable(includeSystem: Boolean): List<AppEntry> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        resolved
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { includeSystem || !it.isSystemApp() }
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = runCatching { packageManager.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    uid = info.uid,
                    isSystem = info.isSystemApp(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}
