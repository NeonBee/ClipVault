package dev.clipvault.app.quickpaste

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.clipvault.app.R
import dev.clipvault.app.data.ClipItem

object QuickPasteTags {
    const val ROOT = "quick_paste_root"
    const val SEARCH = "quick_paste_search"
    const val LIST = "quick_paste_list"
    const val KEEP_OPEN = "quick_paste_keep_open"
    const val KEEP_OPEN_FOOTER = "quick_paste_keep_open_footer"
    fun row(index: Int) = "quick_paste_row_$index"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickPasteScreen(
    state: QuickPasteState,
    onQueryChange: (String) -> Unit,
    onKey: (QuickPasteKeyAction) -> Unit,
    onSelectIndex: (Int) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    onUnlock: () -> Unit,
    onOpenApp: () -> Unit,
    keepOpenAfterCopy: Boolean,
    onKeepOpenChange: (Boolean) -> Unit,
) {
    // The root is focusable so Esc (and Enter on the lock pane) reach the key handler even when no
    // field or button holds focus; clickable buttons are not focusable in touch mode.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(state.gate) {
        if (state.gate != QuickPasteGate.READY) runCatching { rootFocus.requestFocus() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            // DeX's externally reported minimum window dimensions can differ from the
            // Activity content area in dp. Use both axes: even a tall, narrow window needs
            // the toggle inside the search field rather than below the scrollable clip list.
            val compactLayout = maxWidth <= 480.dp || maxHeight <= 420.dp
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxSize().padding(16.dp)
                    // Preview runs parent-first, before the focused field or button: Esc always closes,
                    // and on the search pane arrows/Enter drive the list instead of the text field.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val action = QuickPasteKeys.action(event.key.nativeKeyCode, event.isCtrlPressed)
                            ?: return@onPreviewKeyEvent false
                        when {
                            action == QuickPasteKeyAction.CLOSE -> onClose()
                            state.gate != QuickPasteGate.READY -> return@onPreviewKeyEvent false
                            action == QuickPasteKeyAction.CONFIRM -> onConfirm()
                            else -> onKey(action)
                        }
                        true
                    }
                    // Bubble phase: on the lock pane a focused button handles its own Enter; only an
                    // Enter nobody consumed (root focused) starts the unlock.
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || state.gate != QuickPasteGate.LOCKED) return@onKeyEvent false
                        if (QuickPasteKeys.action(event.key.nativeKeyCode) != QuickPasteKeyAction.CONFIRM) return@onKeyEvent false
                        onUnlock()
                        true
                    }
                    .focusRequester(rootFocus)
                    .focusable()
                    .testTag(QuickPasteTags.ROOT),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.gate) {
                    QuickPasteGate.READY -> SearchPane(
                        state, onQueryChange, onSelectIndex, onConfirm,
                        keepOpenAfterCopy, onKeepOpenChange, compactLayout,
                    )
                    QuickPasteGate.UNLOCKING -> StatusPane(stringResource(R.string.quick_paste_unlocking), null) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                    QuickPasteGate.LOCKED -> StatusPane(stringResource(R.string.quick_paste_locked), state.message) {
                        Icon(Icons.Default.Lock, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        // FlowRow, not Row: at the 240dp freeform minimum the two buttons no longer fit
                        // on one line, and a clipped Unlock button is worse than a second line.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = onClose) { Text(stringResource(R.string.quick_paste_close)) }
                            Button(onClick = onUnlock) { Text(stringResource(R.string.unlock_vault)) }
                        }
                    }
                    QuickPasteGate.SETUP_REQUIRED -> StatusPane(stringResource(R.string.quick_paste_title), state.message) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = onClose) { Text(stringResource(R.string.quick_paste_close)) }
                            Button(onClick = onOpenApp) { Text(stringResource(R.string.quick_paste_open_app)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.SearchPane(
    state: QuickPasteState,
    onQueryChange: (String) -> Unit,
    onSelectIndex: (Int) -> Unit,
    onConfirm: () -> Unit,
    keepOpenAfterCopy: Boolean,
    onKeepOpenChange: (Boolean) -> Unit,
    compactLayout: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        singleLine = true,
        // Replace, rather than add to, the search icon slot in the compact layout.
        // Keeping only one 48dp icon leaves as much room for the text field as before
        // and avoids making the field taller or the list too short at 240x260dp.
        leadingIcon = if (compactLayout) null else {
            { Icon(Icons.Default.Search, null) }
        },
        trailingIcon = if (compactLayout) {
            {
                IconToggleButton(
                    checked = keepOpenAfterCopy,
                    onCheckedChange = onKeepOpenChange,
                    modifier = Modifier.testTag(QuickPasteTags.KEEP_OPEN),
                ) {
                    Icon(
                        // The lock refers to retaining this panel, NOT locking/unlocking the vault.
                        // Keep the accessible label tied to the actual copy-then-close policy.
                        if (keepOpenAfterCopy) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = stringResource(R.string.quick_paste_keep_open),
                        tint = if (keepOpenAfterCopy) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else null,
        placeholder = { Text(stringResource(R.string.quick_paste_search_hint)) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onConfirm() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(QuickPasteTags.SEARCH),
    )
    val listState = rememberLazyListState()
    LaunchedEffect(state.selected, state.results) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == state.selected && it.offset >= 0 &&
            it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (state.results.isNotEmpty() && !fullyVisible) listState.scrollToItem(state.selected)
    }
    if (state.searched && state.results.isEmpty()) {
        Text(stringResource(if (state.query.isBlank()) R.string.quick_paste_empty else R.string.quick_paste_no_match),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag(QuickPasteTags.LIST), state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(state.results, key = { _, item -> item.id }) { index, item ->
            ResultRow(item, index == state.selected, Modifier.testTag(QuickPasteTags.row(index))) {
                // Pointer: one click copies that row, like Enter on a keyboard selection.
                onSelectIndex(index)
                onConfirm()
            }
        }
    }
    if (compactLayout) {
        Text(stringResource(R.string.quick_paste_keys_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        // Retain the labeled footer chip on normal-sized windows, where it was device-tested.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(stringResource(R.string.quick_paste_keys_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(
                selected = keepOpenAfterCopy,
                onClick = { onKeepOpenChange(!keepOpenAfterCopy) },
                label = { Text(stringResource(R.string.quick_paste_keep_open)) },
                modifier = Modifier.testTag(QuickPasteTags.KEEP_OPEN_FOOTER),
            )
        }
    }
}

@Composable
private fun ResultRow(item: ClipItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.primaryContainer else Color.Transparent
    val content = if (selected) colors.onPrimaryContainer else colors.onSurface
    Column(
        modifier.fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val headline = item.title.ifBlank { item.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
        Text(headline.take(200), maxLines = 1, overflow = TextOverflow.Ellipsis, color = content,
            fontWeight = FontWeight.SemiBold)
        if (item.title.isNotBlank() || item.content.lines().size > 1) {
            Text(item.content.take(400), maxLines = 2, overflow = TextOverflow.Ellipsis, color = content,
                style = MaterialTheme.typography.bodySmall)
        }
        Text(DateUtils.getRelativeTimeSpanString(item.lastCapturedAt).toString(), color = content.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusPane(title: String, message: String?, content: @Composable () -> Unit) {
    // Scrolls so the buttons stay reachable in the 260dp-high minimum freeform window.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!message.isNullOrBlank()) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}
