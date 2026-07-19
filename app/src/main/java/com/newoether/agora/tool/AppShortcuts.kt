package com.newoether.agora.tool

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.net.Uri
import com.newoether.agora.util.DebugLog
import org.xmlpull.v1.XmlPullParser

/**
 * Shared by [PackageQueryToolProvider] (`get_app_info` surfaces these read-only, for
 * discovery) and [AppLaunchToolProvider] (`open_app` fires the one the model picked).
 * Only sees **static shortcuts** — the ones an app declares up front in
 * `res/xml/shortcuts.xml` (e.g. a messaging app's "New conversation"). Actually invoking
 * a shortcut through the platform's shortcut API ([android.content.pm.LauncherApps])
 * requires the `ACCESS_SHORTCUTS` permission, which is signature|privileged and granted
 * in practice only to the device's default launcher — not something a regular app can
 * hold or reasonably request. What this does instead is parse the target app's declared
 * shortcuts resource directly (public, unprivileged manifest/resource data) and hand
 * back the plain [Intent] each shortcut declares, which needs no special permission
 * beyond the target being launchable at all. **Dynamic and pinned shortcuts** — the ones
 * apps push at runtime via [android.content.pm.ShortcutManager] (e.g. a chat app's most
 * recent conversations) — live in the system shortcut service, not the manifest, and
 * aren't visible this way; there's no unprivileged API to read or invoke those. That's a
 * real platform ceiling, not a missing feature here.
 */
internal object AppShortcuts {

    data class StaticShortcut(
        val id: String,
        val shortLabel: String,
        val longLabel: String?,
        val enabled: Boolean,
        val disabledMessage: String?,
        val intents: List<Intent>
    )

    /** Finds the activity that declares `<meta-data android:name="android.app.shortcuts" .../>`
     *  and parses the XML resource it points to. Throws [PackageManager.NameNotFoundException]
     *  if [packageName] isn't installed or isn't visible to Agora (same MAIN/LAUNCHER
     *  `<queries>` exception `list_installed_apps` relies on covers this in practice, since
     *  an app with a shortcuts.xml almost always also has a launcher icon). */
    fun readStatic(pm: PackageManager, packageName: String): List<StaticShortcut> {
        val pkgInfo = pm.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        )
        val appInfo = pkgInfo.applicationInfo ?: return emptyList()
        val activities = pkgInfo.activities ?: return emptyList()

        val resId = activities.firstNotNullOfOrNull { activity ->
            activity.metaData?.getInt(META_DATA_SHORTCUTS, 0)?.takeIf { it != 0 }
        } ?: return emptyList()

        val resources = pm.getResourcesForApplication(appInfo)
        return try {
            parse(resources, resId, packageName)
        } catch (e: Exception) {
            DebugLog.e("AppShortcuts", "Failed to parse static shortcuts for $packageName", e)
            emptyList()
        }
    }

    private fun parse(resources: Resources, resId: Int, targetPackage: String): List<StaticShortcut> {
        val parser: XmlResourceParser = resources.getXml(resId)
        val result = mutableListOf<StaticShortcut>()

        var id: String? = null
        var shortLabel: String? = null
        var longLabel: String? = null
        var enabled = true
        var disabledMessage: String? = null
        val intents = mutableListOf<Intent>()

        fun reset() {
            id = null; shortLabel = null; longLabel = null; enabled = true
            disabledMessage = null; intents.clear()
        }

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "shortcut" -> {
                        reset()
                        id = parser.getAttributeValue(ANDROID_NS, "shortcutId")
                        enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                        shortLabel = resolveStringAttr(resources, parser, "shortcutShortLabel")
                        longLabel = resolveStringAttr(resources, parser, "shortcutLongLabel")
                        disabledMessage = resolveStringAttr(resources, parser, "shortcutDisabledMessage")
                    }
                    "intent" -> {
                        val action = parser.getAttributeValue(ANDROID_NS, "action")
                        if (action != null) {
                            val targetClass = parser.getAttributeValue(ANDROID_NS, "targetClass")
                            val targetPkg = parser.getAttributeValue(ANDROID_NS, "targetPackage") ?: targetPackage
                            val data = parser.getAttributeValue(ANDROID_NS, "data")
                            val intent = Intent(action)
                            if (targetClass != null) {
                                intent.component = ComponentName(targetPkg, targetClass)
                            } else {
                                intent.setPackage(targetPkg)
                            }
                            data?.let { intent.data = Uri.parse(it) }
                            intents += intent
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "shortcut") {
                    val currentId = id
                    if (currentId != null) {
                        result += StaticShortcut(
                            id = currentId,
                            shortLabel = shortLabel ?: currentId,
                            longLabel = longLabel,
                            enabled = enabled,
                            disabledMessage = disabledMessage,
                            intents = intents.toList()
                        )
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    /** Static-shortcut labels must reference a `@string/...` resource (a hard platform
     *  requirement, so they can be localized) — resolve via the resource id rather than
     *  reading the raw compiled attribute value, which for a reference type isn't the string. */
    private fun resolveStringAttr(resources: Resources, parser: XmlResourceParser, attr: String): String? {
        val resId = parser.getAttributeResourceValue(ANDROID_NS, attr, 0)
        if (resId != 0) {
            return try { resources.getString(resId) } catch (_: Exception) { null }
        }
        return parser.getAttributeValue(ANDROID_NS, attr)
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val META_DATA_SHORTCUTS = "android.app.shortcuts"
}
