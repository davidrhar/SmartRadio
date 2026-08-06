package com.example.smartradio.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.smartradio.data.DiscoveredStation
import com.example.smartradio.data.LocationCountryResolver
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
import java.util.Locale

/** A one-off event describing an automatic ad/talk skip, for a transient toast in the UI. */
data class AutoSkipEvent(val id: Long, val fromStationName: String, val toStationName: String)

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

    // True when the rotation controller has hopped through the whole shortlist
    // several times over and given up, pausing playback — distinct from a
    // user-initiated pause.
    private val _noStationsAvailable = MutableStateFlow(false)
    val noStationsAvailable: StateFlow<Boolean> = _noStationsAvailable.asStateFlow()

    private val _autoSkipEvent = MutableStateFlow<AutoSkipEvent?>(null)
    val autoSkipEvent: StateFlow<AutoSkipEvent?> = _autoSkipEvent.asStateFlow()

    private val _searchResults = MutableStateFlow<List<DiscoveredStation>>(emptyList())
    val searchResults: StateFlow<List<DiscoveredStation>> = _searchResults.asStateFlow()

    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // "Popular Stations" (or "Popular Near You") shown before the user types anything.
    private val _popularStations = MutableStateFlow<List<DiscoveredStation>>(emptyList())
    val popularStations: StateFlow<List<DiscoveredStation>> = _popularStations.asStateFlow()

    private val _popularLoading = MutableStateFlow(false)
    val popularLoading: StateFlow<Boolean> = _popularLoading.asStateFlow()

    // Null = showing the global list; non-null = showing results for that resolved country.
    private val _nearYouCountryCode = MutableStateFlow<String?>(null)
    private val _nearYouCountryName = MutableStateFlow<String?>(null)
    val nearYouCountryName: StateFlow<String?> = _nearYouCountryName.asStateFlow()

    private val _nearYouLoading = MutableStateFlow(false)
    val nearYouLoading: StateFlow<Boolean> = _nearYouLoading.asStateFlow()

    // True once Android has permanently denied further permission prompts (two denials,
    // or "don't ask again"). The chip greys out rather than doing nothing when tapped.
    private val _locationPermanentlyDenied = MutableStateFlow(false)
    val locationPermanentlyDenied: StateFlow<Boolean> = _locationPermanentlyDenied.asStateFlow()

    // A search result that failed Radio Browser's last connectivity check — held here
    // until the user confirms they want to add it anyway, or cancels.
    private val _pendingRiskyStation = MutableStateFlow<DiscoveredStation?>(null)
    val pendingRiskyStation: StateFlow<DiscoveredStation?> = _pendingRiskyStation.asStateFlow()

    private var searchJob: Job? = null
    private var popularJob: Job? = null

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
                    _noStationsAvailable.value = false
                    val autoSkipFrom = mediaItem?.mediaMetadata?.extras?.getString("autoSkipFrom")
                    if (!autoSkipFrom.isNullOrBlank()) {
                        _autoSkipEvent.value = AutoSkipEvent(
                            id = System.currentTimeMillis(),
                            fromStationName = autoSkipFrom,
                            toStationName = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                        )
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _playbackError.value = error.message ?: error.errorCodeName
                }
                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    // ICY (Shoutcast/Icecast) streams embed a live "now playing" title
                    // directly in the audio stream — not every station sends this. The
                    // service folds it into MediaMetadata.artist (see
                    // RadioPlaybackService.onMetadata) since raw Metadata/onMetadata
                    // events don't cross the MediaSession -> MediaController boundary,
                    // but MediaMetadata changes do. Same channel carries the
                    // "exhausted the shortlist" flag via extras.
                    val title = mediaMetadata.artist?.toString()
                    _nowPlayingTrack.value = if (!title.isNullOrBlank()) title else null
                    _noStationsAvailable.value = mediaMetadata.extras?.getBoolean("noStationsAvailable", false) ?: false
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun addStation(name: String, streamUrl: String, kind: StationKind) {
        viewModelScope.launch { repository.addStation(name, streamUrl, kind) }
    }

    /** Adds immediately unless the station failed its last connectivity check, in which case it's held for confirmation. */
    fun addDiscoveredStation(station: DiscoveredStation) {
        if (station.failedLastCheck()) {
            _pendingRiskyStation.value = station
            return
        }
        viewModelScope.launch {
            repository.addStation(
                name = station.name,
                streamUrl = station.streamUrl,
                kind = station.guessedKind(),
                favicon = station.favicon,
                codec = station.codec,
                bitrate = station.bitrate,
                language = station.language,
                country = station.country,
                countryCode = station.countryCode,
                state = station.state,
                clickCount = station.clickCount
            )
        }
    }

    fun confirmAddPendingStation() {
        val station = _pendingRiskyStation.value ?: return
        viewModelScope.launch {
            repository.addStation(
                name = station.name,
                streamUrl = station.streamUrl,
                kind = station.guessedKind(),
                favicon = station.favicon,
                codec = station.codec,
                bitrate = station.bitrate,
                language = station.language,
                country = station.country,
                countryCode = station.countryCode,
                state = station.state,
                clickCount = station.clickCount
            )
        }
        _pendingRiskyStation.value = null
    }

    fun cancelPendingStation() {
        _pendingRiskyStation.value = null
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
            delay(250) // debounce
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

    /** Call once when the "Find a station" dialog opens, to populate the pre-typing default list. */
    fun loadPopularStations() {
        popularJob?.cancel()
        popularJob = viewModelScope.launch {
            _popularLoading.value = true
            directory.popular(countryCode = _nearYouCountryCode.value)
                .onSuccess { _popularStations.value = it.distinctBy { s -> s.streamUrl } }
                .onFailure { _popularStations.value = emptyList() }
            _popularLoading.value = false
        }
    }

    /** Call after the location permission launcher reports the permission was granted. */
    fun onLocationPermissionGranted() {
        viewModelScope.launch {
            _nearYouLoading.value = true
            val countryCode = LocationCountryResolver.resolveCountryCode(getApplication())
            if (countryCode != null) {
                _nearYouCountryCode.value = countryCode
                _nearYouCountryName.value = runCatching {
                    Locale("", countryCode).displayCountry
                }.getOrDefault(countryCode)
                loadPopularStations()
            }
            _nearYouLoading.value = false
        }
    }

    /** Call after the location permission launcher reports denial, with whether it's now permanent. */
    fun onLocationPermissionDenied(permanentlyDenied: Boolean) {
        _locationPermanentlyDenied.value = permanentlyDenied
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
