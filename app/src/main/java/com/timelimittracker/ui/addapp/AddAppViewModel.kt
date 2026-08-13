package com.timelimittracker.ui.addapp

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelimittracker.TimeLimitApp
import com.timelimittracker.data.db.entities.TrackedAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val iconBytes: ByteArray?,
    val isTracked: Boolean,
    val currentLimitMinutes: Int?
)

class AddAppViewModel(app: Application) : AndroidViewModel(app) {
    private val appInstance = app as TimeLimitApp
    private val _search = MutableStateFlow("")
    private val _allInstalled = MutableStateFlow<List<InstalledAppInfo>>(emptyList())

    val filteredApps: StateFlow<List<InstalledAppInfo>> =
        combine(_search, _allInstalled, appInstance.settingsRepo.trackedApps) { query, installed, tracked ->
            val trackedMap = tracked.associateBy { it.packageName }
            val withTrackedStatus = installed.map { info ->
                val trackedEntry = trackedMap[info.packageName]
                info.copy(
                    isTracked = trackedEntry != null,
                    currentLimitMinutes = trackedEntry?.limitMinutes
                )
            }
            if (query.isBlank()) withTrackedStatus
            else withTrackedStatus.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { loadInstalledApps() }
    }

    fun setSearch(query: String) { _search.value = query }

    fun saveApp(packageName: String, limitMinutes: Int) {
        viewModelScope.launch {
            val info = _allInstalled.value.find { it.packageName == packageName } ?: return@launch
            val entity = TrackedAppEntity(
                packageName = packageName,
                appName = info.appName,
                iconBytes = info.iconBytes,
                limitMinutes = limitMinutes,
                isTracked = true
            )
            appInstance.settingsRepo.upsertTrackedApp(entity)
        }
    }

    fun removeApp(packageName: String) {
        viewModelScope.launch {
            appInstance.settingsRepo.deleteTrackedApp(packageName)
        }
    }

    private suspend fun loadInstalledApps() = withContext(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                InstalledAppInfo(
                    packageName = info.packageName,
                    appName = pm.getApplicationLabel(info).toString(),
                    iconBytes = try { iconToBytes(pm, info.packageName) } catch (e: Exception) { null },
                    isTracked = false,
                    currentLimitMinutes = null
                )
            }
            .sortedBy { it.appName }
        _allInstalled.value = apps
    }

    private fun iconToBytes(pm: PackageManager, packageName: String): ByteArray {
        val drawable = pm.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 80, it) }.toByteArray()
    }
}
