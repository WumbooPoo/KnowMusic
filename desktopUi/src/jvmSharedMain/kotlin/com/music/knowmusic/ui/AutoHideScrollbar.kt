package com.music.knowmusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * A vertical scrollbar that stays hidden while idle and fades in while its
 * list scrolls, while the pointer hovers it, or while its thumb is dragged.
 * Once none of those hold, it waits 2 seconds and fades out again.
 *
 * Only a fade is animated — no size animation — so the layout never shifts
 * when the bar appears or disappears. While hidden the bar is not in the
 * composition at all, so it cannot be hovered or dragged: it is usable only
 * while scrolling, while hovered or dragged, and for 2 seconds after.
 *
 * No style is passed to [VerticalScrollbar], so it keeps the shared style
 * from the app root ([androidx.compose.foundation.LocalScrollbarStyle]).
 * The caller's modifier (placement + height in the surrounding box) goes on
 * the [AnimatedVisibility], untouched; the bar itself just fills that height.
 */
@Composable
fun AutoHideVerticalScrollbar(
    adapter: ScrollbarAdapter,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    AutoHideScrollbarBox(isScrolling, modifier) { interactionSource ->
        VerticalScrollbar(
            adapter = adapter,
            modifier = Modifier.fillMaxHeight(),
            interactionSource = interactionSource,
        )
    }
}

@Composable
fun AutoHideVerticalScrollbar(
    adapter: androidx.compose.foundation.v2.ScrollbarAdapter,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    AutoHideScrollbarBox(isScrolling, modifier) { interactionSource ->
        VerticalScrollbar(
            adapter = adapter,
            modifier = Modifier.fillMaxHeight(),
            interactionSource = interactionSource,
        )
    }
}

@Composable
private fun AutoHideScrollbarBox(
    isScrolling: Boolean,
    modifier: Modifier,
    content: @Composable (MutableInteractionSource) -> Unit,
) {
    // One source, remembered outside the visibility: it survives the bar
    // leaving the composition, so hover/drag state stays consistent.
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val active = isScrolling || isHovered || isDragged
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            visible = true
        } else {
            // Restarting the effect on every active change cancels a
            // pending hide, so a new scroll/hover/drag inside the 2 seconds
            // keeps the bar shown without flicker.
            delay(2000)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        content(interactionSource)
    }
}
