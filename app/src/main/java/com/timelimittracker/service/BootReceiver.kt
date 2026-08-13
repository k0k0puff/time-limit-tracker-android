package com.timelimittracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val serviceIntent = Intent(context, UsageMonitorService::class.java).apply {
            action = UsageMonitorService.ACTION_BOOT
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
