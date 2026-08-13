package com.timelimittracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val serviceIntent = Intent(context, UsageMonitorService::class.java).apply {
            action = UsageMonitorService.ACTION_REMINDER
            putExtra(UsageMonitorService.EXTRA_PACKAGE_NAME, packageName)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
