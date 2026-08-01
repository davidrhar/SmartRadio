package com.example.smartradio.service

import com.example.smartradio.audio.ListeningState
import com.example.smartradio.data.Station

/** Why the rotation controller is switching to a given station. */
sealed class SwitchReason {
    /** The user tapped a station directly. */
    object UserSelected : SwitchReason()
    /** The preference list changed (e.g. the playing station was removed). */
    object ListChanged : SwitchReason()
    /** Sustained non-music triggered an automatic skip away from [fromStationName]. */
    data class AutoSkipped(val fromStationName: String) : SwitchReason()
}

/**
 * Implements the requested behavior:
 *  - Stations are tried in preference order.
 *  - When the current station sustains non-music (ads/talk) per
 *    MusicDetectionEngine, move to the next station in the list.
 *  - If a full lap of the shortlist happens without landing on music,
 *    stop hopping and mute the current station instead — it keeps playing
 *    (and keeps being classified) silently until music resumes there.
 */
class StationRotationController(
    private val onSwitchTo: (Station, SwitchReason) -> Unit,
    private val onMuteChanged: (Boolean) -> Unit
) {
    private var shortlist: List<Station> = emptyList()
    private var currentIndex: Int = 0
    private var stationsTriedThisCycle: Int = 0
    private var muted: Boolean = false

    /** True once the user has picked a station; nothing plays before that. */
    private var playbackStarted: Boolean = false

    fun setShortlist(stations: List<Station>, startIndex: Int = 0) {
        shortlist = stations
        currentIndex = startIndex.coerceIn(0, (stations.size - 1).coerceAtLeast(0))
        stationsTriedThisCycle = 0
        // Deliberately no onSwitchTo here: playback begins when the user
        // taps a station, not when the app opens or the list first loads.
    }

    fun currentStation(): Station? = shortlist.getOrNull(currentIndex)

    fun hasShortlist(): Boolean = shortlist.isNotEmpty()

    /**
     * Applies an updated preference list (e.g. after reorder or add/remove)
     * without interrupting current playback, as long as the currently playing
     * station is still in the list. Only jumps to the top station if the one
     * the user was actively listening to got removed.
     */
    fun updatePreferenceList(stations: List<Station>) {
        val currentId = currentStation()?.id
        shortlist = stations
        stationsTriedThisCycle = 0
        val stillPresentIndex = currentId?.let { id -> stations.indexOfFirst { it.id == id } } ?: -1
        if (stillPresentIndex >= 0) {
            currentIndex = stillPresentIndex
        } else if (stations.isNotEmpty()) {
            currentIndex = 0
            if (playbackStarted) {
                // The station being listened to was removed — keep the radio
                // going with the top preference instead of going silent.
                setMuted(false)
                onSwitchTo(stations[0], SwitchReason.ListChanged)
            }
        }
    }

    /** Call with every debounced state change from MusicDetectionEngine for the active station. */
    fun onListeningStateChanged(newState: ListeningState) {
        if (shortlist.isEmpty() || !playbackStarted) return

        when (newState) {
            ListeningState.MUSIC -> {
                // Found music — reset the lap counter and, if we were muted
                // waiting on this very station, unmute.
                stationsTriedThisCycle = 0
                if (muted) setMuted(false)
            }
            ListeningState.NON_MUSIC -> {
                if (muted) return // already parked muted on this station; nothing to hop to differently
                advanceToNextStation()
            }
            ListeningState.UNKNOWN -> Unit
        }
    }

    /** User manually jumps to a specific station (resets cycle tracking). */
    fun selectStation(stationId: String) {
        val index = shortlist.indexOfFirst { it.id == stationId }
        if (index == -1) return
        playbackStarted = true
        currentIndex = index
        stationsTriedThisCycle = 0
        setMuted(false)
        onSwitchTo(shortlist[currentIndex], SwitchReason.UserSelected)
    }

    private fun advanceToNextStation() {
        val previousStation = shortlist[currentIndex]
        stationsTriedThisCycle++
        if (stationsTriedThisCycle >= shortlist.size) {
            // Completed a full lap and every station was non-music. Stop
            // hopping and mute in place; classification keeps running on the
            // current stream so we notice as soon as it turns to music.
            setMuted(true)
            stationsTriedThisCycle = 0
            return
        }
        currentIndex = (currentIndex + 1) % shortlist.size
        onSwitchTo(shortlist[currentIndex], SwitchReason.AutoSkipped(previousStation.name))
    }

    private fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        onMuteChanged(value)
    }
}
