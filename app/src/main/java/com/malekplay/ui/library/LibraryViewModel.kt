package com.malekplay.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.PlayerSettings
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val userDataRepository: UserDataRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<PlayerSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerSettings()
    )

    private val refreshMutex = Mutex()
    // If MediaStore changes during a scan, run one coalesced follow-up scan.
    private var pendingMediaStoreRefresh = false

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val allMedia = mediaRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val videos = mediaRepository.observeVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val audio = mediaRepository.observeAudio()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastPlayedMediaId = userDataRepository.observeLastPlayedMediaId()

    val seenNewMediaIds = settingsRepository.seenNewMediaIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val newMediaBaselineMs = settingsRepository.newMediaBaselineMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val folders = kotlinx.coroutines.flow.combine(
        mediaRepository.observeFolders(),
        allMedia,
        seenNewMediaIds,
        newMediaBaselineMs
    ) { folderRows, mediaRows, seenIds, baselineMs ->
        // Count unseen arrivals in one pass. Existing files at the first baseline
        // are excluded; a newly indexed item remains new until playback starts.
        val unseenByFolder = unseenNewMediaCountsByFolder(
            records = mediaRows.asSequence().map { row ->
                NewMediaRecord(
                    folderPath = row.media.folderPath,
                    mediaId = row.media.id,
                    addedAtMs = row.media.addedAtMs
                )
            },
            baselineMs = baselineMs,
            seenIds = seenIds
        )
        folderRows.map { folder ->
            folder.copy(newItemCount = unseenByFolder[folder.path] ?: 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local AI-style inference over existing library metadata; no model, network or extra storage. */
    val smartRecommendations = combine(allMedia, lastPlayedMediaId) { items, lastPlayedId ->
        SmartRecommendationPolicy.analyze(items, lastPlayedId)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Genre collections are inferred from the current index and never persisted. */
    val smartCollections = allMedia.map { items ->
        SmartRecommendationPolicy.collections(items)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching = mediaRepository.observeContinueWatching(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats = mediaRepository.observeStats()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.malekplay.domain.model.LibraryStats()
        )

    init {
        viewModelScope.launch {
            settingsRepository.ensureNewMediaBaseline()
            refresh(force = false)
        }
    }

    /** Explicit user refresh forces a scan. */
    fun refresh() = refresh(force = true)

    /**
     * Refresh after MediaStore reports a change. A short throttle coalesces the
     * burst of notifications Android emits while one file is being indexed.
     */
    fun refreshAfterMediaStoreChange() =
        refresh(force = false, minIntervalMs = 2_000L, retryIfBusy = true)

    private fun refresh(
        force: Boolean,
        minIntervalMs: Long = 5 * 60 * 1_000L,
        retryIfBusy: Boolean = false
    ) {
        viewModelScope.launch {
            if (!refreshMutex.tryLock()) {
                if (retryIfBusy) pendingMediaStoreRefresh = true
                return@launch
            }
            _isRefreshing.value = true
            _error.value = null
            try {
                if (force) mediaRepository.refresh()
                else mediaRepository.refreshIfStale(minIntervalMs = minIntervalMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.localizedMessage ?: "Could not refresh the library"
            } finally {
                _isRefreshing.value = false
                val runPendingRefresh = pendingMediaStoreRefresh
                pendingMediaStoreRefresh = false
                refreshMutex.unlock()
                if (runPendingRefresh) refresh(force = true)
            }
        }
    }

    /** Search indexed library metadata locally and rank the strongest matches. */
    fun search(query: String): Flow<List<LibraryItem>> = allMedia.map { items ->
        SmartRecommendationPolicy.search(items, query)
    }

    fun folderContents(path: String) = mediaRepository.observeFolder(path)

    fun toggleFavourite(mediaId: String) {
        viewModelScope.launch { userDataRepository.toggleFavourite(mediaId) }
    }

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { settingsRepository.update { it.copy(sortMode = mode) } }
    }

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { settingsRepository.update { it.copy(viewMode = mode) } }
    }

    /** Applies the persisted sort order to a list. */
    fun applySort(items: List<LibraryItem>, mode: SortMode): List<LibraryItem> = when (mode) {
        SortMode.NEWEST -> items.sortedByDescending { it.media.addedAtMs }
        SortMode.OLDEST -> items.sortedBy { it.media.addedAtMs }
        SortMode.NAME -> items.sortedBy { it.media.title.lowercase(Locale.ROOT) }
        SortMode.DURATION -> items.sortedByDescending { it.media.durationMs }
        SortMode.SIZE -> items.sortedByDescending { it.media.sizeBytes }
    }

    fun applyFolderSort(items: List<MediaFolder>, mode: SortMode): List<MediaFolder> = when (mode) {
        SortMode.NAME -> items.sortedBy { it.name.lowercase(Locale.ROOT) }
        SortMode.SIZE -> items.sortedByDescending { it.totalBytes }
        SortMode.NEWEST -> items.sortedByDescending { it.lastAddedMs }
        SortMode.OLDEST -> items.sortedBy { it.lastAddedMs }
        SortMode.DURATION -> items.sortedByDescending { it.itemCount }
    }
}
