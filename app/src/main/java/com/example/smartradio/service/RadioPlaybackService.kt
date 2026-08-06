package com.example.smartradio.service

import android.content.Context
import android.net.Uri
import androidx.media.utils.MediaConstants
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.smartradio.R
import com.example.smartradio.audio.MusicDetectionEngine
import com.example.smartradio.audio.PcmTapSink
import com.example.smartradio.audio.YamnetClassifier
import com.example.smartradio.data.DiscoveredStation
import com.example.smartradio.data.RadioBrowserApi
import com.example.smartradio.data.Station
import com.example.smartradio.data.StationRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val ROOT_MEDIA_ID = "root"
private const val SEARCH_MEDIA_ID_PREFIX = "search:"
private const val EMPTY_PLACEHOLDER_MEDIA_ID = "empty_placeholder"

/** Matches the phone UI's AutoSkipToast duration, for the Auto-side equivalent below. */
private const val AUTO_SKIP_MESSAGE_DURATION_MS = 3500L

@UnstableApi
class RadioPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null

    private lateinit var classifier: YamnetClassifier
    private val detectionEngine = MusicDetectionEngine()
    private val classifierExecutor = Executors.newSingleThreadExecutor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rotationController: StationRotationController
    private lateinit var repository: StationRepository
    private val directory = RadioBrowserApi()

    /**
     * Snapshot of the last-seen shortlist, for the browse-tree callbacks below, which must
     * answer synchronously and can't collect the repository's Flow themselves. Populated by
     * the same repository.stations collector that already feeds the rotation controller.
     */
    @Volatile private var currentStations: List<Station> = emptyList()

    /** Results from the most recent onSearch() calls, for onGetSearchResult. */
    private val searchResultsByQuery = mutableMapOf<String, List<DiscoveredStation>>()

    /** Flat index of every search result seen so far, keyed by stationUuid, for onSetMediaItems. */
    private val searchResultsById = mutableMapOf<String, DiscoveredStation>()

    override fun onCreate() {
        super.onCreate()
        repository = StationRepository(this)
        classifier = YamnetClassifier(this)

        val tapSink = PcmTapSink { window, _ ->
            // Run inference off the playback thread so it never causes audio glitches.
            classifierExecutor.execute {
                try {
                    val verdict = classifier.classify(window)
                    android.util.Log.d(
                        "SmartRadioClassifier",
                        "music=${verdict.musicScore} speech=${verdict.speechScore} isMusic=${verdict.isMusic}"
                    )
                    serviceScope.launch { detectionEngine.onVerdict(verdict) }
                } catch (t: Throwable) {
                    // Without this, a broken model/label file fails silently forever —
                    // auto-skip would just never fire, with zero indication why.
                    android.util.Log.e("SmartRadioClassifier", "Classification failed", t)
                }
            }
        }
        val teeProcessor = TeeAudioProcessor(tapSink)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(teeProcessor)
                    )
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory).build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            // Raw ICY Metadata events (Player.Listener.onMetadata) never cross the
            // MediaSession -> MediaController IPC boundary in media3 — only synced
            // player state (like MediaMetadata) does. So instead of exposing the ICY
            // title as a one-shot event, fold it into the current MediaItem's
            // MediaMetadata (as the artist) via replaceMediaItem, which *is* synced
            // and reaches MediaController listeners as onMediaMetadataChanged.
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry !is androidx.media3.extractor.metadata.icy.IcyInfo) continue
                    val title = entry.title?.takeIf { it.isNotBlank() }?.let(::cleanIcyTitle)
                    val currentItem = player.currentMediaItem ?: continue
                    if (currentItem.mediaMetadata.artist?.toString() == title) continue
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex == androidx.media3.common.C.INDEX_UNSET) continue
                    player.replaceMediaItem(
                        currentIndex,
                        currentItem.buildUpon()
                            .setMediaMetadata(
                                currentItem.mediaMetadata.buildUpon().setArtist(title).build()
                            )
                            .build()
                    )
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Unreachable stream, dropped connection, unsupported format, etc. —
                // treat exactly like sustained non-music: hop to the next station.
                rotationController.onPlaybackError()
            }
        })

        rotationController = StationRotationController(
            onSwitchTo = { station, reason -> playStation(station, reason) },
            onExhausted = { markExhausted() }
        )

        // Android Auto's standard transport buttons only drive Player.seekToNext()/
        // seekToPrevious() — never our custom SELECT_STATION session command. But
        // ExoPlayer itself only ever holds a single MediaItem (see playStation()
        // below), so it has no real "next"/"previous" to seek to. Wrap it so the
        // session (and every MediaController, including Auto) sees a player that
        // always advertises seek-to-next/previous as available, and routes those
        // calls through the same shortlist logic StationRotationController already
        // implements, rather than ExoPlayer's own (nonexistent) playlist timeline.
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return availableCommands.contains(command)
            }

            override fun seekToNext() {
                rotationController.skipToNext()
            }

            override fun seekToPrevious() {
                rotationController.skipToPrevious()
            }
        }

        val selectStationCommand = androidx.media3.session.SessionCommand("SELECT_STATION", android.os.Bundle.EMPTY)
        mediaSession = MediaLibrarySession.Builder(
            this,
            sessionPlayer,
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(selectStationCommand)
                        .build()
                    return MediaSession.ConnectionResult.accept(
                        sessionCommands,
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    )
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle
                ): ListenableFuture<androidx.media3.session.SessionResult> {
                    if (customCommand.customAction == "SELECT_STATION") {
                        args.getString("stationId")?.let { rotationController.selectStation(it) }
                    }
                    return Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }

                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val rootExtras = android.os.Bundle().apply {
                        // List rows give title+subtitle two full lines of space (grid tiles show
                        // only one truncated title line and drop the subtitle entirely), so
                        // station names and country codes render legibly instead of cutting off.
                        putInt(
                            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                        )
                        putInt(
                            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                        )
                    }
                    val rootItem = MediaItem.Builder()
                        .setMediaId(ROOT_MEDIA_ID)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setTitle(getString(R.string.app_name))
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(
                        LibraryResult.ofItem(rootItem, LibraryParams.Builder().setExtras(rootExtras).build())
                    )
                }

                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    if (parentId != ROOT_MEDIA_ID) {
                        return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                    // Auto's own default empty-list text ("No items") isn't customizable —
                    // there's no API for it — so an empty shortlist returns one inert,
                    // non-playable placeholder item with an actually helpful message instead.
                    val children = if (currentStations.isEmpty()) {
                        listOf(emptyStationsPlaceholderMediaItem())
                    } else {
                        currentStations.map { it.toBrowsableMediaItem() }
                    }
                    android.util.Log.d("SmartRadioAuto", "onGetChildren called by ${browser.packageName}, returning ${children.size} stations: ${currentStations.map { it.name }}")
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                }

                override fun onGetItem(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    mediaId: String
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val station = currentStations.firstOrNull { it.id == mediaId }
                        ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    return Futures.immediateFuture(LibraryResult.ofItem(station.toBrowsableMediaItem(), null))
                }

                override fun onSearch(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<Void>> {
                    // Reuses the same Radio Browser directory the phone's Add Station dialog
                    // searches. The network call is async, so this kicks it off and reports
                    // back via notifySearchResultChanged() once done; onGetSearchResult() below
                    // then just re-reads the cached results for that query.
                    serviceScope.launch {
                        val results = directory.search(query).getOrDefault(emptyList())
                        searchResultsByQuery[query] = results
                        results.forEach { searchResultsById[it.stationUuid] = it }
                        session.notifySearchResultChanged(browser, query, results.size, params)
                    }
                    return Futures.immediateFuture(LibraryResult.ofVoid())
                }

                override fun onGetSearchResult(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val items = (searchResultsByQuery[query] ?: emptyList()).map { it.toSearchResultMediaItem() }
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                }

                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    // Auto (and any other browser) taps a browse-tree/search-result item through
                    // this standard path, not our custom SELECT_STATION command. Route it through
                    // the exact same rotationController.selectStation() call that command already
                    // uses, rather than letting the framework hand the placeholder item straight
                    // to the player and bypass lap-tracking/exhaustion-state resets.
                    val mediaId = mediaItems.firstOrNull()?.mediaId
                    val existingStation = currentStations.firstOrNull { it.id == mediaId }
                    if (existingStation != null) {
                        rotationController.selectStation(existingStation.id)
                        val currentItem = player.currentMediaItem ?: mediaItems.first()
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(listOf(currentItem), 0, androidx.media3.common.C.TIME_UNSET)
                        )
                    }

                    // Not shortlisted yet — check if it's a search result. Tapping one from
                    // Auto's search UI adds it to the persisted shortlist (StationRepository
                    // dedupes by stream URL, so tapping the same result twice is harmless) and
                    // starts playing it in one motion.
                    val discovered = mediaId
                        ?.takeIf { it.startsWith(SEARCH_MEDIA_ID_PREFIX) }
                        ?.let { searchResultsById[it.removePrefix(SEARCH_MEDIA_ID_PREFIX)] }
                    if (discovered != null) {
                        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                        serviceScope.launch {
                            val added = repository.addStation(
                                name = discovered.name,
                                streamUrl = discovered.streamUrl,
                                kind = discovered.guessedKind(),
                                favicon = discovered.favicon,
                                codec = discovered.codec,
                                bitrate = discovered.bitrate,
                                language = discovered.language,
                                country = discovered.country,
                                countryCode = discovered.countryCode,
                                state = discovered.state,
                                clickCount = discovered.clickCount
                            )
                            // Update our own snapshot directly rather than waiting for the
                            // repository.stations Flow to round-trip back to us — that delivery
                            // isn't guaranteed to land before the next line runs.
                            if (currentStations.none { it.id == added.id }) {
                                currentStations = currentStations + added
                            }
                            rotationController.updatePreferenceList(currentStations)
                            rotationController.selectStation(added.id)
                            val currentItem = player.currentMediaItem
                            if (currentItem != null) {
                                future.set(
                                    MediaSession.MediaItemsWithStartPosition(listOf(currentItem), 0, androidx.media3.common.C.TIME_UNSET)
                                )
                            } else {
                                future.setException(IllegalStateException("Failed to start playback for $mediaId"))
                            }
                        }
                        return future
                    }

                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val lastId = repository.lastPlayedStationId()
                    val station = currentStations.firstOrNull { it.id == lastId }
                        ?: return Futures.immediateFailedFuture(
                            UnsupportedOperationException("No station to resume")
                        )
                    rotationController.selectStation(station.id)
                    val currentItem = player.currentMediaItem
                        ?: return Futures.immediateFailedFuture(
                            IllegalStateException("Station did not start playing during resumption")
                        )
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(listOf(currentItem), 0, androidx.media3.common.C.TIME_UNSET)
                    )
                }
            }
        ).build()

        serviceScope.launch {
            detectionEngine.state.collect { state ->
                android.util.Log.d("SmartRadioClassifier", "listening state -> $state")
                rotationController.onListeningStateChanged(state)
            }
        }
        serviceScope.launch {
            repository.stations.collect { stations ->
                currentStations = stations
                if (!rotationController.hasShortlist() && stations.isNotEmpty()) {
                    rotationController.setShortlist(stations)
                } else {
                    rotationController.updatePreferenceList(stations)
                }
                // Tells any subscribed browser (Android Auto) to re-fetch onGetChildren for
                // "root" — without this, a browser that already loaded the (e.g. empty) list
                // once never learns it changed until it re-subscribes from scratch.
                android.util.Log.d("SmartRadioAuto", "stations changed (${stations.size}): ${stations.map { it.name }} -- notifying subscribers")
                mediaSession?.notifyChildrenChanged(ROOT_MEDIA_ID, stations.size, null)
            }
        }
    }

    private fun playStation(station: Station, reason: SwitchReason) {
        detectionEngine.reset()
        repository.saveLastPlayedStationId(station.id)
        val extras = android.os.Bundle()
        val skipMessage = (reason as? SwitchReason.AutoSkipped)?.let {
            extras.putString("autoSkipFrom", it.fromStationName)
            "Skipped ${it.fromStationName} — ads/talk detected"
        }
        val mediaItem = MediaItem.Builder()
            .setUri(station.streamUrl)
            .setMediaId(station.id)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(skipMessage)
                    .setArtworkUri(faviconUri(station.favicon))
                    .setExtras(extras)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        if (skipMessage != null) {
            // Media3 dropped the old PlaybackStateCompat.setErrorMessage() transient-toast
            // mechanism Android Auto used to render on its own — there's no direct
            // replacement "show a message" API, so this reuses the same MediaMetadata.artist
            // channel ICY titles already ride, showing the skip reason there briefly before
            // reverting. The phone UI has its own dedicated AutoSkipToast (driven by the
            // autoSkipFrom extra), so this mainly benefits Auto, which has no other channel —
            // it'll also briefly echo on the phone's "now playing" line, which is harmless.
            serviceScope.launch {
                delay(AUTO_SKIP_MESSAGE_DURATION_MS)
                val current = player.currentMediaItem
                if (current?.mediaId == station.id && current.mediaMetadata.artist?.toString() == skipMessage) {
                    player.replaceMediaItem(
                        player.currentMediaItemIndex,
                        current.buildUpon()
                            .setMediaMetadata(current.mediaMetadata.buildUpon().setArtist(null).build())
                            .build()
                    )
                }
            }
        }
    }

    /**
     * Called after the rotation controller has hopped through the whole
     * shortlist several times over without finding music. Pauses playback
     * outright (rather than muting in place) and flags the current MediaItem's
     * metadata extras so the UI can show a "no stations available" state —
     * extras ride along on MediaMetadata, which (unlike raw Metadata events)
     * is synced across the MediaController boundary.
     */
    private fun markExhausted() {
        player.pause()
        val currentItem = player.currentMediaItem ?: return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == androidx.media3.common.C.INDEX_UNSET) return
        val extras = android.os.Bundle(currentItem.mediaMetadata.extras).apply {
            putBoolean("noStationsAvailable", true)
        }
        player.replaceMediaItem(
            currentIndex,
            currentItem.buildUpon()
                .setMediaMetadata(currentItem.mediaMetadata.buildUpon().setExtras(extras).build())
                .build()
        )
    }

    /** Called by the UI layer (via a bound-service or session command) when the user reorders/edits the shortlist. */
    fun updateShortlist(stations: List<Station>) {
        rotationController.setShortlist(stations, startIndex = 0)
    }

    fun selectStation(stationId: String) = rotationController.selectStation(stationId)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player.release()
        classifier.close()
        classifierExecutor.shutdown()
        super.onDestroy()
    }
}

private fun faviconUri(favicon: String): Uri? = favicon.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

private fun emptyStationsPlaceholderMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(EMPTY_PLACEHOLDER_MEDIA_ID)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle("No stations yet")
                .setSubtitle("Add stations in the Skipadoodle app on your phone")
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build()
        )
        .build()
}

/**
 * Two stations can share a name across different countries (e.g. a generic "CLASS 95" or a
 * syndicated network's local affiliates). Android Auto's grid content style shows only one line
 * of text per tile and drops the subtitle entirely (unlike list style), so the country code is
 * embedded directly in the title here rather than relying on setSubtitle() alone.
 */
private fun titleWithCountryCode(name: String, countryCode: String): String =
    if (countryCode.isNotBlank()) "$name (${countryCode.uppercase()})" else name

private fun Station.toBrowsableMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(streamUrl)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(titleWithCountryCode(name, countryCode))
                .setSubtitle(locationSummary().takeIf { it.isNotBlank() })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setArtworkUri(faviconUri(favicon))
                .build()
        )
        .build()
}

private fun DiscoveredStation.toSearchResultMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(SEARCH_MEDIA_ID_PREFIX + stationUuid)
        .setUri(streamUrl)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(titleWithCountryCode(name, countryCode))
                .setSubtitle(locationSummary().takeIf { it.isNotBlank() })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setArtworkUri(faviconUri(favicon))
                .build()
        )
        .build()
}

private val ICY_TITLE_FIELD_REGEX = Regex("title=\"([^\"]*)\"")
private val ICY_ARTIST_FIELD_REGEX = Regex("artist=\"([^\"]*)\"")
private val ICY_EMBEDDED_TEXT_REGEX = Regex("text=\"([^\"]*)\"")

/**
 * Some broadcasters (iHeartRadio affiliates in particular) cram extra ad/analytics/track fields
 * into the ICY title instead of sending just the current track — and inconsistently, even from
 * the same station: KIIS FM has been observed sending both `title="HALO",artist="Beyonce",
 * url="song_..."` (real track metadata) and `LA's #1 Hit Music Station - text="102.7 KIIS FM"
 * song_spot="T" MediaBaseId="0...` (a station-ID/ad spot) at different times. Each embedded
 * field is matched independently (not assuming a fixed order or that all fields are present),
 * preferring title[+artist] over the older text= fallback. A normal "Artist - Song" title, which
 * never matches either pattern, is returned unchanged.
 */
private fun cleanIcyTitle(rawTitle: String): String {
    val title = ICY_TITLE_FIELD_REGEX.find(rawTitle)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    if (title != null) {
        val artist = ICY_ARTIST_FIELD_REGEX.find(rawTitle)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        return if (artist != null) "$artist - $title" else title
    }
    ICY_EMBEDDED_TEXT_REGEX.find(rawTitle)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
    return rawTitle
}
