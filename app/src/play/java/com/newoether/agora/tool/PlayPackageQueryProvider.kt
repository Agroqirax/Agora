package com.newoether.agora.tool

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Google Play flavor's package listing. QUERY_ALL_PACKAGES itself is still off the
 * table here (see [FdroidPackageQueryProvider]'s doc comment) — but a `<queries>`
 * intent signature matching `ACTION_MAIN`/`CATEGORY_LAUNCHER` is a separate, documented
 * exception: it makes every app with a launcher icon visible to
 * `queryIntentActivities()` with no permission review and no QUERY_ALL_PACKAGES
 * declaration, on every flavor (see the `<queries>` block in `AndroidManifest.xml`).
 * That's a narrower list than the fdroid flavor's — no system-only/background packages
 * without a launcher icon — but it's exactly the set of apps the user could tap from
 * their home screen, which covers what this tool is actually for (finding and opening
 * apps), so [isAvailable] can just be `true` here instead of the tool disappearing
 * entirely on this flavor.
 */
class PlayPackageQueryProvider(private val context: Context) : PackageQueryProvider {
    override fun isAvailable(): Boolean = true

    override fun listInstalledPackages(includeSystemApps: Boolean): List<InstalledPackageInfo> {
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
