package com.timelimittracker.ui.addapp

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
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
    val debugInfo = MutableStateFlow("Loading...")

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
        val TAG = "AddAppVM"
        try {
            Log.d(TAG, "loadInstalledApps: start")
            debugInfo.value = "Querying PackageManager..."

            val pm = getApplication<Application>().packageManager
            val ownPackage = getApplication<Application>().packageName

            // Step 1: raw result from PackageManager
            val raw = try {
                pm.getInstalledApplications(0)
            } catch (e: Exception) {
                Log.e(TAG, "getInstalledApplications threw: ${e::class.simpleName}: ${e.message}", e)
                debugInfo.value = "ERROR in getInstalledApplications: ${e::class.simpleName}: ${e.message}"
                return@withContext
            }
            Log.d(TAG, "raw list size: ${raw.size}")
            debugInfo.value = "raw=${raw.size}"

            // Step 2: after filtering own package
            val filtered = raw.filter { it.packageName != ownPackage }
            Log.d(TAG, "after own-package filter: ${filtered.size}")
            debugInfo.value = "raw=${raw.size} afterFilter=${filtered.size}"

            // Step 3: map to InstalledAppInfo, log any per-item failures
            var mapOk = 0; var mapFail = 0
            val apps = filtered.mapNotNull { info ->
                try {
                    val result = InstalledAppInfo(
                        packageName = info.packageName,
                        appName = pm.getApplicationLabel(info).toString(),
                        iconBytes = try { iconToBytes(pm, info.packageName) } catch (e: Exception) { null },
                        isTracked = false,
                        currentLimitMinutes = null
                    )
                    mapOk++
                    result
                } catch (e: Exception) {
                    mapFail++
                    Log.w(TAG, "failed to map ${info.packageName}: ${e.message}")
                    null
                }
            }.sortedBy { it.appName }

            Log.d(TAG, "mapped ok=$mapOk fail=$mapFail finalList=${apps.size}")
            debugInfo.value = "raw=${raw.size} filtered=${filtered.size} ok=$mapOk fail=$mapFail final=${apps.size}"

            _allInstalled.value = apps
            Log.d(TAG, "loadInstalledApps: done, list set")
        } catch (e: Exception) {
            Log.e(TAG, "loadInstalledApps outer catch: ${e::class.simpleName}: ${e.message}", e)
            debugInfo.value = "OUTER ERROR: ${e::class.simpleName}: ${e.message}"
            e.printStackTrace()
        }
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
