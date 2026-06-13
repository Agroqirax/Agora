package com.newoether.agora

import android.app.Application
import com.newoether.agora.util.CrashReporter

/**
 * Application entry point. Installs the crash reporter before any other component runs so
 * that crashes occurring during startup are captured as well.
 */
class AgoraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
