package com.example.smartradio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class DiscoveredStation(
    @SerialName("name") val name: String,
    @SerialName("url_resolved") val streamUrl: String,
    @SerialName("countrycode") val countryCode: String = "",
    @SerialName("tags") val tags: String = "",
    @SerialName("votes") val votes: Int = 0
) {
    /** Best-effort guess — Radio Browser doesn't cleanly separate "FM simulcast" vs. pure digital. */
    fun guessedKind(): StationKind =
        if (tags.contains("fm", ignoreCase = true)) StationKind.FM_SIMULCAST else StationKind.DIGITAL
}

/**
 * Talks to the community-run Radio Browser directory (https://www.radio-browser.info) —
 * free, no API key, tens of thousands of FM-simulcast and internet-only stations.
 *
 * Note: production apps are meant to resolve a working mirror via the
 * `all.api.radio-browser.info` DNS SRV record and fail over between mirrors.
 * This uses a single fixed mirror (de1) for simplicity — swap in real
 * mirror-resolution logic if that mirror ever becomes unreliable.
 */
class RadioBrowserApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int = 25): Result<List<DiscoveredStation>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://de1.api.radio-browser.info/json/stations/search".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("name", query)
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("hidebroken", "true")
                    .addQueryParameter("order", "votes")
                    .addQueryParameter("reverse", "true")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    // Radio Browser asks clients to identify themselves.
                    .header("User-Agent", "SmartRadioApp/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body?.string() ?: "[]"
                    json.decodeFromString<List<DiscoveredStation>>(body)
                        .filter { it.streamUrl.isNotBlank() }
                }
            }
        }
}
