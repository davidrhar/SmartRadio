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
 * Radio Browser runs several independent mirrors with no single stable
 * hostname; the "correct" approach is resolving `all.api.radio-browser.info`
 * via DNS SRV records, which isn't practical to do simply from a mobile app.
 * Instead, this tries a short list of known mirrors in order and falls back
 * to the next one on any failure — one slow/unreachable mirror (including
 * due to VPN routing) no longer fails the whole search.
 */
class RadioBrowserApi {

    private val mirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int = 25): Result<List<DiscoveredStation>> =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            for (mirror in mirrors) {
                val result = runCatching { searchMirror(mirror, query, limit) }
                result.onSuccess { return@withContext Result.success(it) }
                result.onFailure { lastError = it }
            }
            Result.failure(lastError ?: IOException("All Radio Browser mirrors failed"))
        }

    private fun searchMirror(mirror: String, query: String, limit: Int): List<DiscoveredStation> {
        val url = "$mirror/json/stations/search".toHttpUrl()
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
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $mirror")
            val body = response.body?.string() ?: "[]"
            return json.decodeFromString<List<DiscoveredStation>>(body)
                .filter { it.streamUrl.isNotBlank() }
        }
    }
}
