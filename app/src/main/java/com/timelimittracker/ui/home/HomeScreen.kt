package com.timelimittracker.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
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
    onOpenPermissions: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val trackingEnabled by viewModel.trackingEnabled.collectAsStateWithLifecycle()
    val trackedApps by viewModel.trackedApps.collectAsStateWithLifecycle()

    var editingApp by remember { mutableStateOf<TrackedAppUiState?>(null) }
    var editLimitInput by remember { mutableStateOf("") }
    var deletingApp by remember { mutableStateOf<TrackedAppUiState?>(null) }
    var resettingApp by remember { mutableStateOf<TrackedAppUiState?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time Limit Tracker") },
                actions = {
                    TextButton(onClick = onOpenPermissions) { Text("Permissions") }
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
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        editingApp = app
                                        editLimitInput = app.limitMinutes.toString()
                                        false
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        deletingApp = app
                                        false
                                    }
                                    SwipeToDismissBoxValue.Settled -> false
                                }
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val target = dismissState.targetValue
                                val bgColor = when (target) {
                                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                                }
                                val alignment = if (target == SwipeToDismissBoxValue.StartToEnd)
                                    Alignment.CenterStart else Alignment.CenterEnd
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(bgColor)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = alignment
                                ) {
                                    when (target) {
                                        SwipeToDismissBoxValue.StartToEnd -> Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        SwipeToDismissBoxValue.EndToStart -> Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        SwipeToDismissBoxValue.Settled -> Unit
                                    }
                                }
                            }
                        ) {
                            TrackedAppRow(
                                app = app,
                                onReset = if (app.sessionStatus != null && app.sessionStatus != SessionStatus.ENDED) {
                                    { resettingApp = app }
                                } else null
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Edit time limit dialog
    editingApp?.let { app ->
        AlertDialog(
            onDismissRequest = { editingApp = null },
            title = { Text("Edit: ${app.appName}") },
            text = {
                Column {
                    Text("New time limit:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editLimitInput,
                        onValueChange = { editLimitInput = it.filter { c -> c.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        suffix = { Text("min") }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Active sessions keep their current limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val limit = editLimitInput.toIntOrNull()?.coerceAtLeast(1) ?: app.limitMinutes
                    viewModel.updateAppLimit(app.packageName, limit)
                    editingApp = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingApp = null }) { Text("Cancel") }
            }
        )
    }

    // Reset session confirmation dialog
    resettingApp?.let { app ->
        AlertDialog(
            onDismissRequest = { resettingApp = null },
            title = { Text("Reset ${app.appName}?") },
            text = { Text("This will clear the current session. The timer starts fresh next time you open the app.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSession(app.packageName)
                    resettingApp = null
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { resettingApp = null }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation dialog
    deletingApp?.let { app ->
        AlertDialog(
            onDismissRequest = { deletingApp = null },
            title = { Text("Remove ${app.appName}?") },
            text = { Text("This will stop tracking ${app.appName} and remove its time limit.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTrackedApp(app.packageName)
                    deletingApp = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingApp = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrackedAppRow(app: TrackedAppUiState, onReset: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
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
            Text(
                formatTrackedTime(app.trackedSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        val (badgeText, badgeColor) = when (app.sessionStatus) {
            SessionStatus.ACTIVE -> "In Session" to MaterialTheme.colorScheme.primary
            SessionStatus.LIMIT_REACHED -> "Limit Reached" to MaterialTheme.colorScheme.error
            SessionStatus.PAUSED -> "Paused" to MaterialTheme.colorScheme.secondary
            null, SessionStatus.ENDED -> "Idle" to MaterialTheme.colorScheme.outline
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(badgeText, color = badgeColor, style = MaterialTheme.typography.labelMedium)
            if (onReset != null) {
                IconButton(onClick = onReset, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset session",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun formatTrackedTime(seconds: Long): String {
    if (seconds <= 0L) return "No time tracked"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "Tracked: ${h}h ${m}m"
        m > 0 -> "Tracked: ${m}m ${s}s"
        else -> "Tracked: ${s}s"
    }
}
