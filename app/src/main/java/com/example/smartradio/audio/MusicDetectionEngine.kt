package com.example.smartradio.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ListeningState { MUSIC, NON_MUSIC, UNKNOWN }

/**
 * Each classified window (~1s) is noisy — a DJ talking over a music bed or
 * jingle, a song's quiet intro, or a brief instrumental sting can flip the
 * raw verdict from one window to the next. A strict "N in a row" rule is too
 * fragile for that: a single flipped window resets the count to zero, so a
 * long talk segment with music bedded underneath could go undetected forever.
 *
 * Instead this looks at a rolling window of the last [windowSize] verdicts
 * and requires a clear majority before declaring a state change. In the
 * ambiguous middle ground, it holds the previous state rather than flapping.
 */
class MusicDetectionEngine(
    private val windowSize: Int = 10,       // ~10s of audio considered at a time
    private val nonMusicThreshold: Int = 7, // >=7/10 non-music windows -> talk/ads
    private val musicThreshold: Int = 6     // >=6/10 music windows -> music
) {
    private val _state = MutableStateFlow(ListeningState.UNKNOWN)
    val state: StateFlow<ListeningState> = _state

    private val recentVerdicts = ArrayDeque<Boolean>() // true = isMusic

    fun onVerdict(verdict: YamnetClassifier.Verdict) {
        recentVerdicts.addLast(verdict.isMusic)
        if (recentVerdicts.size > windowSize) recentVerdicts.removeFirst()
        if (recentVerdicts.size < windowSize) return // not enough data yet

        val musicCount = recentVerdicts.count { it }
        val nonMusicCount = recentVerdicts.size - musicCount

        when {
            nonMusicCount >= nonMusicThreshold -> _state.value = ListeningState.NON_MUSIC
            musicCount >= musicThreshold -> _state.value = ListeningState.MUSIC
            // else: ambiguous mix — hold whatever state we were already in
        }
    }

    fun reset() {
        recentVerdicts.clear()
        _state.value = ListeningState.UNKNOWN
    }
}
