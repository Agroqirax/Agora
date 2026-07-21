package com.newoether.agora.tool

/**
 * Lists installed packages on the device. Flavor-split exactly like
 * [com.newoether.agora.sandbox.SandboxManagerFactory]:
 * - fdroid → FdroidPackageQueryProvider (full device inventory; QUERY_ALL_PACKAGES is
 *   declared only in the fdroid flavor's manifest)
 * - play   → PlayPackageQueryProvider (launcher-icon apps only, via a permission-review-free
 *   `<queries>` exception rather than QUERY_ALL_PACKAGES — narrower, but always available)
 */
interface PackageQueryProvider {
    /** Whether this build/flavor can list installed packages at all. */
    fun isAvailable(): Boolean

    /**
     * All (optionally filtered) installed packages, each already carrying every field
     * `list_installed_apps` reports — version name and system-app status are computed
     * per-package during the scan, since there's no separate per-package lookup tool
     * anymore.
     */
    fun listInstalledPackages(includeSystemApps: Boolean): List<InstalledPackageInfo>
}

data class InstalledPackageInfo(
    val packageName: String,
    val appLabel: String,
    val versionName: String?,
    val isSystemApp: Boolean
)
