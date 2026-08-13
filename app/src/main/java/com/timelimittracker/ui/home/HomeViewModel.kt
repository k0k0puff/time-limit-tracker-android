package com.timelimittracker.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelimittracker.TimeLimitApp
import com.timelimittracker.data.db.entities.SessionStatus
import com.timelimittracker.service.UsageMonitorService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TrackedAppUiState(
    val packageName: String,
    val appName: String,
    val iconBytes: ByteArray?,
    val limitMinutes: Int,
    val sessionStatus: SessionStatus?  // null = no active session
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
                TrackedAppUiState(
                    packageName = app.packageName,
                    appName = app.appName,
                    iconBytes = app.iconBytes,
                    limitMinutes = app.limitMinutes,
                    sessionStatus = sessionMap[app.packageName]?.status
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wasInterrupted: StateFlow<Boolean> = MutableStateFlow(false)

    fun setTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appInstance.dataStore.setTrackingEnabled(enabled)
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, UsageMonitorService::class.java)
            if (enabled) {
                androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
            } else {
                ctx.stopService(intent)
            }
        }
    }
}
