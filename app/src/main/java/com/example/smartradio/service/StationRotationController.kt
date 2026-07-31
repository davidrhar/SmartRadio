package com.example.smartradio.service

import com.example.smartradio.audio.ListeningState
import com.example.smartradio.data.Station

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
    private val onSwitchTo: (Station) -> Unit,
    private val onMuteChanged: (Boolean) -> Unit
) {
    private var shortlist: List<Station> = emptyList()
    private var currentIndex: Int = 0
    private var stationsTriedThisCycle: Int = 0
    private var muted: Boolean = false

    fun setShortlist(stations: List<Station>, startIndex: Int = 0) {
        shortlist = stations
        currentIndex = startIndex.coerceIn(0, (stations.size - 1).coerceAtLeast(0))
        stationsTriedThisCycle = 0
        setMuted(false)
        if (shortlist.isNotEmpty()) onSwitchTo(shortlist[currentIndex])
    }

    fun currentStation(): Station? = shortlist.getOrNull(currentIndex)

    fun hasShortlist(): Boolean = shortlist.isNotEmpty()

    /**
     * Applies an updated preference list (e.g. after drag-to-reorder or
     * add/remove) without interrupting current playback, as long as the
     * currently playing station is still in the list.
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
            setMuted(false)
            onSwitchTo(stations[0])
        }
    }

    /** Call with every debounced state change from MusicDetectionEngine for the active station. */
    fun onListeningStateChanged(newState: ListeningState) {
        if (shortlist.isEmpty()) return

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
        currentIndex = index
        stationsTriedThisCycle = 0
        setMuted(false)
        onSwitchTo(shortlist[currentIndex])
    }

    private fun advanceToNextStation() {
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
        onSwitchTo(shortlist[currentIndex])
    }

    private fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        onMuteChanged(value)
    }
}
