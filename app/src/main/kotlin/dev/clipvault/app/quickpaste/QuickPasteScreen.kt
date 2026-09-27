package dev.clipvault.app.quickpaste

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
    fun row(index: Int) = "quick_paste_row_$index"
}

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
) {
    // The root is focusable so Esc (and Enter on the lock pane) reach the key handler even when no
    // field or button holds focus; clickable buttons are not focusable in touch mode.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(state.gate) {
        if (state.gate != QuickPasteGate.READY) runCatching { rootFocus.requestFocus() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
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
                    QuickPasteGate.READY -> SearchPane(state, onQueryChange, onSelectIndex, onConfirm)
                    QuickPasteGate.UNLOCKING -> StatusPane(stringResource(R.string.quick_paste_unlocking), null) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                    QuickPasteGate.LOCKED -> StatusPane(stringResource(R.string.quick_paste_locked), state.message) {
                        Icon(Icons.Default.Lock, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onClose) { Text(stringResource(R.string.quick_paste_close)) }
                            Button(onClick = onUnlock) { Text(stringResource(R.string.unlock_vault)) }
                        }
                    }
                    QuickPasteGate.SETUP_REQUIRED -> StatusPane(stringResource(R.string.quick_paste_title), state.message) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onClose) { Text(stringResource(R.string.quick_paste_close)) }
                            Button(onClick = onOpenApp) { Text(stringResource(R.string.quick_paste_open_app)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SearchPane(
    state: QuickPasteState,
    onQueryChange: (String) -> Unit,
    onSelectIndex: (Int) -> Unit,
    onConfirm: () -> Unit,
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
        leadingIcon = { Icon(Icons.Default.Search, null) },
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
    Text(stringResource(R.string.quick_paste_keys_hint), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!message.isNullOrBlank()) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}
