package com.newoether.agora.tool

import android.content.Context
import android.content.pm.ApplicationInfo

class FdroidPackageQueryProvider(private val context: Context) : PackageQueryProvider {
    override fun isAvailable(): Boolean = true

    override fun listInstalledPackages(includeSystemApps: Boolean): List<InstalledPackageInfo> {
        val pm = context.packageManager
        return pm.getInstalledPackages(0).mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystemApps) return@mapNotNull null
            InstalledPackageInfo(
                packageName = pkg.packageName,
                appLabel = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg.packageName },
                versionName = pkg.versionName,
                isSystemApp = isSystem
            )
        }.sortedBy { it.appLabel.lowercase() }
    }
}
