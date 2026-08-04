package com.newoether.agora

import android.app.Application
import com.newoether.agora.di.AppContainer
import com.newoether.agora.util.CrashReporter

/**
 * Application entry point. Installs the crash reporter before any other component runs so
 * that crashes occurring during startup are captured as well.
 *
 * Owns the process-scoped [AppContainer] so that shared singletons (data layer, providers,
 * generation infrastructure) outlive any single Activity/ViewModel and are reachable from
 * background components (Workers, scheduled task execution) — not just the UI.
 */
class AgoraApplication : Application() {
    /** Process-lifetime dependency container. The single source of shared singletons. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Orphaned Run recovery is the startup barrier for every generator and scheduler.
        container.startProcessServices()
    }
}
