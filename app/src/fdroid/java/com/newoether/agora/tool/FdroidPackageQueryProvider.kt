package com.newoether.agora.tool

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

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

    override fun getPackageInfo(packageName: String): InstalledPackageInfo? {
        val pm = context.packageManager
        return try {
            val pkg = pm.getPackageInfo(packageName, 0)
            val appInfo = pkg.applicationInfo ?: return null
            InstalledPackageInfo(
                packageName = pkg.packageName,
                appLabel = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg.packageName },
                versionName = pkg.versionName,
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
