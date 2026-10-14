package com.edo.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edo.app.data.OpenRouterOptions
import com.edo.app.llm.isValidExtraJson

/** OpenRouter-only routing and request options (see [OpenRouterOptions]). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OpenRouterSection(options: OpenRouterOptions, onChange: (OpenRouterOptions) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("OpenRouter routing", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = options.onlyProviders,
            onValueChange = { onChange(options.copy(onlyProviders = it)) },
            label = { Text("Only these providers") },
            supportingText = { Text("Comma-separated slugs, e.g. deepinfra, together. Blank allows any.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = options.ignoreProviders,
            onValueChange = { onChange(options.copy(ignoreProviders = it)) },
            label = { Text("Never use these providers") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        Text("Prefer", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "Default", "price" to "Cheapest", "throughput" to "Fastest output", "latency" to "Lowest latency")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = options.sort == value,
                        onClick = { onChange(options.copy(sort = value)) },
                        label = { Text(label) },
                    )
                }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = options.fallbackModels,
            onValueChange = { onChange(options.copy(fallbackModels = it)) },
            label = { Text("Fallback models") },
            supportingText = { Text("Comma-separated, tried in order if the main model fails.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        Text("Reasoning effort", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "Default", "low" to "Low", "medium" to "Medium", "high" to "High").forEach { (value, label) ->
                FilterChip(
                    selected = options.reasoningEffort == value,
                    onClick = { onChange(options.copy(reasoningEffort = value)) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ToggleRow("Allow fallback providers", "Off: fail rather than use a provider outside your list.", options.allowFallbacks) {
            onChange(options.copy(allowFallbacks = it))
        }
        ToggleRow("Deny data collection", "Skip providers that may store or train on prompts.", options.denyDataCollection) {
            onChange(options.copy(denyDataCollection = it))
        }
        ToggleRow("Zero data retention only", "Only endpoints that don't retain prompts.", options.zeroDataRetention) {
            onChange(options.copy(zeroDataRetention = it))
        }
        ToggleRow("Require parameter support", "Skip providers that ignore tools or other request parameters.", options.requireParameters) {
            onChange(options.copy(requireParameters = it))
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = options.extraJson,
            onValueChange = { onChange(options.copy(extraJson = it)) },
            label = { Text("Extra request JSON") },
            supportingText = {
                Text(
                    if (isValidExtraJson(options.extraJson)) "Merged into every request, e.g. {\"temperature\": 0.2}."
                    else "Not a valid JSON object; it will be ignored.",
                )
            },
            isError = !isValidExtraJson(options.extraJson),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
