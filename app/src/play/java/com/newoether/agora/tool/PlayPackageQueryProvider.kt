package com.newoether.agora.tool

class PlayPackageQueryProvider : PackageQueryProvider {
    override fun isAvailable(): Boolean = false
    override fun listInstalledPackages(includeSystemApps: Boolean): List<InstalledPackageInfo> = emptyList()
}
