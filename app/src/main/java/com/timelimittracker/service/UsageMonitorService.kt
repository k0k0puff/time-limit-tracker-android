package com.timelimittracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.timelimittracker.data.db.AppDatabase
import com.timelimittracker.data.db.entities.SessionStatus
import com.timelimittracker.data.datastore.AppSettingsDataStore
import com.timelimittracker.data.repository.SessionRepository
import com.timelimittracker.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class UsageMonitorService : Service() {

    companion object {
        const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
        const val ACTION_BOOT = "com.timelimittracker.ACTION_BOOT"
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val NOTIF_CHANNEL_ID = "usage_monitor"
        private const val NOTIF_ID = 1
        private const val POLL_INTERVAL_MS = 12_000L
        private const val TAG = "UsageMonitorService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var dataStore: AppSettingsDataStore
    private lateinit var overlayManager: OverlayManager
    private lateinit var usageStatsManager: UsageStatsManager
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        settingsRepo = SettingsRepository(db.trackedAppDao(), db.globalTemplatesDao())
        sessionRepo = SessionRepository(db.sessionDao())
        dataStore = AppSettingsDataStore(this)
        overlayManager = OverlayManager(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOT -> scope.launch { handleBoot() }
            ACTION_REMINDER -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_STICKY
                scope.launch { handleReminder(pkg) }
            }
        }
        startPollingIfNeeded()
        return START_STICKY
    }

    private fun startPollingIfNeeded() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    poll()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun poll() {
        val trackingEnabled = dataStore.trackingEnabled.first()
        if (!trackingEnabled) return

        val foregroundPkg = getForegroundPackage()
        val trackedApps = settingsRepo.trackedApps.first()
        val nowMs = System.currentTimeMillis()

        // Check for uninstalled apps
        trackedApps.forEach { app ->
            try {
                packageManager.getPackageInfo(app.packageName, 0)
            } catch (e: Exception) {
                Log.i(TAG, "App uninstalled: ${app.packageName} — ending session")
                val session = sessionRepo.getActiveSession(app.packageName)
                if (session != null) {
                    sessionRepo.updateSession(session.copy(status = SessionStatus.ENDED))
                    AlarmScheduler.cancelReminder(this, app.packageName)
                }
                settingsRepo.deleteTrackedApp(app.packageName)
                return@forEach
            }
        }

        trackedApps.forEach { app ->
            val session = sessionRepo.getActiveSession(app.packageName)

            if (foregroundPkg == app.packageName) {
                // App is in foreground
                if (session == null) {
                    // Start new session
                    val templates = settingsRepo.getTemplates()
                    val snapshot = SessionSnapshot(
                        limitMinutes = app.limitMinutes,
                        mainMessage = templates.mainMessage,
                        reminder1 = templates.reminder1,
                        reminder2 = templates.reminder2,
                        reminder3 = templates.reminder3
                    )
                    val newSession = SessionStateMachine.startSession(app.packageName, snapshot, nowMs)
                    sessionRepo.upsertSession(newSession)
                } else {
                    val (updated, event) = SessionStateMachine.onForeground(session, nowMs)
                    sessionRepo.updateSession(updated)
                    if (event == SessionEvent.LimitReached) {
                        val message = SessionStateMachine.getMessageForCycle(updated)
                        val substituted = TokenSubstitutor.substitute(
                            message, app.appName, updated.snapshotLimitMinutes,
                            updated.accumulatedActiveSeconds
                        )
                        withContext(Dispatchers.Main) { overlayManager.show(substituted) {} }
                        AlarmScheduler.scheduleReminder(this, app.packageName)
                    }
                }
            } else {
                // App not in foreground
                if (session != null) {
                    when (session.status) {
                        SessionStatus.ACTIVE, SessionStatus.LIMIT_REACHED -> {
                            val paused = SessionStateMachine.onBackground(session, nowMs)
                            sessionRepo.updateSession(paused)
                        }
                        SessionStatus.PAUSED -> {
                            val maybeEnded = SessionStateMachine.checkExpiry(session, nowMs)
                            if (maybeEnded.status == SessionStatus.ENDED) {
                                sessionRepo.updateSession(maybeEnded)
                                AlarmScheduler.cancelReminder(this, app.packageName)
                                withContext(Dispatchers.Main) { overlayManager.hide() }
                            }
                        }
                        SessionStatus.ENDED -> { /* nothing */ }
                    }
                }
            }
        }
    }

    private suspend fun handleReminder(packageName: String) {
        val session = sessionRepo.getActiveSession(packageName) ?: return
        if (session.status != SessionStatus.LIMIT_REACHED) return

        val foregroundPkg = getForegroundPackage()
        if (foregroundPkg != packageName) return  // App no longer in foreground — skip

        val advanced = SessionStateMachine.advanceReminderCycle(session)
        sessionRepo.updateSession(advanced)

        val trackedApp = settingsRepo.getByPackageName(packageName) ?: return
        val message = SessionStateMachine.getMessageForCycle(advanced)
        val substituted = TokenSubstitutor.substitute(
            message, trackedApp.appName, advanced.snapshotLimitMinutes,
            advanced.accumulatedActiveSeconds
        )
        withContext(Dispatchers.Main) { overlayManager.show(substituted) {} }
        AlarmScheduler.scheduleReminder(this, packageName)
    }

    private suspend fun handleBoot() {
        sessionRepo.endAllSessions()
    }

    private fun getForegroundPackage(): String? {
        val nowMs = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(nowMs - 10_000L, nowMs)
        val event = UsageEvents.Event()
        var lastForegroundPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundPkg = event.packageName
            }
        }
        return lastForegroundPkg
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Usage Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Tracks app usage in the background" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Time Limit Tracker")
            .setContentText("Monitoring app usage...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlayManager.hide()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
