package com.timelimittracker.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelimittracker.TimeLimitApp
import com.timelimittracker.data.db.entities.SessionStatus
import com.timelimittracker.service.AlarmScheduler
import com.timelimittracker.service.DebugLog
import com.timelimittracker.service.UsageMonitorService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TrackedAppUiState(
    val packageName: String,
    val appName: String,
    val iconBytes: ByteArray?,
    val limitMinutes: Int,
    val sessionStatus: SessionStatus?,  // null = no active session
    val trackedSeconds: Long = 0L
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val appInstance = app as TimeLimitApp

    val trackingEnabled: StateFlow<Boolean> = appInstance.dataStore.trackingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val trackedApps: StateFlow<List<TrackedAppUiState>> =
        combine(
            appInstance.settingsRepo.trackedApps,
            appInstance.sessionRepo.activeSessions
        ) { apps, sessions ->
            val sessionMap = sessions.associateBy { it.packageName }
            apps.map { app ->
                val session = sessionMap[app.packageName]
                TrackedAppUiState(
                    packageName = app.packageName,
                    appName = app.appName,
                    iconBytes = app.iconBytes,
                    limitMinutes = app.limitMinutes,
                    sessionStatus = session?.status,
                    trackedSeconds = session?.accumulatedActiveSeconds ?: 0L
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wasInterrupted: StateFlow<Boolean> = MutableStateFlow(false)

    fun updateAppLimit(packageName: String, limitMinutes: Int) {
        viewModelScope.launch {
            val app = appInstance.settingsRepo.getByPackageName(packageName) ?: return@launch
            appInstance.settingsRepo.upsertTrackedApp(app.copy(limitMinutes = limitMinutes))
        }
    }

    fun resetSession(packageName: String) {
        viewModelScope.launch {
            val session = appInstance.sessionRepo.getActiveSession(packageName)
            if (session == null) {
                DebugLog.log("RESET", "$packageName: no active session to reset")
                return@launch
            }
            appInstance.sessionRepo.updateSession(session.copy(status = SessionStatus.ENDED))
            AlarmScheduler.cancelReminder(getApplication(), packageName)
            DebugLog.log("RESET", "$packageName: session ENDED (was ${session.status}, accumulated=${session.accumulatedActiveSeconds}s)")
            // Force the service to re-poll immediately so a new session is created
            // if the app is still in foreground
            forceRetriggerTracking()
        }
    }

    fun deleteTrackedApp(packageName: String) {
        viewModelScope.launch {
            val session = appInstance.sessionRepo.getActiveSession(packageName)
            if (session != null) {
                appInstance.sessionRepo.updateSession(
                    session.copy(status = SessionStatus.ENDED)
                )
            }
            appInstance.settingsRepo.deleteTrackedApp(packageName)
        }
    }

    fun forceRetriggerTracking() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, UsageMonitorService::class.java).apply {
            action = UsageMonitorService.ACTION_FORCE_POLL
        }
        androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
    }

    fun setTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                // End all active sessions before stopping the service so that
                // stale ACTIVE sessions don't accumulate phantom time when
                // tracking is re-enabled later.
                appInstance.sessionRepo.endAllSessions()
                DebugLog.log("TRACKING", "Disabled — all sessions ended")
            }
            appInstance.dataStore.setTrackingEnabled(enabled)
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, UsageMonitorService::class.java)
            if (enabled) {
                androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
                DebugLog.log("TRACKING", "Enabled — service started")
            } else {
                AlarmScheduler.cancelKeepAlive(ctx)
                ctx.stopService(intent)
            }
        }
    }
}
