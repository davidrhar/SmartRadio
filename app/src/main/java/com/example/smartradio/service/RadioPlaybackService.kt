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
                val verdict = classifier.classify(window)
                serviceScope.launch { detectionEngine.onVerdict(verdict) }
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
            onSwitchTo = { station -> playStation(station) },
            onMuteChanged = { muted -> player.volume = if (muted) 0f else 1f }
        )

        serviceScope.launch {
            detectionEngine.state.collect { state ->
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

    private fun playStation(station: Station) {
        detectionEngine.reset()
        val mediaItem = MediaItem.Builder()
            .setUri(station.streamUrl)
            .setMediaId(station.id)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(station.name)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
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
