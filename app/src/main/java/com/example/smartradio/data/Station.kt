package com.example.smartradio.data

import kotlinx.serialization.Serializable

enum class StationKind {
    /** An FM station's internet simulcast stream (phones have no FM tuner chip). */
    FM_SIMULCAST,
    /** An internet-only or DAB+ digital station, accessed as a stream URL. */
    DIGITAL
}

@Serializable
data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val kind: StationKind,
    /** Lower value = higher preference. Assigned/maintained by StationRepository. */
    val preferenceOrder: Int,
    // Metadata captured at add-time from the directory, when the station came
    // from a search result rather than manual entry. Blank/zero otherwise —
    // all default so existing saved stations (from before these fields
    // existed) still decode without needing a migration.
    val favicon: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val language: String = "",
    val country: String = "",
    val state: String = "",
    val clickCount: Int = 0
) {
    /** "MP3 128kbps" style summary, blank if no data was captured. */
    fun qualitySummary(): String {
        val parts = mutableListOf<String>()
        if (codec.isNotBlank()) parts += codec
        if (bitrate > 0) parts += "${bitrate}kbps"
        return parts.joinToString(" ")
    }

    fun primaryLanguage(): String? =
        language.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }

    fun locationSummary(): String =
        if (state.isNotBlank()) "$country, $state" else country
}
