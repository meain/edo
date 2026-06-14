package com.edo.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.AppSettings
import com.edo.app.data.Provider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    var current by remember { mutableStateOf(container.settings.load()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Provider", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = current.provider == Provider.Anthropic,
                    onClick = {
                        current = current.copy(
                            provider = Provider.Anthropic,
                            baseUrl = if (current.provider != Provider.Anthropic) "https://api.anthropic.com" else current.baseUrl,
                        )
                    },
                    label = { Text("Anthropic") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = current.provider == Provider.OpenAI,
                    onClick = {
                        current = current.copy(
                            provider = Provider.OpenAI,
                            baseUrl = if (current.provider != Provider.OpenAI) "https://api.openai.com" else current.baseUrl,
                        )
                    },
                    label = { Text("OpenAI-compatible") },
                )
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = current.baseUrl,
                onValueChange = { current = current.copy(baseUrl = it) },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = current.apiKey,
                onValueChange = { current = current.copy(apiKey = it) },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = current.model,
                onValueChange = { current = current.copy(model = it) },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Yolo mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Auto-approve all tool calls",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = current.yoloMode,
                    onCheckedChange = { current = current.copy(yoloMode = it) },
                )
            }
            Spacer(Modifier.height(24.dp))
            Row {
                Button(onClick = {
                    container.settings.save(current)
                    onBack()
                }, modifier = Modifier.weight(1f)) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    current = AppSettings(activeProjectId = current.activeProjectId)
                }) { Text("Reset") }
            }
        }
    }
}
