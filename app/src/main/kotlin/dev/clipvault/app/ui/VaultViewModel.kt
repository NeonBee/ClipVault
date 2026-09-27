package dev.clipvault.app.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.data.CaptureRule
import dev.clipvault.app.data.ClipItem
import dev.clipvault.app.data.ClipQuery
import dev.clipvault.app.data.CollectionRecord
import dev.clipvault.app.data.RetentionPolicy
import dev.clipvault.app.data.TagRecord
import dev.clipvault.app.data.VaultStats
import dev.clipvault.app.security.VaultLockLog
import dev.clipvault.app.ui.settings.AccentPalette
import dev.clipvault.app.ui.settings.AppLanguage
import dev.clipvault.app.ui.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class SmartFilter(val requiredFlag: Int = 0) {
    ALL,
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LINKS(dev.clipvault.app.nativecore.NativeClassifier.LINK),
    INSTAGRAM(dev.clipvault.app.nativecore.NativeClassifier.INSTAGRAM),
    YOUTUBE(dev.clipvault.app.nativecore.NativeClassifier.YOUTUBE),
    TELEGRAM(dev.clipvault.app.nativecore.NativeClassifier.TELEGRAM),
    GITHUB(dev.clipvault.app.nativecore.NativeClassifier.GITHUB),
    PERSIAN(dev.clipvault.app.nativecore.NativeClassifier.PERSIAN),
    ENGLISH(dev.clipvault.app.nativecore.NativeClassifier.ENGLISH),
    MIXED(dev.clipvault.app.nativecore.NativeClassifier.MIXED_LANGUAGE),
    LONG_TEXT(dev.clipvault.app.nativecore.NativeClassifier.LONG_TEXT),
    DATES(dev.clipvault.app.nativecore.NativeClassifier.DATE),
    EMAIL(dev.clipvault.app.nativecore.NativeClassifier.EMAIL),
    PHONE(dev.clipvault.app.nativecore.NativeClassifier.PHONE),
    CODE(dev.clipvault.app.nativecore.NativeClassifier.CODE),
    JSON(dev.clipvault.app.nativecore.NativeClassifier.JSON),
    FAVORITES,
    PINNED,
}

data class LibraryQueryState(
    val search: String = "",
    val filter: SmartFilter = SmartFilter.ALL,
    val sort: ClipQuery.Sort = ClipQuery.Sort.NEWEST,
    val collectionId: Long? = null,
    val tagId: Long? = null,
    val domain: String = "",
    val generation: Long = 0,
)

data class VaultUiState(
    val unlocked: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val query: LibraryQueryState = LibraryQueryState(),
    val selectedIds: Set<Long> = emptySet(),
    val detail: ClipItem? = null,
    val collections: List<CollectionRecord> = emptyList(),
    val tags: List<TagRecord> = emptyList(),
    val rules: List<CaptureRule> = emptyList(),
    val stats: VaultStats = VaultStats(0, 0, 0, 0, 0, 0, 0, 0),
    val pendingCount: Int = 0,
    val lastCaptureAt: Long = 0,
    val lastCaptureError: String = "",
    val bridgeState: String = "",
    /** Sanitized recent lock reasons, newest first (WP-06 session-continuity diagnosis). */
    val lockEvents: List<VaultLockLog.Event> = emptyList(),
    val autoLockMs: Long = 30_000,
)

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipVaultApp
    private val settingsRepository = app.container().settingsRepository
    private val mutableState = MutableStateFlow(VaultUiState(unlocked = app.isUnlocked))
    val state: StateFlow<VaultUiState> = mutableState.asStateFlow()
    val themeSettings = settingsRepository.settings

    val clips: Flow<PagingData<ClipItem>> = mutableState
        .map { Triple(it.unlocked, it.query, it.query.generation) }
        .distinctUntilChanged()
        .flatMapLatest { (unlocked, query, _) ->
            if (!unlocked) kotlinx.coroutines.flow.flowOf(PagingData.empty())
            else Pager(PagingConfig(pageSize = 50, prefetchDistance = 15, enablePlaceholders = false)) {
                ClipPagingSource(app) { limit, offset -> buildQuery(query, false, limit, offset) }
            }.flow
        }
        .cachedIn(viewModelScope)

    val trash: Flow<PagingData<ClipItem>> = mutableState
        .map { Pair(it.unlocked, it.query.generation) }
        .distinctUntilChanged()
        .flatMapLatest { (unlocked, _) ->
            if (!unlocked) kotlinx.coroutines.flow.flowOf(PagingData.empty())
            else Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
                ClipPagingSource(app) { limit, offset ->
                    ClipQuery.builder().trash(true).page(limit, offset).build()
                }
            }.flow
        }
        .cachedIn(viewModelScope)

    /**
     * The capture service writes bridge health asynchronously (after unlock it starts only once
     * refreshMetadata() already ran), so Diagnostics observes these keys instead of reading them
     * once. Held in a field: SharedPreferences keeps listeners weakly.
     */
    private val bridgePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == ClipVaultApp.PREF_BRIDGE_STATE || key == ClipVaultApp.PREF_LAST_CAPTURE_ERROR ||
            key == ClipVaultApp.PREF_LOCK_LOG) {
            publishBridgeDiagnostics()
        }
    }

    init {
        publishBridgeDiagnostics()
        app.settings().registerOnSharedPreferenceChangeListener(bridgePreferenceListener)
        if (app.isUnlocked) refreshMetadata()
    }

    override fun onCleared() {
        app.settings().unregisterOnSharedPreferenceChangeListener(bridgePreferenceListener)
        super.onCleared()
    }

    private fun publishBridgeDiagnostics() {
        val settings = app.settings()
        val bridgeState = settings.getString(ClipVaultApp.PREF_BRIDGE_STATE, "").orEmpty()
        val captureError = settings.getString(ClipVaultApp.PREF_LAST_CAPTURE_ERROR, "").orEmpty()
        val lockEvents = VaultLockLog.parse(settings.getString(ClipVaultApp.PREF_LOCK_LOG, ""))
        mutableState.update { it.copy(bridgeState = bridgeState, lastCaptureError = captureError, lockEvents = lockEvents) }
    }

    fun onVaultOpened(imported: Int) {
        mutableState.update {
            it.copy(unlocked = true, busy = false, error = null,
                message = if (imported > 0) "$imported staged clips imported" else null,
                query = it.query.copy(generation = it.query.generation + 1))
        }
        refreshMetadata()
    }

    /** The vault is open (possibly unlocked by another ClipVault window); show it without re-authenticating. */
    fun onVaultAvailable() {
        if (mutableState.value.unlocked) refresh() else onVaultOpened(0)
    }

    fun onVaultLocked() {
        mutableState.value = VaultUiState(unlocked = false, pendingCount = app.pending().count())
    }

    fun setBusy(value: Boolean) = mutableState.update { it.copy(busy = value, error = null) }
    fun setError(message: String) = mutableState.update { it.copy(busy = false, error = message) }
    fun clearMessage() = mutableState.update { it.copy(message = null, error = null) }

    fun setSearch(value: String) = mutableState.update {
        it.copy(query = it.query.copy(search = value, generation = it.query.generation + 1), selectedIds = emptySet())
    }

    fun setFilter(value: SmartFilter, collectionId: Long? = null, tagId: Long? = null) = mutableState.update {
        it.copy(query = it.query.copy(filter = value, collectionId = collectionId, tagId = tagId,
            generation = it.query.generation + 1), selectedIds = emptySet())
    }

    fun setTagFilter(tagId: Long) = setFilter(SmartFilter.ALL, tagId = tagId)

    fun setDomain(value: String) = mutableState.update {
        it.copy(query = it.query.copy(domain = value.trim(), generation = it.query.generation + 1),
            selectedIds = emptySet())
    }

    fun setSort(value: ClipQuery.Sort) = mutableState.update {
        it.copy(query = it.query.copy(sort = value, generation = it.query.generation + 1))
    }

    fun refresh() {
        mutableState.update { it.copy(query = it.query.copy(generation = it.query.generation + 1)) }
        refreshMetadata()
    }

    fun toggleSelection(id: Long) = mutableState.update { current ->
        val next = current.selectedIds.toMutableSet().apply { if (!add(id)) remove(id) }
        current.copy(selectedIds = next)
    }

    fun clearSelection() = mutableState.update { it.copy(selectedIds = emptySet()) }

    fun toggleFavorite(item: ClipItem) = repositoryAction {
        setFavorite(item.id, !item.favorite)
    }

    fun togglePinned(item: ClipItem) = repositoryAction {
        setPinned(item.id, !item.pinned)
    }

    fun trash(item: ClipItem) = repositoryAction("Moved to Trash") {
        moveToTrash(listOf(item.id), "manual", System.currentTimeMillis())
    }

    fun trashSelected() {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        repositoryAction("${ids.size} clips moved to Trash") {
            moveToTrash(ids, "manual", System.currentTimeMillis())
        }
        clearSelection()
    }

    fun pinSelected() {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        repositoryAction("Pinned ${ids.size} clips") { setPinned(ids, true) }
        clearSelection()
    }

    fun favoriteSelected() {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        repositoryAction("Added ${ids.size} clips to favorites") { setFavorite(ids, true) }
        clearSelection()
    }

    fun moveSelectedToCollection(collectionId: Long?) {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        repositoryAction("Moved ${ids.size} clips") { setCollection(ids, collectionId) }
        clearSelection()
    }

    fun addTagToSelected(tagId: Long) {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        repositoryAction("Tagged ${ids.size} clips") { addTagToClips(ids, tagId) }
        clearSelection()
    }

    fun exportSelected(onReady: (String) -> Unit) {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val repository = app.repository() ?: return@launch
            val text = ids.mapNotNull(repository::find).joinToString("\n\n— — —\n\n") { it.content }
            withContext(Dispatchers.Main) {
                if (text.isNotBlank()) onReady(text)
                clearSelection()
            }
        }
    }

    fun restore(item: ClipItem) = repositoryAction("Clip restored") { restore(listOf(item.id)) }
    fun permanentlyDelete(item: ClipItem) = repositoryAction("Clip permanently deleted") { permanentlyDelete(listOf(item.id)) }

    fun restoreSelected() {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        repositoryAction("Restored ${ids.size} clips") { restore(ids) }
        clearSelection()
    }

    fun permanentlyDeleteSelected() {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        repositoryAction("Permanently deleted ${ids.size} clips") { permanentlyDelete(ids) }
        clearSelection()
    }

    fun loadDetail(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val detail = app.repository()?.find(id)
            mutableState.update { it.copy(detail = detail) }
        }
    }

    fun saveDetail(id: Long, content: String, title: String, note: String) = repositoryAction("Saved") {
        edit(id, content, title, note)
    }

    fun createCollection(name: String, color: String = "violet") = repositoryAction("Collection created") {
        if (name.isNotBlank()) createCollection(name, color)
    }

    fun deleteCollection(id: Long) = repositoryAction("Collection removed") { deleteCollection(id) }

    fun createTag(name: String, color: String = "violet") = repositoryAction("Tag created") {
        if (name.isNotBlank()) createTag(name, color)
    }

    fun createRule(type: CaptureRule.Type, pattern: String) = repositoryAction("Capture rule added") {
        createRule(type, pattern)
        app.refreshRules()
    }

    fun setRuleEnabled(rule: CaptureRule, enabled: Boolean) = repositoryAction {
        setRuleEnabled(rule.id, enabled)
        app.refreshRules()
    }

    fun deleteRule(id: Long) = repositoryAction("Rule removed") {
        deleteRule(id)
        app.refreshRules()
    }

    fun setRetentionMonths(months: Int) {
        app.settings().edit().putInt(ClipVaultApp.PREF_RETENTION_MONTHS, months)
            .putInt(ClipVaultApp.PREF_CUSTOM_RETENTION_DAYS, 0).apply()
        app.applyRetentionNow()
        mutableState.update { it.copy(message = "Retention updated") }
    }

    fun setCustomRetention(days: Int) {
        val safe = days.coerceIn(RetentionPolicy.MIN_CUSTOM_DAYS, RetentionPolicy.MAX_CUSTOM_DAYS)
        app.settings().edit().putInt(ClipVaultApp.PREF_CUSTOM_RETENTION_DAYS, safe).apply()
        app.applyRetentionNow()
        mutableState.update { it.copy(message = "Retention set to $safe days") }
    }

    fun setSkipSensitive(value: Boolean) {
        app.settings().edit().putBoolean(ClipVaultApp.PREF_SKIP_SENSITIVE, value).apply()
    }

    fun setAutoLockMs(value: Long) {
        val safe = value.coerceIn(0L, 300_000L)
        app.settings().edit().putLong(ClipVaultApp.PREF_AUTO_LOCK_MS, safe).apply()
        mutableState.update { it.copy(autoLockMs = safe, message = "Auto-lock updated") }
    }

    fun setThemeMode(value: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(value) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(value) }
    fun setAccent(value: AccentPalette) = viewModelScope.launch { settingsRepository.setAccent(value) }
    fun setReducedMotion(value: Boolean) = viewModelScope.launch { settingsRepository.setReducedMotion(value) }
    fun setLanguage(value: AppLanguage) = viewModelScope.launch { settingsRepository.setLanguage(value) }
    fun setFontScale(value: Float) = viewModelScope.launch { settingsRepository.setFontScale(value) }

    fun refreshMetadata() {
        if (!app.isUnlocked) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repository = app.repository() ?: return@launch
                val nextStats = repository.stats()
                val nextCollections = repository.collections()
                val nextTags = repository.tags()
                val nextRules = repository.rules()
                val pending = app.pending().count()
                val lastCapture = app.settings().getLong(ClipVaultApp.PREF_LAST_CAPTURE_AT, 0L)
                val autoLock = app.settings().getLong(ClipVaultApp.PREF_AUTO_LOCK_MS, 30_000L)
                mutableState.update {
                    it.copy(stats = nextStats, collections = nextCollections, tags = nextTags,
                        rules = nextRules, pendingCount = pending, lastCaptureAt = lastCapture,
                        autoLockMs = autoLock)
                }
            } catch (error: RuntimeException) {
                mutableState.update { it.copy(error = error.message ?: "Could not read the vault") }
            }
        }
    }

    private fun repositoryAction(message: String? = null, block: dev.clipvault.app.data.VaultRepository.() -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repository = app.repository() ?: return@launch
                repository.block()
                mutableState.update {
                    it.copy(message = message, query = it.query.copy(generation = it.query.generation + 1))
                }
                refreshMetadata()
            } catch (error: RuntimeException) {
                mutableState.update { it.copy(error = error.message ?: "Operation failed") }
            }
        }
    }

    private fun buildQuery(state: LibraryQueryState, trash: Boolean, limit: Int, offset: Int): ClipQuery {
        val builder = ClipQuery.builder()
            .search(state.search)
            .requiredFlag(state.filter.requiredFlag)
            .favoritesOnly(state.filter == SmartFilter.FAVORITES)
            .pinnedOnly(state.filter == SmartFilter.PINNED)
            .trash(trash)
            .collectionId(state.collectionId)
            .tagId(state.tagId)
            .domain(state.domain)
            .sort(state.sort)
            .page(limit, offset)
        val now = System.currentTimeMillis()
        when (state.filter) {
            SmartFilter.TODAY -> builder.fromTime(startOfDay(now, 0))
            SmartFilter.YESTERDAY -> builder.fromTime(startOfDay(now, -1)).toTime(startOfDay(now, 0))
            SmartFilter.THIS_WEEK -> builder.fromTime(startOfWeek(now))
            else -> Unit
        }
        return builder.build()
    }

    private fun startOfDay(now: Long, dayOffset: Int): Long = Calendar.getInstance().run {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun startOfWeek(now: Long): Long = Calendar.getInstance().run {
        timeInMillis = now
        firstDayOfWeek = Calendar.SATURDAY
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
}
