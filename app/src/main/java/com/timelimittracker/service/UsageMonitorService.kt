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
        const val ACTION_FORCE_POLL = "com.timelimittracker.ACTION_FORCE_POLL"
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
    /** Max age (ms) for a session's lastForegroundTimestamp before it's considered stale.
     *  Must be well above real-world poll gaps (Android Doze can cause 5+ min gaps). */
    private val STALE_SESSION_THRESHOLD_MS = 10 * 60 * 1000L  // 10 minutes

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
        ServiceWatchdogWorker.schedule(this)
        DebugLog.log("SERVICE", "Service created, polling will start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOT -> scope.launch { handleBoot() }
            ACTION_REMINDER -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_STICKY
                scope.launch { handleReminder(pkg) }
            }
            ACTION_FORCE_POLL -> {
                // Cancel current poll loop and restart immediately so the first poll fires now
                pollJob?.cancel()
                pollJob = null
            }
            // ACTION_KEEP_ALIVE: no special handling needed — just ensure polling is alive
        }
        startPollingIfNeeded()
        // Reschedule the keepalive alarm so the service is revived within ~2 min if killed
        AlarmScheduler.scheduleKeepAlive(this)
        return START_STICKY
    }

    private fun startPollingIfNeeded() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    poll()
                } catch (e: CancellationException) {
                    throw e  // Don't swallow cancellation
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun poll() {
        val trackingEnabled = dataStore.trackingEnabled.first()
        if (!trackingEnabled) {
            DebugLog.log("POLL", "Tracking disabled, skipping")
            return
        }

        val foregroundPkg = getForegroundPackage()
        val trackedApps = settingsRepo.trackedApps.first()
        val nowMs = System.currentTimeMillis()
        DebugLog.log("POLL", "fg=$foregroundPkg | tracked=${trackedApps.map { it.packageName }}")
        Log.d(TAG, "poll: foreground=$foregroundPkg, tracked=${trackedApps.map { it.packageName }}")

        // Check for uninstalled apps
        val deletedPackages = mutableSetOf<String>()

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
                deletedPackages.add(app.packageName)
            }
        }

        trackedApps.filter { it.packageName !in deletedPackages }.forEach { app ->
            var session = sessionRepo.getActiveSession(app.packageName)

            if (foregroundPkg == app.packageName) {
                // App is in foreground
                if (session == null) {
                    // Start new session
                    DebugLog.log("STATE", "${app.packageName}: NEW SESSION")
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
                    DebugLog.log("STATE", "${app.packageName}: FG ${session.status}→${updated.status} accumulated=${updated.accumulatedActiveSeconds}s")
                    if (event == SessionEvent.LimitReached) {
                        val message = SessionStateMachine.getMessageForCycle(updated)
                        val substituted = TokenSubstitutor.substitute(
                            message, app.appName, updated.snapshotLimitMinutes,
                            updated.accumulatedActiveSeconds
                        )
                        withContext(Dispatchers.Main) { overlayManager.show(substituted, app.packageName) {} }
                        AlarmScheduler.scheduleReminder(this, app.packageName, updated.snapshotLimitMinutes)
                    }
                }
            } else {
                // App not in foreground
                if (session != null) {
                    // Guard: if session is ACTIVE but lastForegroundTimestamp is stale,
                    // the service was likely killed and restarted. Reset the timestamp
                    // to prevent phantom time accumulation on the background transition.
                    // Only applied here (not in the foreground path) because if the app
                    // IS currently in foreground, the gap represents real usage time.
                    if (session.status == SessionStatus.ACTIVE &&
                        (nowMs - session.lastForegroundTimestamp) > STALE_SESSION_THRESHOLD_MS) {
                        val gapSec = (nowMs - session.lastForegroundTimestamp) / 1000
                        DebugLog.log("STATE", "${app.packageName}: STALE BG reset (gap=${gapSec}s)")
                        val fixed = session.copy(lastForegroundTimestamp = nowMs)
                        sessionRepo.updateSession(fixed)
                        session = fixed
                    }
                    when (session.status) {
                        SessionStatus.ACTIVE, SessionStatus.LIMIT_REACHED -> {
                            val paused = SessionStateMachine.onBackground(session, nowMs)
                            sessionRepo.updateSession(paused)
                            DebugLog.log("STATE", "${app.packageName}: BG ${session.status}→PAUSED accumulated=${paused.accumulatedActiveSeconds}s")
                        }
                        SessionStatus.PAUSED -> {
                            val maybeEnded = SessionStateMachine.checkExpiry(session, nowMs)
                            if (maybeEnded.status == SessionStatus.ENDED) {
                                sessionRepo.updateSession(maybeEnded)
                                AlarmScheduler.cancelReminder(this, app.packageName)
                                withContext(Dispatchers.Main) { overlayManager.hide() }
                                DebugLog.log("STATE", "${app.packageName}: PAUSED→ENDED (expired)")
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
        withContext(Dispatchers.Main) { overlayManager.show(substituted, packageName) {} }
        AlarmScheduler.scheduleReminder(this, packageName, advanced.snapshotLimitMinutes)
    }

    private suspend fun handleBoot() {
        sessionRepo.endAllSessions()
    }

    private suspend fun getForegroundPackage(): String? {
        val nowMs = System.currentTimeMillis()
        // Use a 2-hour window so we catch apps that have been open a long time
        val events = usageStatsManager.queryEvents(nowMs - 2 * 60 * 60 * 1000L, nowMs)
        val event = UsageEvents.Event()
        // Track per-app last foreground/background times independently.
        // The old approach tracked only the single globally-latest foreground event, which
        // caused it to return null whenever any system UI (notification, dialog) briefly
        // foregrounded and then dismissed — even if the user's app was still on screen.
        val lastFgTime = mutableMapOf<String, Long>()
        val lastBgTime = mutableMapOf<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val prev = lastFgTime[event.packageName] ?: 0L
                    if (event.timeStamp > prev) lastFgTime[event.packageName] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val prev = lastBgTime[event.packageName] ?: 0L
                    if (event.timeStamp > prev) lastBgTime[event.packageName] = event.timeStamp
                }
            }
        }
        // An app is currently in foreground if its last fg event is more recent than its last bg event.
        // Return whichever such app was foregrounded most recently.
        return lastFgTime.entries
            .filter { (pkg, fgTime) -> (lastBgTime[pkg] ?: 0L) < fgTime }
            .maxByOrNull { it.value }
            ?.key
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
        DebugLog.log("SERVICE", "Service destroyed")
        scope.cancel()
        overlayManager.hide()
        // START_STICKY + ServiceWatchdogWorker handle restarts;
        // self-restarting here interfered with intentional stops
        // when the user toggled tracking off.
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
