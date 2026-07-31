package com.example.smartradio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "smart_radio_stations")
private val STATIONS_KEY = stringPreferencesKey("stations_json")

private val json = Json { ignoreUnknownKeys = true }

/**
 * Single source of truth for the shortlisted stations and the order the
 * auto-rotation logic should try them in. Order is simply list index,
 * persisted as JSON so drag-to-reorder in the UI is durable across restarts.
 */
class StationRepository(private val context: Context) {

    val stations: Flow<List<Station>> = context.dataStore.data.map { prefs ->
        val raw = prefs[STATIONS_KEY] ?: return@map defaultStations()
        runCatching { json.decodeFromString<List<Station>>(raw) }
            .getOrDefault(defaultStations())
            .sortedBy { it.preferenceOrder }
    }

    suspend fun saveAll(stations: List<Station>) {
        val reindexed = stations.mapIndexed { index, s -> s.copy(preferenceOrder = index) }
        context.dataStore.edit { prefs ->
            prefs[STATIONS_KEY] = json.encodeToString(reindexed)
        }
    }

    suspend fun addStation(name: String, streamUrl: String, kind: StationKind) {
        val current = stationsSnapshot()
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
        saveAll(stationsSnapshot().filterNot { it.id == id })
    }

    suspend fun reorder(newOrderIds: List<String>) {
        val byId = stationsSnapshot().associateBy { it.id }
        val reordered = newOrderIds.mapNotNull { byId[it] }
        saveAll(reordered)
    }

    private suspend fun stationsSnapshot(): List<Station> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[STATIONS_KEY] ?: return defaultStations()
        return runCatching { json.decodeFromString<List<Station>>(raw) }.getOrDefault(defaultStations())
    }

    // Placeholder starter list — replace with real stream URLs for your market.
    // FM_SIMULCAST entries should point at the station's own internet simulcast
    // stream (most broadcasters publish one); DIGITAL entries are any internet
    // or DAB+ station's stream URL.
    private fun defaultStations(): List<Station> = emptyList()
}
