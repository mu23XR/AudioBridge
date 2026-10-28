package com.cuscus.wifiaudiostreaming

import android.app.Application
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics

class AudioBridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeDiagnostics.initialize(this)
        VolumeMemory.initialize(this)
    }
}
