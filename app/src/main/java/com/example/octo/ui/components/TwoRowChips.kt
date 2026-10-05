package com.example.octo.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase 26 (FB26) — shared tag-row layout for body-part chips/tags.
 *
 * Row 1 holds the first half of the items, FIXED (no scroll — whatever fits is
 * shown; the sets here are ≤ 16 short labels so the first half always fits).
 * Row 2 holds the remaining half and scrolls horizontally if it overflows.
 * ALL items stay visible through the scrollable second row — there is no "+n"
 * overflow chip (FB25 feedback, generalized from the Phase 25 library card).
 *
 * Used by the Library video cards ([com.example.octo.ui.library.LibraryScreen])
 * and the video-detail body-part chips ([com.example.octo.ui.video.VideoDetailScreen]),
 * which pass their own chip composable as [itemContent].
 */
@Composable
fun <T> TwoRowChips(
    items: List<T>,
    modifier: Modifier = Modifier,
    rowSpacing: Dp = 4.dp,
    itemSpacing: Dp = 4.dp,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val split = (items.size + 1) / 2
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(itemSpacing)) {
            items.take(split).forEach { itemContent(it) }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            items.drop(split).forEach { itemContent(it) }
        }
    }
}
