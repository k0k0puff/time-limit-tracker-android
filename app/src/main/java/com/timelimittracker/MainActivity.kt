package com.timelimittracker

import android.app.AppOpsManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.timelimittracker.service.UsageMonitorService
import com.timelimittracker.ui.navigation.AppNavGraph
import com.timelimittracker.ui.navigation.Routes
import com.timelimittracker.ui.theme.TimeLimitTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startDest = if (permissionsGranted()) {
            startMonitorService()
            Routes.HOME
        } else {
            Routes.ONBOARDING
        }

        setContent {
            TimeLimitTrackerTheme {
                AppNavGraph(startDestination = startDest)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionsGranted()) startMonitorService()
    }

    private fun permissionsGranted(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val usageGranted = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
        return usageGranted && Settings.canDrawOverlays(this)
    }

    private fun startMonitorService() {
        val intent = Intent(this, UsageMonitorService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }
}
