package com.edo.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Free-text model field that suggests [options] matching what's typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    placeholder: String? = null,
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val matches = options.filter { it.contains(value, ignoreCase = true) && it != value }.take(30)
    ExposedDropdownMenuBox(
        expanded = expanded && matches.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            singleLine = true,
            trailingIcon = if (options.isNotEmpty()) {
                { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && matches.isNotEmpty()) }
            } else null,
        )
        ExposedDropdownMenu(
            expanded = expanded && matches.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            matches.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = {
                        onValueChange(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
