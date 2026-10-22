package com.edo.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edo.app.data.ModelProfile
import com.edo.app.data.Provider
import com.edo.app.data.providerEnum

private fun providerLabel(p: Provider) = when (p) {
    Provider.Anthropic -> "Anthropic"
    Provider.OpenAI -> "OpenAI-compatible"
    Provider.OpenRouter -> "OpenRouter"
}

/** Saved model profiles: tap one to switch to it; reorder with the arrows, delete with the trash icon. */
@Composable
fun ProfilesSection(
    profiles: List<ModelProfile>,
    activeId: Long,
    onSelect: (ModelProfile) -> Unit,
    onMove: (ModelProfile, Int) -> Unit,
    onDelete: (ModelProfile) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ModelProfile?>(null) }
    Text("Profiles", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    if (profiles.isEmpty()) {
        Text(
            "No saved profiles. Fill in the connection below and tap “Save as profile”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    profiles.forEachIndexed { index, p ->
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onSelect(p) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = p.id == activeId, onClick = { onSelect(p) })
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${providerLabel(p.providerEnum())} · ${p.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onMove(p, -1) }, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${p.name} up")
            }
            IconButton(onClick = { onMove(p, 1) }, enabled = index < profiles.lastIndex) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${p.name} down")
            }
            IconButton(onClick = { pendingDelete = p }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${p.name}")
            }
        }
    }
    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete profile?") },
            text = { Text("“${p.name}” will be removed.") },
            confirmButton = {
                TextButton(onClick = { onDelete(p); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Asks for a profile name. */
@Composable
fun SaveProfileDialog(initialName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
