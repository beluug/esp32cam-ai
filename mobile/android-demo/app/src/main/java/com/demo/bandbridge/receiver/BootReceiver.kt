package com.demo.bandbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.demo.bandbridge.service.KeepAliveService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            KeepAliveService.start(context.applicationContext)
        }
    }
}
