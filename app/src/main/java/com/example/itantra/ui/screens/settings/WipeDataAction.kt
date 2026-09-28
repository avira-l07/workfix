package com.example.itantra.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WipeDataAction(onWipe: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { confirm = true }) { Text("Wipe all data") }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Wipe all private data?") },
        text = { Text("Messages, emergency records, identity, settings and diagnostic data will be permanently deleted. Language packs and models will stay. Press and hold below to confirm.") },
        confirmButton = {
            Surface(color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClickLabel = "Wipe all private data", onLongClick = { confirm = false; onWipe() })) {
                Text("Hold to wipe", Modifier.padding(16.dp))
            }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}
