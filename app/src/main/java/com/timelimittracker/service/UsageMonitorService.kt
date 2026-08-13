package com.timelimittracker.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

// Stub — will be replaced in Task 7
class UsageMonitorService : Service() {
    companion object {
        const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
        const val ACTION_BOOT = "com.timelimittracker.ACTION_BOOT"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
