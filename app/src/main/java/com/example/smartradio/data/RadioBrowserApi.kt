package com.example.smartradio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class DiscoveredStation(
    @SerialName("stationuuid") val stationUuid: String = "",
    @SerialName("name") val name: String,
    @SerialName("url_resolved") val streamUrl: String,
    @SerialName("countrycode") val countryCode: String = "",
    @SerialName("country") val country: String = "",
    @SerialName("state") val state: String = "",
    @SerialName("language") val language: String = "",
    @SerialName("tags") val tags: String = "",
    @SerialName("votes") val votes: Int = 0,
    @SerialName("clickcount") val clickCount: Int = 0,
    @SerialName("codec") val codec: String = "",
    @SerialName("bitrate") val bitrate: Int = 0,
    @SerialName("favicon") val favicon: String = "",
    @SerialName("lastcheckok") val lastCheckOk: Int = 1
) {
    /**
     * Best-effort guess — Radio Browser doesn't cleanly separate "FM simulcast"/"AM simulcast"
     * vs. pure digital. The "am" tag is checked as an exact tag match rather than substring
     * (unlike "fm"): "am" is a common substring of unrelated words, so a loose contains() check
     * would misfire far more often than it does for "fm".
     */
    fun guessedKind(): StationKind = when {
        tags.contains("fm", ignoreCase = true) -> StationKind.FM_SIMULCAST
        tags.split(",").map { it.trim() }.any { it.equals("am", ignoreCase = true) } -> StationKind.AM_SIMULCAST
        else -> StationKind.DIGITAL
    }

    /** "MP3 128kbps" style summary, blank if the directory has no data for this station. */
    fun qualitySummary(): String {
        val parts = mutableListOf<String>()
        if (codec.isNotBlank()) parts += codec
        if (bitrate > 0) parts += "${bitrate}kbps"
        return parts.joinToString(" ")
    }

    /** First listed language, capitalized — Radio Browser stores these lowercase and comma-separated. */
    fun primaryLanguage(): String? =
        language.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }

    /** "Country" or "Country, State" when a state is present — most stations (e.g. Singapore) have none. */
    fun locationSummary(): String =
        if (state.isNotBlank()) "$country, $state" else country

    fun failedLastCheck(): Boolean = lastCheckOk == 0
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
            runAcrossMirrors { mirror ->
                val url = "$mirror/json/stations/search".toHttpUrl().newBuilder()
                    .addQueryParameter("name", query)
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("hidebroken", "true")
                    .addQueryParameter("order", "clickcount")
                    .addQueryParameter("reverse", "true")
                    .build()
                fetch(mirror, url)
            }
        }

    /**
     * Top stations by recent play count, optionally narrowed to a country.
     * Pass null/blank countryCode for a global list — used as the default
     * "Popular Stations" list before the user has typed anything.
     */
    suspend fun popular(countryCode: String? = null, limit: Int = 10): Result<List<DiscoveredStation>> =
        withContext(Dispatchers.IO) {
            runAcrossMirrors { mirror ->
                val builder = "$mirror/json/stations/search".toHttpUrl().newBuilder()
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("hidebroken", "true")
                    .addQueryParameter("order", "clickcount")
                    .addQueryParameter("reverse", "true")
                if (!countryCode.isNullOrBlank()) {
                    builder.addQueryParameter("countrycode", countryCode)
                }
                fetch(mirror, builder.build())
            }
        }

    private inline fun runAcrossMirrors(
        block: (mirror: String) -> List<DiscoveredStation>
    ): Result<List<DiscoveredStation>> {
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            val result = runCatching { block(mirror) }
            result.onSuccess { return Result.success(it) }
            result.onFailure { lastError = it }
        }
        return Result.failure(lastError ?: IOException("All Radio Browser mirrors failed"))
    }

    private fun fetch(mirror: String, url: HttpUrl): List<DiscoveredStation> {
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
