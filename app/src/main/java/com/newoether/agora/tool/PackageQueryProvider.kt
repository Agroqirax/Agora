package com.newoether.agora.tool

/**
 * Lists (and looks up individual) installed packages on the device. Flavor-split exactly
 * like [com.newoether.agora.sandbox.SandboxManagerFactory]:
 * - fdroid → FdroidPackageQueryProvider (full device inventory; QUERY_ALL_PACKAGES is
 *   declared only in the fdroid flavor's manifest)
 * - play   → PlayPackageQueryProvider (launcher-icon apps only, via a permission-review-free
 *   `<queries>` exception rather than QUERY_ALL_PACKAGES — narrower, but always available)
 */
interface PackageQueryProvider {
    /** Whether this build/flavor can list installed packages at all. */
    fun isAvailable(): Boolean

    fun listInstalledPackages(includeSystemApps: Boolean): List<InstalledPackageInfo>

    /** Details for one already-known package, or null if it's not installed or not
     *  visible to Agora on this flavor. Backs `get_app_info`. */
    fun getPackageInfo(packageName: String): InstalledPackageInfo?
}

data class InstalledPackageInfo(
    val packageName: String,
    val appLabel: String,
    val versionName: String?,
    val isSystemApp: Boolean
)
