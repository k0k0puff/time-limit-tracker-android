package com.timelimittracker.ui.templates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    viewModel: TemplatesViewModel = viewModel()
) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Templates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = isDirty
                    ) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Available tokens: {appName}  {limit}  {elapsed}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )

            TemplateField(
                label = "Main Message",
                value = drafts.mainMessage,
                placeholder = "You've been on {appName} for {limit} min.",
                onValueChange = { viewModel.updateMain(it) }
            )

            TemplateField(
                label = "Reminder 1",
                value = drafts.reminder1 ?: "",
                placeholder = "Still on {appName}. Take a break.",
                onValueChange = { viewModel.updateReminder1(it) }
            )

            TemplateField(
                label = "Reminder 2",
                value = drafts.reminder2 ?: "",
                placeholder = "Another 5 minutes on {appName}...",
                onValueChange = { viewModel.updateReminder2(it) }
            )

            TemplateField(
                label = "Reminder 3",
                value = drafts.reminder3 ?: "",
                placeholder = "You've now been on {appName} for {elapsed} min.",
                onValueChange = { viewModel.updateReminder3(it) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Changes only apply to sessions that start after saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TemplateField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.outline) },
        minLines = 2
    )
}
