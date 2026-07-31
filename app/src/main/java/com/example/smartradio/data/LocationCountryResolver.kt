package com.example.smartradio.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Resolves a rough ISO country code from the device's last known coarse
 * location, for the opt-in "Popular Near You" feature. Deliberately uses only
 * platform APIs (no Google Play services location dependency) — coarse
 * accuracy is all we need since Radio Browser only filters by country/state
 * anyway, never anything more granular.
 */
object LocationCountryResolver {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns null on any failure (no permission, no cached location, geocoder unavailable, etc). */
    suspend fun resolveCountryCode(context: Context): String? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null

        runCatching {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return@withContext null

            @Suppress("MissingPermission")
            val location = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
                .firstNotNullOfOrNull { provider -> locationManager.getLastKnownLocation(provider) }
                ?: return@withContext null

            // Geocoder.getFromLocation's synchronous overload is deprecated since API 33
            // in favor of a listener-based version, but remains functional on every
            // supported API level here — kept for simplicity rather than branching
            // implementations by SDK version for a single best-effort lookup.
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)

            addresses?.firstOrNull()?.countryCode
        }.getOrNull()
    }
}
