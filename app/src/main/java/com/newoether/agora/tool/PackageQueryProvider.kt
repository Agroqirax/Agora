package com.newoether.agora.tool

/**
 * Lists installed packages on the device. Flavor-split exactly like
 * [com.newoether.agora.sandbox.SandboxManagerFactory]:
 * - fdroid → FdroidPackageQueryProvider (real implementation; QUERY_ALL_PACKAGES is
 *   declared only in the fdroid flavor's manifest)
 * - play   → PlayPackageQueryProvider (always unavailable — Google Play requires an
 *   explicit declared-use justification for QUERY_ALL_PACKAGES that isn't worth pursuing
 *   for a single assistant tool call; whoever wants to fight that fight for the Play
 *   build can swap this out later)
 */
interface PackageQueryProvider {
    /** Whether this build/flavor can list installed packages at all. */
    fun isAvailable(): Boolean

    fun listInstalledPackages(includeSystemApps: Boolean): List<InstalledPackageInfo>
}

data class InstalledPackageInfo(
    val packageName: String,
    val appLabel: String,
    val versionName: String?,
    val isSystemApp: Boolean
)
