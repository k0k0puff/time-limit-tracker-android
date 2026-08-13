package com.timelimittracker.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timelimittracker.data.db.entities.SessionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddApp: () -> Unit,
    onEditApp: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val trackingEnabled by viewModel.trackingEnabled.collectAsStateWithLifecycle()
    val trackedApps by viewModel.trackedApps.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time Limit Tracker") },
                actions = {
                    TextButton(onClick = onOpenTemplates) { Text("Templates") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddApp) {
                Icon(Icons.Default.Add, contentDescription = "Add App")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Tracking", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (trackingEnabled) "Active" else "Disabled",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = trackingEnabled,
                    onCheckedChange = { viewModel.setTrackingEnabled(it) }
                )
            }
            HorizontalDivider()

            if (trackedApps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps tracked yet. Tap + to add one.")
                }
            } else {
                LazyColumn {
                    items(trackedApps, key = { it.packageName }) { app ->
                        TrackedAppRow(app = app, onClick = { onEditApp(app.packageName) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackedAppRow(app: TrackedAppUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = remember(app.iconBytes) {
            app.iconBytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = app.appName,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(
                Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) { Text(app.appName.first().toString()) }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, style = MaterialTheme.typography.bodyLarge)
            Text("Limit: ${app.limitMinutes} min", style = MaterialTheme.typography.bodySmall)
        }

        val (badgeText, badgeColor) = when (app.sessionStatus) {
            SessionStatus.ACTIVE -> "In Session" to MaterialTheme.colorScheme.primary
            SessionStatus.LIMIT_REACHED -> "Limit Reached" to MaterialTheme.colorScheme.error
            SessionStatus.PAUSED -> "Paused" to MaterialTheme.colorScheme.secondary
            null, SessionStatus.ENDED -> "Idle" to MaterialTheme.colorScheme.outline
        }
        Text(badgeText, color = badgeColor, style = MaterialTheme.typography.labelMedium)
    }
}
