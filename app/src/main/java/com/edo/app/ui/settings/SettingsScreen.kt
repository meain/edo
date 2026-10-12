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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.edo.app.AppContainer
import com.edo.app.data.AppSettings
import com.edo.app.data.Provider
import com.edo.app.llm.AnthropicClient
import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.LlmClient
import com.edo.app.llm.LlmEvent
import com.edo.app.llm.OpenAIClient
import com.edo.app.llm.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** Sends a one-word prompt with the unsaved settings; returns (ok, message). */
private suspend fun testConnection(container: AppContainer, s: AppSettings): Pair<Boolean, String> {
    val llm: LlmClient = when (s.provider) {
        Provider.Anthropic -> AnthropicClient(s.baseUrl, s.apiKey, s.model, container.http, maxTokens = 16)
        Provider.OpenAI -> OpenAIClient(s.baseUrl, s.apiKey, s.model, container.http)
    }
    return try {
        var result: Pair<Boolean, String>? = null
        llm.stream(null, listOf(ConvMessage(Role.User, listOf(Block.Text("Reply with: ok")))), emptyList())
            .takeWhile { result == null }
            .collect { ev ->
                when (ev) {
                    is LlmEvent.Failure -> result = false to ev.message
                    is LlmEvent.TurnEnd -> result = true to "OK — ${s.model} responded"
                    else -> Unit
                }
            }
        result ?: (false to "No response from server")
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        false to (t.message ?: t.javaClass.simpleName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    var current by remember { mutableStateOf(container.settings.load()) }
    var maxTurnsText by remember { mutableStateOf(current.maxTurns.toString()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

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
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = maxTurnsText,
                onValueChange = { text ->
                    val digits = text.filter { it.isDigit() }.take(4)
                    maxTurnsText = digits
                    digits.toIntOrNull()?.takeIf { it > 0 }?.let { current = current.copy(maxTurns = it) }
                },
                label = { Text("Max turns per run") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = testConnection(container, current)
                        testing = false
                    }
                },
                enabled = !testing,
            ) { Text(if (testing) "Testing…" else "Test connection") }
            testResult?.let { (ok, msg) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
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
                    maxTurnsText = current.maxTurns.toString()
                }) { Text("Reset") }
            }
        }
    }
}
