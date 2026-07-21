package com.newoether.agora.tool

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Lists installed packages on the device: apps with a home-screen (launcher) icon.
 * Backed by the platform's package-visibility exception for a `<queries>` entry matching
 * `ACTION_MAIN`/`CATEGORY_LAUNCHER` (see that block in AndroidManifest.xml) — this makes
 * every launcher-icon app visible to `queryIntentActivities()` with no permission review
 * and no `QUERY_ALL_PACKAGES` declaration, on every flavor. That's narrower than "every
 * installed package" (no system-only/background packages without a launcher icon), but
 * it's exactly the set of apps a person could tap from their home screen — which is what
 * `list_installed_apps`/`open_app` are for — and it's why this one implementation now
 * covers both the fdroid and play flavors instead of splitting per flavor: Google
 * requires an explicit declared-use justification for `QUERY_ALL_PACKAGES` that isn't
 * worth pursuing for a tool whose whole job is finding launchable apps anyway.
 */
class PackageQueryProvider(private val context: Context) {

    fun listInstalledPackages(includeSystemApps: Boolean): List<InstalledPackageInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .mapNotNull { appInfo ->
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystemApps) return@mapNotNull null
                val versionName = try {
                    pm.getPackageInfo(appInfo.packageName, 0).versionName
                } catch (_: Exception) { null }
                InstalledPackageInfo(
                    packageName = appInfo.packageName,
                    appLabel = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { appInfo.packageName },
                    versionName = versionName,
                    isSystemApp = isSystem
                )
            }
            .sortedBy { it.appLabel.lowercase() }
    }
}

data class InstalledPackageInfo(
    val packageName: String,
    val appLabel: String,
    val versionName: String?,
    val isSystemApp: Boolean
)
