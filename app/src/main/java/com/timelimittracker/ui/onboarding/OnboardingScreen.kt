package com.timelimittracker.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun OnboardingScreen(
    onAllRequiredGranted: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    LaunchedEffect(permissions.allRequiredGranted) {
        if (permissions.allRequiredGranted) onAllRequiredGranted()
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Setup Required", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Time Limit Tracker needs a few permissions to work.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))

        PermissionRow(
            label = "Usage Access",
            description = "Required to detect which app is in the foreground.",
            granted = permissions.usageAccess,
            required = true,
            onGrant = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )
        Spacer(Modifier.height(16.dp))

        PermissionRow(
            label = "Display Over Other Apps",
            description = "Required to show blocking overlays.",
            granted = permissions.overlayPermission,
            required = true,
            onGrant = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )
        Spacer(Modifier.height(16.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                label = "Notifications",
                description = "Recommended for the background service notification.",
                granted = permissions.notifications,
                required = false,
                onGrant = {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            )
            Spacer(Modifier.height(16.dp))
        }

        PermissionRow(
            label = "Battery Optimization Exemption",
            description = "Recommended to prevent the OS from stopping tracking.",
            granted = permissions.batteryOptExempt,
            required = false,
            onGrant = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )

        Spacer(Modifier.height(32.dp))
        Button(onClick = { viewModel.refresh() }) {
            Text("I've granted permissions — Continue")
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    onGrant: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label + if (required) " *" else " (recommended)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                Text("✓", color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}
