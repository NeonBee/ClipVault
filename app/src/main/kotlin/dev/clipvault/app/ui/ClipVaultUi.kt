@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.clipvault.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.R
import dev.clipvault.app.data.CaptureRule
import dev.clipvault.app.data.ClipItem
import dev.clipvault.app.data.ClipQuery
import dev.clipvault.app.data.CollectionRecord
import dev.clipvault.app.data.RetentionPolicy
import dev.clipvault.app.nativecore.NativeClassifier
import dev.clipvault.app.ui.settings.AccentPalette
import dev.clipvault.app.ui.settings.AppLanguage
import dev.clipvault.app.ui.settings.ThemeMode
import dev.clipvault.app.ui.settings.ThemeSettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class MainDestination(val route: String, val label: Int, val icon: ImageVector) {
    LIBRARY("library", R.string.library, Icons.Default.Home),
    COLLECTIONS("collections", R.string.collections, Icons.Default.CollectionsBookmark),
    INSIGHTS("insights", R.string.insights, Icons.Default.Analytics),
    SETTINGS("settings", R.string.settings, Icons.Default.Settings),
}

@Composable
fun ClipVaultUi(
    viewModel: VaultViewModel,
    themeSettings: ThemeSettings,
    shizukuReady: Boolean,
    captureEnabled: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onToggleCapture: () -> Unit,
    onCopy: (ClipItem) -> Unit,
    onExportSelection: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message, state.error) {
        (state.error ?: state.message)?.let { snackbar.showSnackbar(it) }
        viewModel.clearMessage()
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Crossfade(targetState = state.unlocked, animationSpec = tween(if (themeSettings.reducedMotion) 0 else 260), label = "vault") { unlocked ->
            if (!unlocked) LockScreen(state.busy, state.error, state.pendingCount, onUnlock)
            else VaultNavigation(viewModel, themeSettings, state, shizukuReady, captureEnabled,
                onLock, onToggleCapture, onCopy, onExportSelection, onExportBackup, onImportBackup, snackbar)
        }
        if (state.busy) Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = .42f), modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun LockScreen(busy: Boolean, error: String?, pendingCount: Int, onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Fingerprint, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.lock_screen_description), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pendingCount > 0) AssistChip(onClick = {}, label = { Text(stringResource(R.string.pending_count, pendingCount)) })
            Button(onClick = onUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.unlock_vault))
            }
            AnimatedVisibility(error != null) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.offline_encrypted), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun VaultNavigation(
    viewModel: VaultViewModel,
    theme: ThemeSettings,
    state: VaultUiState,
    shizukuReady: Boolean,
    captureEnabled: Boolean,
    onLock: () -> Unit,
    onToggleCapture: () -> Unit,
    onCopy: (ClipItem) -> Unit,
    onExportSelection: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val isMainDestination = MainDestination.entries.any { it.route == route }
    BoxWithConstraints {
        val expanded = maxWidth >= 800.dp
        Row(Modifier.fillMaxSize()) {
            if (expanded && isMainDestination) AdaptiveRail(nav, route)
            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { if (!expanded && isMainDestination) BottomNavigation(nav, route) },
            ) { padding ->
                NavHost(navController = nav, startDestination = MainDestination.LIBRARY.route,
                    modifier = Modifier.padding(padding)) {
                    composable("library") {
                        LibraryScreen(viewModel, state, onCopy, onLock,
                            onOpenTrash = { nav.navigate("trash") },
                            onOpenSearch = { nav.navigate("search") },
                            onExportSelection = onExportSelection,
                            onOpenDetail = { nav.navigate("detail/$it") })
                    }
                    composable("collections") {
                        CollectionsScreen(viewModel, state,
                            onOpenSmart = { filter -> viewModel.setFilter(filter); nav.navigate("library") },
                            onOpenCollection = { id -> viewModel.setFilter(SmartFilter.ALL, id); nav.navigate("library") },
                            onOpenTag = { id -> viewModel.setTagFilter(id); nav.navigate("library") })
                    }
                    composable("insights") { InsightsScreen(state) }
                    composable("settings") {
                        SettingsScreen(viewModel, theme, state, shizukuReady, captureEnabled,
                            onToggleCapture, onExport, onImport, onOpenTrash = { nav.navigate("trash") })
                    }
                    composable("trash") {
                        TrashScreen(viewModel, onBack = nav::popBackStack, onOpenDetail = { nav.navigate("detail/$it") })
                    }
                    composable("search") {
                        AdvancedSearchScreen(viewModel, state, onBack = nav::popBackStack,
                            onCopy = onCopy, onExportSelection = onExportSelection,
                            onOpenDetail = { nav.navigate("detail/$it") })
                    }
                    composable("detail/{id}") { entry ->
                        val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                        DetailScreen(viewModel, id, onBack = nav::popBackStack, onCopy = onCopy)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(nav: NavHostController, route: String) {
    NavigationBar {
        MainDestination.entries.forEach { destination ->
            NavigationBarItem(selected = route == destination.route,
                onClick = { nav.navigate(destination.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                icon = { Icon(destination.icon, null) }, label = { Text(stringResource(destination.label)) })
        }
    }
}

@Composable
private fun AdaptiveRail(nav: NavHostController, route: String) {
    NavigationRail(Modifier.statusBarsPadding()) {
        MainDestination.entries.forEach { destination ->
            NavigationRailItem(selected = route == destination.route, onClick = { nav.navigate(destination.route) },
                icon = { Icon(destination.icon, null) }, label = { Text(stringResource(destination.label)) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    viewModel: VaultViewModel,
    state: VaultUiState,
    onCopy: (ClipItem) -> Unit,
    onLock: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSearch: () -> Unit,
    onExportSelection: (String) -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val clips = viewModel.clips.collectAsLazyPagingItems()
    var sortMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Column { Text(stringResource(R.string.library)); Text(stringResource(R.string.clip_count, state.stats.activeCount), style = MaterialTheme.typography.bodyMedium) } },
            actions = {
                BadgedBox(badge = { if (state.stats.trashCount > 0) Badge { Text(state.stats.trashCount.toString()) } }) {
                    IconButton(onClick = onOpenTrash) { Icon(Icons.Default.Delete, stringResource(R.string.trash)) }
                }
                Box { IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        ClipQuery.Sort.entries.forEach { sort -> DropdownMenuItem(
                            text = { Text(sortLabel(sort)) }, leadingIcon = { if (state.query.sort == sort) Icon(Icons.Default.Check, null) },
                            onClick = { viewModel.setSort(sort); sortMenu = false }) }
                    }
                }
                IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Tune, stringResource(R.string.advanced_search)) }
                IconButton(onClick = onLock) { Icon(Icons.Default.Lock, stringResource(R.string.lock)) }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        OutlinedTextField(value = state.query.search, onValueChange = viewModel::setSearch,
            leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (state.query.search.isNotEmpty()) IconButton(onClick = { viewModel.setSearch("") }) { Icon(Icons.Default.Close, null) } },
            placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lazyItems(SmartFilter.entries, key = { it.name }) { filter ->
                FilterChip(selected = state.query.filter == filter && state.query.collectionId == null,
                    onClick = { viewModel.setFilter(filter) }, label = { Text(filterLabel(filter)) })
            }
        }
        BulkSelectionBar(viewModel, state, onExportSelection)
        ClipList(clips, state.selectedIds, viewModel::toggleSelection, onOpenDetail, onCopy,
            viewModel::toggleFavorite, viewModel::togglePinned, viewModel::trash,
            emptyLabel = stringResource(R.string.empty_library))
    }
}

@Composable
private fun BulkSelectionBar(
    viewModel: VaultViewModel,
    state: VaultUiState,
    onExportSelection: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var chooseCollection by rememberSaveable { mutableStateOf(false) }
    var chooseTag by rememberSaveable { mutableStateOf(false) }
    AnimatedVisibility(state.selectedIds.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.selected_count, state.selectedIds.size), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconButton(viewModel::pinSelected) { Icon(Icons.Default.PushPin, stringResource(R.string.pin)) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_actions)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.favorite)) },
                            onClick = { menu = false; viewModel.favoriteSelected() },
                            leadingIcon = { Icon(Icons.Default.Favorite, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.move_to_collection)) },
                            onClick = { menu = false; chooseCollection = true },
                            leadingIcon = { Icon(Icons.Default.CollectionsBookmark, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.add_tag)) },
                            onClick = { menu = false; chooseTag = true },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.export_selected)) },
                            onClick = { menu = false; viewModel.exportSelected(onExportSelection) },
                            leadingIcon = { Icon(Icons.Default.Upload, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.delete)) },
                            onClick = { menu = false; viewModel.trashSelected() },
                            leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
                }
                IconButton(viewModel::clearSelection) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            }
        }
    }
    if (chooseCollection) SelectionPickerDialog(
        title = stringResource(R.string.select_collection),
        items = listOf(null to stringResource(R.string.no_collection)) + state.collections.map { it.id to it.name },
        onDismiss = { chooseCollection = false },
        onSelected = { viewModel.moveSelectedToCollection(it); chooseCollection = false },
    )
    if (chooseTag) SelectionPickerDialog(
        title = stringResource(R.string.select_tag),
        items = state.tags.map { it.id to it.name },
        onDismiss = { chooseTag = false },
        onSelected = { id -> id?.let(viewModel::addTagToSelected); chooseTag = false },
    )
}

@Composable
private fun SelectionPickerDialog(
    title: String,
    items: List<Pair<Long?, String>>,
    onDismiss: () -> Unit,
    onSelected: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(title) },
        text = {
            if (items.isEmpty()) Text(stringResource(R.string.empty_list))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                lazyItems(items) { (id, label) ->
                    Surface(onClick = { onSelected(id) }, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (id == null) Icons.Default.Close else Icons.AutoMirrored.Filled.Label, null)
                            Spacer(Modifier.width(12.dp)); Text(label)
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSearchScreen(
    viewModel: VaultViewModel,
    state: VaultUiState,
    onBack: () -> Unit,
    onCopy: (ClipItem) -> Unit,
    onExportSelection: (String) -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val clips = viewModel.clips.collectAsLazyPagingItems()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.advanced_search)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { TextButton({ viewModel.setSearch(""); viewModel.setDomain(""); viewModel.setFilter(SmartFilter.ALL) }) { Text(stringResource(R.string.clear_filters)) } },
        )
        OutlinedTextField(
            value = state.query.search,
            onValueChange = viewModel::setSearch,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            label = { Text(stringResource(R.string.search_content_title_note_domain)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = state.query.domain,
            onValueChange = viewModel::setDomain,
            label = { Text(stringResource(R.string.domain_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lazyItems(SmartFilter.entries) { filter ->
                FilterChip(state.query.filter == filter, { viewModel.setFilter(filter) }, { Text(filterLabel(filter)) })
            }
        }
        if (state.collections.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lazyItems(state.collections, key = { it.id }) { collection ->
                FilterChip(state.query.collectionId == collection.id,
                    { viewModel.setFilter(SmartFilter.ALL, collection.id) }, { Text(collection.name) })
            }
        }
        if (state.tags.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lazyItems(state.tags, key = { it.id }) { tag ->
                FilterChip(state.query.tagId == tag.id, { viewModel.setTagFilter(tag.id) }, { Text("#${tag.name}") })
            }
        }
        BulkSelectionBar(viewModel, state, onExportSelection)
        ClipList(clips, state.selectedIds, viewModel::toggleSelection, onOpenDetail, onCopy,
            viewModel::toggleFavorite, viewModel::togglePinned, viewModel::trash,
            emptyLabel = stringResource(R.string.no_search_results))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipList(
    clips: LazyPagingItems<ClipItem>,
    selected: Set<Long>,
    onSelect: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onCopy: (ClipItem) -> Unit,
    onFavorite: (ClipItem) -> Unit,
    onPin: (ClipItem) -> Unit,
    onDelete: (ClipItem) -> Unit,
    emptyLabel: String,
) {
    when {
        clips.loadState.refresh is LoadState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        clips.loadState.refresh is LoadState.Error -> ErrorState(onRetry = clips::retry)
        clips.itemCount == 0 -> EmptyState(emptyLabel)
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(count = clips.itemCount, key = clips.itemKey { it.id }) { index ->
                val item = clips[index] ?: return@items
                val previous = if (index > 0) clips.peek(index - 1) else null
                if (previous == null || dayKey(previous.lastCapturedAt) != dayKey(item.lastCapturedAt)) DateHeader(item.lastCapturedAt)
                ClipCard(item, item.id in selected, onSelect, onOpen, onCopy, onFavorite, onPin, onDelete)
            }
            if (clips.loadState.append is LoadState.Loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    item: ClipItem, selected: Boolean, onSelect: (Long) -> Unit, onOpen: (Long) -> Unit,
    onCopy: (ClipItem) -> Unit, onFavorite: (ClipItem) -> Unit, onPin: (ClipItem) -> Unit, onDelete: (ClipItem) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            role = Role.Button, onClick = { if (selected) onSelect(item.id) else onOpen(item.id) },
            onLongClick = { onSelect(item.id) }),
        colors = CardDefaults.elevatedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.pinned) Icon(Icons.Default.PushPin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(item.title.ifBlank { item.domain.ifBlank { typeLabel(item.flags) } }, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatTime(item.lastCapturedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.content, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.captureCount > 1) AssistChip(onClick = {}, label = { Text("×${item.captureCount}") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onPin(item) }) { Icon(if (item.pinned) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, stringResource(R.string.pin)) }
                IconButton(onClick = { onFavorite(item) }) { Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.favorite), tint = if (item.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { onCopy(item) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }
                IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionsScreen(
    viewModel: VaultViewModel, state: VaultUiState,
    onOpenSmart: (SmartFilter) -> Unit, onOpenCollection: (Long) -> Unit, onOpenTag: (Long) -> Unit,
) {
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var addTagDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.collections)) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { addDialog = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.new_collection)) }) }) { padding ->
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { CollectionCard(stringResource(R.string.favorites), state.stats.favoriteCount, Icons.Default.Favorite) { onOpenSmart(SmartFilter.FAVORITES) } }
            item { CollectionCard(stringResource(R.string.pinned), state.stats.pinnedCount, Icons.Default.PushPin) { onOpenSmart(SmartFilter.PINNED) } }
            item { CollectionCard(stringResource(R.string.links), state.stats.linkCount, Icons.Default.Category) { onOpenSmart(SmartFilter.LINKS) } }
            gridItems(state.collections, key = { it.id }) { collection -> CollectionCard(collection.name, collection.clipCount, Icons.Default.CollectionsBookmark) { onOpenCollection(collection.id) } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton({ addTagDialog = true }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.new_tag)) }
                }
            }
            gridItems(state.tags, key = { it.id }) { tag -> CollectionCard("#${tag.name}", tag.clipCount, Icons.AutoMirrored.Filled.Label) { onOpenTag(tag.id) } }
        }
    }
    if (addDialog) TextEntryDialog(stringResource(R.string.new_collection), stringResource(R.string.collection_name),
        onDismiss = { addDialog = false }, onConfirm = { viewModel.createCollection(it); addDialog = false })
    if (addTagDialog) TextEntryDialog(stringResource(R.string.new_tag), stringResource(R.string.tag_name),
        onDismiss = { addTagDialog = false }, onConfirm = { viewModel.createTag(it); addTagDialog = false })
}

@Composable
private fun CollectionCard(name: String, count: Int, icon: ImageVector, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.height(140.dp)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            }
            Column { Text(name, style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.clip_count, count), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsightsScreen(state: VaultUiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text(stringResource(R.string.insights)) })
        Text(stringResource(R.string.insights_local_only), Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(stringResource(R.string.total_clips), state.stats.activeCount.toString(), Icons.Default.Category)
            MetricCard(stringResource(R.string.captured_today), state.stats.todayCount.toString(), Icons.Default.Analytics)
            MetricCard(stringResource(R.string.links), state.stats.linkCount.toString(), Icons.Default.Category)
            MetricCard(stringResource(R.string.duplicate_saves), state.stats.duplicateCaptures.toString(), Icons.Default.ContentCopy)
            MetricCard(stringResource(R.string.characters), compactNumber(state.stats.totalCharacters), Icons.Default.Edit)
            MetricCard(stringResource(R.string.trash), state.stats.trashCount.toString(), Icons.Default.Delete)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, icon: ImageVector) {
    OutlinedCard(Modifier.width(170.dp).height(126.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: VaultViewModel, theme: ThemeSettings, state: VaultUiState,
    shizukuReady: Boolean, captureEnabled: Boolean, onToggleCapture: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onOpenTrash: () -> Unit,
) {
    var addRule by rememberSaveable { mutableStateOf(false) }
    var customRetention by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text(stringResource(R.string.settings)) })
        SettingsSection(stringResource(R.string.capture), Icons.Default.PlayCircle) {
            SettingRow(stringResource(if (captureEnabled) R.string.capture_running else R.string.capture_stopped),
                stringResource(if (shizukuReady) R.string.shizuku_ready else R.string.shizuku_offline),
                if (captureEnabled) Icons.Default.PauseCircle else Icons.Default.PlayCircle, onToggleCapture)
            val context = LocalContext.current
            SwitchRow(stringResource(R.string.skip_sensitive), stringResource(R.string.skip_sensitive_summary),
                (context.applicationContext as ClipVaultApp).settings().getBoolean(ClipVaultApp.PREF_SKIP_SENSITIVE, true), viewModel::setSkipSensitive)
            Text(stringResource(R.string.auto_lock_timeout), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L to R.string.immediately, 30_000L to R.string.thirty_seconds,
                    60_000L to R.string.one_minute, 300_000L to R.string.five_minutes).forEach { (value, label) ->
                    FilterChip(state.autoLockMs == value, { viewModel.setAutoLockMs(value) }, { Text(stringResource(label)) })
                }
            }
        }
        SettingsSection(stringResource(R.string.retention), Icons.Default.Backup) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AssistChip({ viewModel.setRetentionMonths(1) }, { Text(stringResource(R.string.one_month)) }) }
                item { AssistChip({ viewModel.setRetentionMonths(3) }, { Text(stringResource(R.string.three_months)) }) }
                item { AssistChip({ viewModel.setRetentionMonths(6) }, { Text(stringResource(R.string.six_months)) }) }
                item { AssistChip({ viewModel.setRetentionMonths(RetentionPolicy.FOREVER) }, { Text(stringResource(R.string.forever)) }) }
                item { AssistChip({ customRetention = true }, { Text(stringResource(R.string.custom)) }) }
            }
            SettingRow(stringResource(R.string.trash), stringResource(R.string.trash_retention), Icons.Default.RestoreFromTrash, onOpenTrash)
        }
        SettingsSection(stringResource(R.string.appearance), Icons.Default.Palette) {
            Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode -> FilterChip(theme.mode == mode, { viewModel.setThemeMode(mode) }, { Text(themeModeLabel(mode)) }) }
            }
            SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_summary), theme.dynamicColor, viewModel::setDynamicColor)
            Text(stringResource(R.string.accent_color), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                AccentPalette.entries.forEach { palette -> AccentButton(palette, palette == theme.accentPalette) { viewModel.setAccent(palette) } }
            }
            ThemePreview()
            SwitchRow(stringResource(R.string.reduced_motion), stringResource(R.string.reduced_motion_summary), theme.reducedMotion, viewModel::setReducedMotion)
            Text(stringResource(R.string.font_scale), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.85f, 1f, 1.15f, 1.30f).forEach { scale ->
                    FilterChip(kotlin.math.abs(theme.fontScale - scale) < .01f,
                        { viewModel.setFontScale(scale) }, { Text("${(scale * 100).toInt()}%") })
                }
            }
        }
        SettingsSection(stringResource(R.string.language), Icons.Default.Language) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { language -> FilterChip(theme.language == language, { viewModel.setLanguage(language) }, { Text(languageLabel(language)) }) }
            }
        }
        SettingsSection(stringResource(R.string.capture_rules), Icons.AutoMirrored.Filled.Rule) {
            state.rules.forEach { rule -> RuleRow(rule, { viewModel.setRuleEnabled(rule, it) }, { viewModel.deleteRule(rule.id) }) }
            OutlinedButton(onClick = { addRule = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_rule)) }
        }
        SettingsSection(stringResource(R.string.encrypted_backup), Icons.Default.Backup) {
            Text(stringResource(R.string.backup_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onExport) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.export_backup)) }
                OutlinedButton(onClick = onImport) { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.import_backup)) }
            }
        }
        SettingsSection(stringResource(R.string.diagnostics), Icons.Default.Info) {
            DiagnosticRow(stringResource(R.string.shizuku), if (shizukuReady) stringResource(R.string.ready) else stringResource(R.string.offline))
            DiagnosticRow(stringResource(R.string.pending_staging), state.pendingCount.toString())
            DiagnosticRow(stringResource(R.string.last_successful_capture),
                if (state.lastCaptureAt == 0L) stringResource(R.string.never) else formatDateTime(state.lastCaptureAt))
            DiagnosticRow(stringResource(R.string.last_sanitized_error),
                when (state.lastCaptureError) {
                    "" -> stringResource(R.string.none)
                    "bridge_unavailable" -> stringResource(R.string.bridge_unavailable)
                    // Sanitized bridge state identifier such as DEGRADED:BACKEND_NOT_SHELL.
                    else -> state.lastCaptureError
                })
            // Device validation records: bridge mode (event vs poll) and the Android build it ran on.
            DiagnosticRow(stringResource(R.string.bridge_mode),
                state.bridgeState.ifBlank { stringResource(R.string.none) })
            DiagnosticRow(stringResource(R.string.android_build),
                "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.DISPLAY}")
            // Why the vault last locked (reason code + device state, never content); newest first.
            if (state.lockEvents.isEmpty()) DiagnosticRow(stringResource(R.string.recent_locks), stringResource(R.string.none))
            state.lockEvents.forEach { event ->
                DiagnosticRow(stringResource(R.string.lock_at, formatLockTime(event.atMillis)), event.summary())
            }
            DiagnosticRow(stringResource(R.string.database), stringResource(R.string.encrypted_unlocked))
            DiagnosticRow(stringResource(R.string.network_permission), stringResource(R.string.not_present))
        }
        Spacer(Modifier.height(28.dp))
    }
    if (addRule) RuleDialog(onDismiss = { addRule = false }) { type, pattern -> viewModel.createRule(type, pattern); addRule = false }
    if (customRetention) NumberEntryDialog(stringResource(R.string.custom_retention), 7, 365,
        onDismiss = { customRetention = false }) { viewModel.setCustomRetention(it); customRetention = false }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(9.dp)); Text(title, style = MaterialTheme.typography.titleMedium) }
            HorizontalDivider(); content()
        }
    }
}

@Composable
private fun SettingRow(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChecked)
    }
}

@Composable
private fun AccentButton(palette: AccentPalette, selected: Boolean, onClick: () -> Unit) {
    val color = when (palette) {
        AccentPalette.VIOLET -> Color(0xFF7357D9); AccentPalette.BLUE -> Color(0xFF2962C6)
        AccentPalette.TEAL -> Color(0xFF006B60); AccentPalette.ROSE -> Color(0xFF9C405E)
        AccentPalette.AMBER -> Color(0xFFB37A00); AccentPalette.GRAPHITE -> Color(0xFF4F606F)
    }
    Surface(onClick = onClick, shape = CircleShape, color = color, modifier = Modifier.size(42.dp).semantics { contentDescription = palette.name }) {
        if (selected) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Color.White) }
    }
}

@Composable
private fun ThemePreview() {
    OutlinedCard {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) } }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(stringResource(R.string.live_preview), fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.live_preview_summary), style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = {}) { Text(stringResource(R.string.action)) }
        }
    }
}

@Composable
private fun RuleRow(rule: CaptureRule, onEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(rule.pattern, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(rule.type.name.lowercase() + " · " + rule.matchCount, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(rule.enabled, onEnabled); IconButton(onDelete) { Icon(Icons.Default.Delete, null) }
    }
}

/** Seconds matter when matching a lock to a reproduction step. */
internal fun formatLockTime(atMillis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(atMillis))

@Composable
internal fun DiagnosticRow(label: String, value: String) {
    // Both cells are weighted. An unweighted value is measured first and takes the whole width, so a
    // long value such as the Android build squeezed the label to a few dp, one character per line.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.45f))
        Text(value, Modifier.weight(0.55f), color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreen(viewModel: VaultViewModel, onBack: () -> Unit, onOpenDetail: (Long) -> Unit) {
    val trash = viewModel.trash.collectAsLazyPagingItems()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.trash)) }, navigationIcon = { IconButton({ viewModel.clearSelection(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        Text(stringResource(R.string.trash_retention), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(state.selectedIds.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.selected_count, state.selectedIds.size), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(viewModel::restoreSelected) { Icon(Icons.Default.RestoreFromTrash, stringResource(R.string.restore)) }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Default.DeleteForever, stringResource(R.string.delete_forever)) }
                    IconButton(viewModel::clearSelection) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                }
            }
        }
        when {
            trash.loadState.refresh is LoadState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            trash.itemCount == 0 -> EmptyState(stringResource(R.string.empty_trash))
            else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(count = trash.itemCount, key = trash.itemKey { it.id }) { index ->
                    val item = trash[index] ?: return@items
                    ElevatedCard(modifier = Modifier.fillMaxWidth().combinedClickable(
                        role = Role.Button,
                        onClick = { if (item.id in state.selectedIds) viewModel.toggleSelection(item.id) else onOpenDetail(item.id) },
                        onLongClick = { viewModel.toggleSelection(item.id) },
                    ), colors = CardDefaults.elevatedCardColors(
                        containerColor = if (item.id in state.selectedIds) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    )) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.content, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.align(Alignment.End)) {
                                TextButton({ viewModel.restore(item) }) { Icon(Icons.Default.RestoreFromTrash, null); Text(stringResource(R.string.restore)) }
                                TextButton({ viewModel.permanentlyDelete(item) }) { Icon(Icons.Default.DeleteForever, null); Text(stringResource(R.string.delete_forever)) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_forever)) },
        text = { Text(stringResource(R.string.delete_selected_confirmation, state.selectedIds.size)) },
        confirmButton = { TextButton({ confirmDelete = false; viewModel.permanentlyDeleteSelected() }) { Text(stringResource(R.string.delete_forever)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(viewModel: VaultViewModel, id: Long, onBack: () -> Unit, onCopy: (ClipItem) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(id, state.query.generation) { viewModel.loadDetail(id) }
    val item = state.detail
    var content by rememberSaveable(item?.id) { mutableStateOf(item?.content.orEmpty()) }
    var title by rememberSaveable(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var note by rememberSaveable(item?.id) { mutableStateOf(item?.note.orEmpty()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.clip_detail)) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { if (item != null) IconButton({ onCopy(item) }) { Icon(Icons.Default.ContentCopy, null) } })
        if (item == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(content, { content = it }, label = { Text(stringResource(R.string.content)) }, modifier = Modifier.fillMaxWidth(), minLines = 6)
            OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.note)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({}, { Text(item.domain.ifBlank { typeLabel(item.flags) }) })
                AssistChip({}, { Text("${item.characterCount} ${stringResource(R.string.characters).lowercase()}") })
                if (item.captureCount > 1) AssistChip({}, { Text("×${item.captureCount}") })
            }
            Button({ viewModel.saveDetail(id, content, title, note) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.save)) }
        }
    }
}

@Composable private fun DateHeader(time: Long) { Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(time)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)) }
@Composable private fun EmptyState(label: String) { Box(Modifier.fillMaxSize().padding(36.dp), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Category, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(12.dp)); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun ErrorState(onRetry: () -> Unit) { Box(Modifier.fillMaxSize(), Alignment.Center) { OutlinedButton(onRetry) { Icon(Icons.Default.Refresh, null); Text(stringResource(R.string.retry)) } } }

@Composable
private fun TextEntryDialog(title: String, hint: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismiss, confirmButton = { TextButton({ if (value.isNotBlank()) onConfirm(value) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } }, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(hint) }, singleLine = true) })
}

@Composable
private fun NumberEntryDialog(title: String, min: Int, max: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by rememberSaveable { mutableStateOf("30") }
    AlertDialog(onDismiss, confirmButton = { TextButton({ value.toIntOrNull()?.coerceIn(min, max)?.let(onConfirm) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } }, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it.filter(Char::isDigit) }, label = { Text("$min–$max") }, singleLine = true) })
}

@Composable
private fun RuleDialog(onDismiss: () -> Unit, onConfirm: (CaptureRule.Type, String) -> Unit) {
    var type by remember { mutableStateOf(CaptureRule.Type.DOMAIN) }; var pattern by rememberSaveable { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }
    AlertDialog(onDismiss, confirmButton = { TextButton({ if (pattern.isNotBlank()) onConfirm(type, pattern) }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } }, title = { Text(stringResource(R.string.add_rule)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box { OutlinedButton({ menu = true }) { Text(type.name); Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) { CaptureRule.Type.entries.forEach { option -> DropdownMenuItem({ Text(option.name) }, { type = option; menu = false }) } }
                }
                OutlinedTextField(pattern, { pattern = it }, label = { Text(stringResource(R.string.rule_pattern)) })
                Text(stringResource(R.string.rule_block_only), style = MaterialTheme.typography.bodyMedium)
            }
        })
}

@Composable private fun filterLabel(filter: SmartFilter): String = stringResource(when (filter) {
    SmartFilter.ALL -> R.string.all; SmartFilter.TODAY -> R.string.today; SmartFilter.YESTERDAY -> R.string.yesterday
    SmartFilter.THIS_WEEK -> R.string.this_week; SmartFilter.LINKS -> R.string.links; SmartFilter.INSTAGRAM -> R.string.instagram
    SmartFilter.YOUTUBE -> R.string.youtube; SmartFilter.TELEGRAM -> R.string.telegram; SmartFilter.GITHUB -> R.string.github
    SmartFilter.PERSIAN -> R.string.persian; SmartFilter.ENGLISH -> R.string.english; SmartFilter.MIXED -> R.string.mixed
    SmartFilter.LONG_TEXT -> R.string.long_text; SmartFilter.DATES -> R.string.dates; SmartFilter.EMAIL -> R.string.email
    SmartFilter.PHONE -> R.string.phone; SmartFilter.CODE -> R.string.code; SmartFilter.JSON -> R.string.json
    SmartFilter.FAVORITES -> R.string.favorites; SmartFilter.PINNED -> R.string.pinned
})
@Composable private fun sortLabel(sort: ClipQuery.Sort): String = stringResource(when (sort) { ClipQuery.Sort.NEWEST -> R.string.newest; ClipQuery.Sort.OLDEST -> R.string.oldest; ClipQuery.Sort.FREQUENT -> R.string.frequent; ClipQuery.Sort.LONGEST -> R.string.longest })
@Composable private fun themeModeLabel(mode: ThemeMode): String = stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.system; ThemeMode.LIGHT -> R.string.light; ThemeMode.DARK -> R.string.dark; ThemeMode.AMOLED -> R.string.amoled })
@Composable private fun languageLabel(language: AppLanguage): String = stringResource(when (language) { AppLanguage.SYSTEM -> R.string.system; AppLanguage.PERSIAN -> R.string.persian; AppLanguage.ENGLISH -> R.string.english })

private fun typeLabel(flags: Int): String = when {
    flags and NativeClassifier.INSTAGRAM != 0 -> "Instagram"; flags and NativeClassifier.YOUTUBE != 0 -> "YouTube"
    flags and NativeClassifier.TELEGRAM != 0 -> "Telegram"; flags and NativeClassifier.GITHUB != 0 -> "GitHub"
    flags and NativeClassifier.JSON != 0 -> "JSON"; flags and NativeClassifier.CODE != 0 -> "Code"
    flags and NativeClassifier.LINK != 0 -> "Link"; flags and NativeClassifier.PERSIAN != 0 -> "فارسی"
    flags and NativeClassifier.ENGLISH != 0 -> "English"; else -> "Text"
}
private fun formatTime(time: Long): String = java.text.DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
private fun formatDateTime(time: Long): String = java.text.DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private fun dayKey(time: Long): Int = Calendar.getInstance().run { timeInMillis = time; get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR) }
private fun compactNumber(value: Long): String = when { value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f); value >= 1_000 -> "%.1fK".format(value / 1_000f); else -> value.toString() }
