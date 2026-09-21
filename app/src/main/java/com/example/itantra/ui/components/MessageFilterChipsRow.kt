package com.example.itantra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itantra.data.messages.MessageFilter
import com.example.itantra.ui.theme.ITantraColors

/**
 * Matches the chip row in 00_transceiver_hub/code.html
 * ("All (14) · Sent · Received · Failed (1) · Emergency").
 */
@Composable
fun MessageFilterChipsRow(
    selected: MessageFilter,
    onSelect: (MessageFilter) -> Unit,
    counts: Map<MessageFilter, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(MessageFilter.entries) { filter ->
            val count = counts[filter]
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(if (count != null) "${filter.label} ($count)" else filter.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ITantraColors.Primary,
                    selectedLabelColor = ITantraColors.SurfaceWhite,
                ),
            )
        }
    }
}
