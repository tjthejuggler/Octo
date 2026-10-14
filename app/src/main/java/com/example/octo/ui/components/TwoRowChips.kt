package com.example.octo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase 26 (FB26) — shared tag-row layout for body-part / muscle chips.
 *
 * FlowRow wrapping: every chip keeps its intrinsic width and simply flows to
 * the next row when the current one is full — all items stay visible with no
 * scroll and no "+n" overflow chip. (The original two-fixed-rows version
 * squeezed overflowing chips until their text wrapped letter-by-letter — the
 * "vertical tag" bug.)
 *
 * Used by the Library video cards ([com.example.octo.ui.library.LibraryScreen])
 * and the video-detail chips ([com.example.octo.ui.video.VideoDetailScreen]),
 * which pass their own chip composable as [itemContent].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> TwoRowChips(
    items: List<T>,
    modifier: Modifier = Modifier,
    rowSpacing: Dp = 4.dp,
    itemSpacing: Dp = 4.dp,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        items.forEach { itemContent(it) }
    }
}
