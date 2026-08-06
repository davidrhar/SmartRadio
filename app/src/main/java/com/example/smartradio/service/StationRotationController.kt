package com.example.smartradio.service

import com.example.smartradio.audio.ListeningState
import com.example.smartradio.data.Station

/** Why the rotation controller is switching to a given station. */
sealed class SwitchReason {
    /** The user tapped a station directly. */
    object UserSelected : SwitchReason()
    /** The preference list changed (e.g. the playing station was removed). */
    object ListChanged : SwitchReason()
    /** Sustained non-music, or a playback error, triggered an automatic skip away from [fromStationName]. */
    data class AutoSkipped(val fromStationName: String) : SwitchReason()
}

/** Number of full laps through the shortlist to attempt before giving up. */
private const val MAX_LAPS = 3

/**
 * Implements the requested behavior:
 *  - Stations are tried in preference order.
 *  - When the current station sustains non-music (ads/talk), or fails to play
 *    at all (playback error), move to the next station in the list.
 *  - After [MAX_LAPS] full laps of the shortlist without landing on music,
 *    stop hopping and pause entirely, surfacing an "exhausted" state so the
 *    UI can show a "no stations available" screen.
 */
class StationRotationController(
    private val onSwitchTo: (Station, SwitchReason) -> Unit,
    private val onExhausted: () -> Unit
) {
    private var shortlist: List<Station> = emptyList()
    private var currentIndex: Int = 0
    private var stationsTriedThisCycle: Int = 0
    private var lapsCompleted: Int = 0
    private var exhausted: Boolean = false

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
                lapsCompleted = 0
                exhausted = false
                onSwitchTo(stations[0], SwitchReason.ListChanged)
            }
        }
    }

    /** Call with every debounced state change from MusicDetectionEngine for the active station. */
    fun onListeningStateChanged(newState: ListeningState) {
        if (shortlist.isEmpty() || !playbackStarted || exhausted) return

        when (newState) {
            ListeningState.MUSIC -> {
                // Found music — reset the lap counters.
                stationsTriedThisCycle = 0
                lapsCompleted = 0
            }
            ListeningState.NON_MUSIC -> advanceToNextStation()
            ListeningState.UNKNOWN -> Unit
        }
    }

    /** Call when the player reports a playback error (e.g. unreachable stream) — counts as a skip reason, like non-music. */
    fun onPlaybackError() {
        if (shortlist.isEmpty() || !playbackStarted || exhausted) return
        advanceToNextStation()
    }

    /** User manually jumps to a specific station (resets cycle tracking and clears any exhausted state). */
    fun selectStation(stationId: String) {
        val index = shortlist.indexOfFirst { it.id == stationId }
        if (index == -1) return
        playbackStarted = true
        currentIndex = index
        stationsTriedThisCycle = 0
        lapsCompleted = 0
        exhausted = false
        onSwitchTo(shortlist[currentIndex], SwitchReason.UserSelected)
    }

    /**
     * User-driven skip to the next/previous station in the shortlist (e.g. Android Auto's
     * standard transport buttons). Deliberately separate from [advanceToNextStation], which is
     * coupled to the auto-skip/exhaustion state machine — a deliberate skip should always work
     * and always reset that tracking, exactly like [selectStation].
     */
    fun skipToNext() {
        if (shortlist.isEmpty()) return
        playbackStarted = true
        currentIndex = (currentIndex + 1) % shortlist.size
        stationsTriedThisCycle = 0
        lapsCompleted = 0
        exhausted = false
        onSwitchTo(shortlist[currentIndex], SwitchReason.UserSelected)
    }

    fun skipToPrevious() {
        if (shortlist.isEmpty()) return
        playbackStarted = true
        currentIndex = (currentIndex - 1 + shortlist.size) % shortlist.size
        stationsTriedThisCycle = 0
        lapsCompleted = 0
        exhausted = false
        onSwitchTo(shortlist[currentIndex], SwitchReason.UserSelected)
    }

    private fun advanceToNextStation() {
        val previousStation = shortlist[currentIndex]
        stationsTriedThisCycle++
        if (stationsTriedThisCycle >= shortlist.size) {
            stationsTriedThisCycle = 0
            lapsCompleted++
            if (lapsCompleted >= MAX_LAPS) {
                // Exhausted every station, MAX_LAPS times over — stop hopping
                // and pause entirely rather than keep burning bandwidth/battery.
                exhausted = true
                onExhausted()
                return
            }
        }
        currentIndex = (currentIndex + 1) % shortlist.size
        onSwitchTo(shortlist[currentIndex], SwitchReason.AutoSkipped(previousStation.name))
    }
}
