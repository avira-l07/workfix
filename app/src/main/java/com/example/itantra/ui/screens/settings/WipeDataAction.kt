package com.example.itantra.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import com.itantra.core.storage.DataRemovalChoice

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WipeDataAction(onWipe: (Set<DataRemovalChoice>) -> Unit) {
    var choose by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf(emptySet<DataRemovalChoice>()) }
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { choices = emptySet(); choose = true }) { Text("Choose data to delete") }
    if (choose) AlertDialog(
        onDismissRequest = { choose = false },
        title = { Text("Choose data to delete") },
        text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            Text("Selected data will be permanently deleted, including its recycle bin. The app restarts and disconnects peers. Language models stay.")
            DataRemovalChoice.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().toggleable(value = choice in choices, role = Role.Checkbox, onValueChange = { selected ->
                    choices = when {
                        !selected -> choices - choice
                        choice == DataRemovalChoice.ALL_PRIVATE -> setOf(choice)
                        else -> (choices - DataRemovalChoice.ALL_PRIVATE) + choice
                    }
                }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = choice in choices, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(choice.label)
                        Text(choice.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = choices.isNotEmpty(), onClick = { choose = false; confirm = true }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { choose = false }) { Text("Cancel") } }
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Permanently delete selected data?") },
        text = { Text(choices.sortedBy { it.ordinal }.joinToString("\n") { "• ${it.label}" } + "\n\nThis cannot be undone. Press and hold below to confirm.") },
        confirmButton = {
            Surface(color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClickLabel = "Delete selected data permanently", onLongClick = { confirm = false; onWipe(choices) })) {
                Text("Hold to delete", Modifier.padding(16.dp))
            }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}
