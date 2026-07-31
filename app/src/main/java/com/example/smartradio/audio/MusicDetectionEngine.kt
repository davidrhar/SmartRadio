package com.example.smartradio.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ListeningState { MUSIC, NON_MUSIC, UNKNOWN }

/**
 * Each classified window (~1s) is noisy — a DJ pausing mid-sentence, a song's
 * quiet intro, or a brief jingle can flip the raw verdict. This engine
 * requires a sustained run of same-verdict windows before declaring a state
 * change, so a station only gets treated as "talking/ads" after several
 * consecutive seconds of non-music, not a single blip.
 */
class MusicDetectionEngine(
    private val sustainedWindowsToSwitch: Int = 8 // ~8 seconds of continuous non-music
) {
    private val _state = MutableStateFlow(ListeningState.UNKNOWN)
    val state: StateFlow<ListeningState> = _state

    private var consecutiveNonMusic = 0
    private var consecutiveMusic = 0

    fun onVerdict(verdict: YamnetClassifier.Verdict) {
        if (verdict.isMusic) {
            consecutiveMusic++
            consecutiveNonMusic = 0
            if (consecutiveMusic >= 2) _state.value = ListeningState.MUSIC
        } else {
            consecutiveNonMusic++
            consecutiveMusic = 0
            if (consecutiveNonMusic >= sustainedWindowsToSwitch) _state.value = ListeningState.NON_MUSIC
        }
    }

    fun reset() {
        consecutiveMusic = 0
        consecutiveNonMusic = 0
        _state.value = ListeningState.UNKNOWN
    }
}
