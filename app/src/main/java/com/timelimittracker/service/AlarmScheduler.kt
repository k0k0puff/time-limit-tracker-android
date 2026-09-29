package com.timelimittracker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AlarmScheduler {
    private const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
    private const val EXTRA_PACKAGE_NAME = "package_name"
    private const val ACTION_KEEP_ALIVE = "com.timelimittracker.ACTION_KEEP_ALIVE"
    private const val KEEP_ALIVE_REQUEST_CODE = 99999
    private const val KEEP_ALIVE_INTERVAL_MS = 2 * 60 * 1000L  // 2 minutes

    /**
     * Schedule a repeating keepalive alarm that restarts the service if killed.
     * Uses setExactAndAllowWhileIdle so it fires even in Doze mode.
     * Each alarm is one-shot; the service reschedules on every onStartCommand.
     */
    fun scheduleKeepAlive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, UsageMonitorService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            KEEP_ALIVE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + KEEP_ALIVE_INTERVAL_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 30_000L, pendingIntent)
        }
    }

    fun cancelKeepAlive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, UsageMonitorService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            KEEP_ALIVE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }

    fun scheduleReminder(context: Context, packageName: String, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val requestCode = packageName.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + intervalMinutes * 60 * 1000L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            // Fallback: window alarm with a small tolerance so it fires close to the target time.
            // Previously used a 5-minute window which caused alarms to fire up to 10 min late.
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                30_000L,
                pendingIntent
            )
        }
    }

    fun cancelReminder(context: Context, packageName: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
        }
        val requestCode = packageName.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}
