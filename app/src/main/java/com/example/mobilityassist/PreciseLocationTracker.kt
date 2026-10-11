package com.example.mobilityassist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Foreground-only location updates. Every consumer still checks fix age and accuracy. */
class PreciseLocationTracker(
    context: Context,
    private val onFix: (Location) -> Unit
) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val appContext = context.applicationContext
    private var listening = false
    var latest: Location? = null
        private set

    fun start() {
        if (listening || ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        listening = true
        try {
            var registered = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    manager.requestLocationUpdates(provider, 1_000L, 0f, this)
                    registered = true
                } catch (_: IllegalArgumentException) { /* Provider not present on this phone. */ }
            }
            if (!registered) { listening = false; return }
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider ->
                    try { manager.getLastKnownLocation(provider) }
                    catch (_: IllegalArgumentException) { null }
                }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let(::onLocationChanged)
        } catch (_: SecurityException) {
            stop()
        } catch (_: IllegalArgumentException) {
            stop()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy()) return
        val old = latest
        if (old != null) {
            if (location.elapsedRealtimeNanos < old.elapsedRealtimeNanos) return
            val oldAgeMs = (SystemClock.elapsedRealtimeNanos() - old.elapsedRealtimeNanos) / 1_000_000L
            if (oldAgeMs in 0..5_000L && location.accuracy > old.accuracy + 10f) return
        }
        latest = location
        onFix(location)
    }

    fun stop() {
        if (listening) manager.removeUpdates(this)
        listening = false
    }

    companion object {
        fun usable(location: Location?, maxAccuracyM: Float = 30f): Boolean {
            if (location == null || !location.hasAccuracy() || location.accuracy > maxAccuracyM) return false
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            return ageMs in 0..15_000L && location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
        }
    }
}
