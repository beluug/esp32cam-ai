package com.demo.bandbridge

import android.app.Application
import com.demo.bandbridge.service.KeepAliveService

class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            KeepAliveService.start(this)
        }
    }
}
