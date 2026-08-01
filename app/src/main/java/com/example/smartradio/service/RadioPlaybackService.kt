package com.example.smartradio.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.smartradio.audio.MusicDetectionEngine
import com.example.smartradio.audio.PcmTapSink
import com.example.smartradio.audio.YamnetClassifier
import com.example.smartradio.data.Station
import com.example.smartradio.data.StationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@UnstableApi
class RadioPlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    private lateinit var classifier: YamnetClassifier
    private val detectionEngine = MusicDetectionEngine()
    private val classifierExecutor = Executors.newSingleThreadExecutor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rotationController: StationRotationController
    private lateinit var repository: StationRepository

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
                    val title = entry.title?.takeIf { it.isNotBlank() }
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
        val selectStationCommand = androidx.media3.session.SessionCommand("SELECT_STATION", android.os.Bundle.EMPTY)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
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
                ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    if (customCommand.customAction == "SELECT_STATION") {
                        args.getString("stationId")?.let { rotationController.selectStation(it) }
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
            })
            .build()

        rotationController = StationRotationController(
            onSwitchTo = { station, reason -> playStation(station, reason) },
            onExhausted = { markExhausted() }
        )

        serviceScope.launch {
            detectionEngine.state.collect { state ->
                android.util.Log.d("SmartRadioClassifier", "listening state -> $state")
                rotationController.onListeningStateChanged(state)
            }
        }
        serviceScope.launch {
            repository.stations.collect { stations ->
                if (!rotationController.hasShortlist() && stations.isNotEmpty()) {
                    rotationController.setShortlist(stations)
                } else {
                    rotationController.updatePreferenceList(stations)
                }
            }
        }
    }

    private fun playStation(station: Station, reason: SwitchReason) {
        detectionEngine.reset()
        val extras = android.os.Bundle()
        if (reason is SwitchReason.AutoSkipped) {
            extras.putString("autoSkipFrom", reason.fromStationName)
        }
        val mediaItem = MediaItem.Builder()
            .setUri(station.streamUrl)
            .setMediaId(station.id)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setExtras(extras)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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
