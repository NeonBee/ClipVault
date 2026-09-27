package dev.clipvault.app.quickpaste

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.data.ClipItem
import dev.clipvault.app.data.ClipQuery
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuickPasteGate { LOCKED, UNLOCKING, SETUP_REQUIRED, READY }

/**
 * Held only in memory. The query and results are never written to disk or saved instance state,
 * and both are dropped whenever the vault locks or the window closes.
 */
data class QuickPasteState(
    val gate: QuickPasteGate = QuickPasteGate.LOCKED,
    val query: String = "",
    val results: List<ClipItem> = emptyList(),
    val selected: Int = 0,
    val searched: Boolean = false,
    val message: String? = null,
) {
    val selectedItem: ClipItem? get() = results.getOrNull(selected)
}

class QuickPasteViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val RESULT_LIMIT = 50
        const val SEARCH_DEBOUNCE_MS = 120L
    }

    private val app = application as ClipVaultApp
    // Wraps the app's single IO executor; never closed because closing would shut the executor down.
    private val io = app.io().asCoroutineDispatcher()
    private val mutableState = MutableStateFlow(QuickPasteState())
    val state: StateFlow<QuickPasteState> = mutableState.asStateFlow()
    private var searchJob: Job? = null

    /** The biometric prompt opens by itself once per window; later attempts need the Unlock button. */
    var autoPromptConsumed = false

    fun onUnlocking() = mutableState.update { it.copy(gate = QuickPasteGate.UNLOCKING, message = null) }

    fun onVaultOpen() {
        if (mutableState.value.gate == QuickPasteGate.READY) return
        mutableState.value = QuickPasteState(gate = QuickPasteGate.READY)
        search("", immediate = true)
    }

    fun onVaultLocked(message: String? = null) {
        searchJob?.cancel()
        mutableState.value = QuickPasteState(gate = QuickPasteGate.LOCKED, message = message)
    }

    fun onSetupRequired(message: String) {
        searchJob?.cancel()
        mutableState.value = QuickPasteState(gate = QuickPasteGate.SETUP_REQUIRED, message = message)
    }

    fun setQuery(query: String) {
        if (mutableState.value.gate != QuickPasteGate.READY) return
        mutableState.update { it.copy(query = query, selected = 0) }
        search(query, immediate = query.isBlank())
    }

    fun onKey(action: QuickPasteKeyAction) = mutableState.update {
        it.copy(selected = QuickPasteSelection.move(it.selected, action, it.results.size))
    }

    fun select(index: Int) = mutableState.update {
        if (index in it.results.indices) it.copy(selected = index) else it
    }

    /** Clears decrypted content before the window goes away. */
    fun clear() = onVaultLocked()

    private fun search(query: String, immediate: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            val repository = app.repository()
            if (repository == null) {
                onVaultLocked()
                return@launch
            }
            val request = ClipQuery.builder().search(query).page(RESULT_LIMIT, 0).build()
            // The vault can close between repository() and the query; treat that as locked, not as a crash.
            val items = withContext(io) { runCatching { repository.query(request).items }.getOrNull() }
            if (items == null && !app.isUnlocked) {
                onVaultLocked()
                return@launch
            }
            mutableState.update {
                if (it.gate != QuickPasteGate.READY || it.query != query) it
                else it.copy(results = items.orEmpty(), selected = 0, searched = true)
            }
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        mutableState.value = QuickPasteState()
        super.onCleared()
    }
}
