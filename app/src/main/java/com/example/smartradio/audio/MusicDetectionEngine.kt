package com.example.smartradio.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ListeningState { MUSIC, NON_MUSIC, UNKNOWN }

/**
 * Real radio talk segments are very often layered over a continuous music
 * bed (a DJ chatting over an instrumental, a station ident with music
 * underneath), so per-second "is this music or speech" can stay dominated by
 * loud background music even while someone is clearly talking — the speech
 * signal shows up as intermittent, moderate spikes rather than a clean,
 * sustained majority. A binary per-window vote discards that accumulating
 * pattern; this instead tracks a smoothed (EMA) running average of the raw
 * speech score and reacts once that average crosses a threshold, which
 * catches sustained-but-intermittent speech that a strict vote misses.
 * Hysteresis (different enter/exit thresholds) prevents flapping right at
 * the boundary.
 */
class MusicDetectionEngine(
    private val emaAlpha: Double = 0.15,
    private val nonMusicEnterThreshold: Double = 0.12,
    private val musicEnterThreshold: Double = 0.06,
    private val minSamplesBeforeDeciding: Int = 5
) {
    private val _state = MutableStateFlow(ListeningState.UNKNOWN)
    val state: StateFlow<ListeningState> = _state

    private var emaSpeechScore = 0.0
    private var samplesSeen = 0

    fun onVerdict(verdict: YamnetClassifier.Verdict) {
        emaSpeechScore = emaAlpha * verdict.speechScore + (1 - emaAlpha) * emaSpeechScore
        samplesSeen++
        android.util.Log.d("SmartRadioClassifier", "emaSpeech=$emaSpeechScore")
        if (samplesSeen < minSamplesBeforeDeciding) return

        when {
            emaSpeechScore >= nonMusicEnterThreshold -> _state.value = ListeningState.NON_MUSIC
            emaSpeechScore <= musicEnterThreshold -> _state.value = ListeningState.MUSIC
            // else: in the hysteresis band between thresholds — hold whatever state we were already in
        }
    }

    fun reset() {
        emaSpeechScore = 0.0
        samplesSeen = 0
        _state.value = ListeningState.UNKNOWN
    }
}
