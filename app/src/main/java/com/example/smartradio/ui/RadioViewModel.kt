package com.example.smartradio.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.smartradio.data.DiscoveredStation
import com.example.smartradio.data.RadioBrowserApi
import com.example.smartradio.data.Station
import com.example.smartradio.data.StationKind
import com.example.smartradio.data.StationRepository
import com.example.smartradio.service.RadioPlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@UnstableApi
class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StationRepository(application)
    private val directory = RadioBrowserApi()

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentStationId = MutableStateFlow<String?>(null)
    val currentStationId: StateFlow<String?> = _currentStationId.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _nowPlayingTrack = MutableStateFlow<String?>(null)
    val nowPlayingTrack: StateFlow<String?> = _nowPlayingTrack.asStateFlow()

    private val _searchResults = MutableStateFlow<List<DiscoveredStation>>(emptyList())
    val searchResults: StateFlow<List<DiscoveredStation>> = _searchResults.asStateFlow()

    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private var searchJob: Job? = null

    private var controller: MediaController? = null

    init {
        viewModelScope.launch {
            repository.stations.collect { _stations.value = it }
        }
        val sessionToken = SessionToken(
            application,
            ComponentName(application, RadioPlaybackService::class.java)
        )
        val future = MediaController.Builder(application, sessionToken).buildAsync()
        future.addListener({
            controller = future.get()
            _currentStationId.value = controller?.currentMediaItem?.mediaId
            _isPlaying.value = controller?.isPlaying ?: false
            controller?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                    if (playing) _playbackError.value = null
                }
                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int
                ) {
                    _currentStationId.value = mediaItem?.mediaId
                    _nowPlayingTrack.value = null
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _playbackError.value = error.message ?: error.errorCodeName
                }
                override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                    // ICY (Shoutcast/Icecast) streams embed a live "now playing" title
                    // directly in the audio stream — not every station sends this.
                    for (i in 0 until metadata.length()) {
                        val entry = metadata.get(i)
                        if (entry is androidx.media3.extractor.metadata.icy.IcyInfo) {
                            val title = entry.title
                            _nowPlayingTrack.value = if (!title.isNullOrBlank()) title else null
                        }
                    }
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun addStation(name: String, streamUrl: String, kind: StationKind) {
        viewModelScope.launch { repository.addStation(name, streamUrl, kind) }
    }

    fun addDiscoveredStation(station: DiscoveredStation) {
        viewModelScope.launch {
            repository.addStation(station.name, station.streamUrl, station.guessedKind())
        }
    }

    /** Debounced directory search — call on every keystroke in the search field. */
    fun searchStations(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searchError.value = null
            _searchLoading.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // debounce
            _searchLoading.value = true
            _searchError.value = null
            directory.search(query)
                .onSuccess { _searchResults.value = it.distinctBy { s -> s.streamUrl } }
                .onFailure {
                    _searchResults.value = emptyList()
                    _searchError.value = "Couldn't reach the station directory — check your connection."
                }
            _searchLoading.value = false
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _searchError.value = null
        _searchLoading.value = false
    }

    fun removeStation(id: String) {
        viewModelScope.launch { repository.removeStation(id) }
    }

    fun reorder(newOrderIds: List<String>) {
        viewModelScope.launch { repository.reorder(newOrderIds) }
    }

    fun selectStation(id: String) {
        _playbackError.value = null
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand("SELECT_STATION", android.os.Bundle.EMPTY),
            android.os.Bundle().apply { putString("stationId", id) }
        )
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
