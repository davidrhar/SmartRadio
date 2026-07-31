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
    val preferenceOrder: Int
)
