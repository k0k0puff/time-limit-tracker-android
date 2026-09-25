package com.timelimittracker.ui.onboarding

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionState(
    val usageAccess: Boolean,
    val overlayPermission: Boolean,
    val notifications: Boolean,
    val batteryOptExempt: Boolean,
    // OEM "App launch" setting — no API to read it, always prompts user to set manually
    val launchAppManaged: Boolean = false
) {
    val allRequiredGranted: Boolean get() = usageAccess && overlayPermission
}

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val _permissions = MutableStateFlow(checkPermissions())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    fun refresh() {
        _permissions.value = checkPermissions()
    }

    private fun checkPermissions(): PermissionState {
        val ctx = getApplication<Application>()
        return PermissionState(
            usageAccess = hasUsageAccess(ctx),
            overlayPermission = Settings.canDrawOverlays(ctx),
            notifications = hasNotificationPermission(),
            batteryOptExempt = isBatteryOptExempt(ctx)
        )
    }

    private fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val ctx = getApplication<Application>()
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
