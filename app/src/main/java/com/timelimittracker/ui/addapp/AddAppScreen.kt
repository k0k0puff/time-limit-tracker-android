package com.timelimittracker.ui.addapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppScreen(
    onBack: () -> Unit,
    viewModel: AddAppViewModel = viewModel()
) {
    val apps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val debugInfo by viewModel.debugInfo.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var limitInput by remember { mutableStateOf("30") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add App") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.setSearch(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search apps...") },
                singleLine = true
            )

            // DEBUG BANNER — remove after diagnosis
            Text(
                text = "DBG: $debugInfo | list=${apps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            LazyColumn {
                items(apps, key = { it.packageName }) { app ->
                    AppListRow(
                        app = app,
                        onClick = {
                            selectedApp = app
                            limitInput = app.currentLimitMinutes?.toString() ?: "30"
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    selectedApp?.let { app ->
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text(app.appName) },
            text = {
                Column {
                    Text(if (app.isTracked) "Edit time limit:" else "Set time limit (minutes):")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it.filter { c -> c.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        suffix = { Text("min") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val limit = limitInput.toIntOrNull()?.coerceAtLeast(1) ?: 30
                    viewModel.saveApp(app.packageName, limit)
                    selectedApp = null
                }) { Text("Save") }
            },
            dismissButton = {
                if (app.isTracked) {
                    TextButton(onClick = {
                        viewModel.removeApp(app.packageName)
                        selectedApp = null
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { selectedApp = null }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun AppListRow(app: InstalledAppInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = remember(app.iconBytes) {
            app.iconBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = app.appName, modifier = Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(app.appName.first().toString())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, style = MaterialTheme.typography.bodyLarge)
            if (app.isTracked) {
                Text(
                    "Tracked — ${app.currentLimitMinutes} min limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (app.isTracked) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Tracked",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
