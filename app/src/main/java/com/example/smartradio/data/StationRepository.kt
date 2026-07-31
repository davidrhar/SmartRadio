package com.example.smartradio.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "smart_radio_stations"
private const val STATIONS_KEY = "stations_json"

private val json = Json { ignoreUnknownKeys = true }

/**
 * Single source of truth for the shortlisted stations and the order the
 * auto-rotation logic should try them in. Order is simply list index,
 * persisted as JSON so drag-to-reorder in the UI is durable across restarts.
 *
 * Backed by plain SharedPreferences rather than Jetpack DataStore: DataStore's
 * native counter library (libdatastore_shared_counter.so) has an inconsistent
 * 16 KB page-alignment history across versions, and SharedPreferences has no
 * native library at all, sidestepping the problem entirely. The public API
 * here (a reactive Flow<List<Station>>) is unchanged, so nothing else in the
 * app needed to change.
 */
class StationRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val stations: Flow<List<Station>> = callbackFlow {
        trySend(readStations())

        // SharedPreferences.OnSharedPreferenceChangeListener is held only via
        // a WeakReference internally by the framework — keep a strong local
        // reference (captured by the awaitClose closure below) so it isn't
        // garbage-collected while this flow is being collected.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == STATIONS_KEY) trySend(readStations())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun saveAll(stations: List<Station>) {
        val reindexed = stations.mapIndexed { index, s -> s.copy(preferenceOrder = index) }
        prefs.edit().putString(STATIONS_KEY, json.encodeToString(reindexed)).apply()
    }

    suspend fun addStation(name: String, streamUrl: String, kind: StationKind) {
        val current = readStations()
        val newStation = Station(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            streamUrl = streamUrl,
            kind = kind,
            preferenceOrder = current.size
        )
        saveAll(current + newStation)
    }

    suspend fun removeStation(id: String) {
        saveAll(readStations().filterNot { it.id == id })
    }

    suspend fun reorder(newOrderIds: List<String>) {
        val byId = readStations().associateBy { it.id }
        val reordered = newOrderIds.mapNotNull { byId[it] }
        saveAll(reordered)
    }

    private fun readStations(): List<Station> {
        val raw = prefs.getString(STATIONS_KEY, null) ?: return defaultStations()
        return runCatching { json.decodeFromString<List<Station>>(raw) }
            .getOrDefault(defaultStations())
            .sortedBy { it.preferenceOrder }
    }

    // Placeholder starter list — replace with real stream URLs for your market.
    // FM_SIMULCAST entries should point at the station's own internet simulcast
    // stream (most broadcasters publish one); DIGITAL entries are any internet
    // or DAB+ station's stream URL.
    private fun defaultStations(): List<Station> = emptyList()
}
