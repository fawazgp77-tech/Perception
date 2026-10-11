package com.example.mobilityassist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/** Foreground fixes with a framework fallback on phones without Google Play services. */
class PreciseLocationTracker(
    context: Context,
    private val onFix: (Location) -> Unit
) : LocationListener {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(appContext) }
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocationChanged)
        }
    }
    private var listening = false
    private var usingFused = false
    private var frameworkStarted = false
    private var oneShot: CancellationTokenSource? = null
    private var frameworkSignal: CancellationSignal? = null
    var latest: Location? = null
        private set

    fun isEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    fun start() {
        if (listening || !hasLocationPermission() || !isEnabled()) return
        listening = true
        val playServices = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
        if (playServices) {
            try {
                usingFused = true
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                    .setMinUpdateIntervalMillis(500L).build()
                fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                    .addOnFailureListener { if (listening) startFramework() }
                refresh()
                return
            } catch (_: SecurityException) { /* Permission changed during request. */ }
            catch (_: Exception) { /* Try Android's provider below. */ }
        }
        startFramework()
    }

    fun refresh() {
        if (!hasLocationPermission() || !isEnabled()) return
        if (!listening) { start(); return }
        if (usingFused) {
            oneShot?.cancel()
            val request = CancellationTokenSource()
            oneShot = request
            try {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, request.token)
                    .addOnSuccessListener { if (listening && it != null) onLocationChanged(it) }
                    .addOnFailureListener { if (listening) startFramework() }
            } catch (_: SecurityException) { startFramework() }
            catch (_: Exception) { startFramework() }
        } else refreshFramework()
    }

    private fun startFramework() {
        if (!listening) return
        if (frameworkStarted) return
        if (usingFused) fused.removeLocationUpdates(fusedCallback)
        usingFused = false
        frameworkStarted = true
        var registered = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 1_000L, 0f, this, Looper.getMainLooper())
                    registered = true
                }
            } catch (_: SecurityException) { /* Approximate-only cannot use GPS. */ }
            catch (_: IllegalArgumentException) { /* Provider absent. */ }
        }
        if (!registered) { listening = false; frameworkStarted = false }
        else refreshFramework()
    }

    private fun refreshFramework() {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                manager.getLastKnownLocation(provider)?.let(::onLocationChanged)
                if (Build.VERSION.SDK_INT >= 30 && manager.isProviderEnabled(provider)) {
                    frameworkSignal?.cancel()
                    frameworkSignal = CancellationSignal()
                    manager.getCurrentLocation(provider, frameworkSignal, appContext.mainExecutor) {
                        if (listening && it != null) onLocationChanged(it)
                    }
                    break
                }
            } catch (_: SecurityException) { /* Continue with permitted provider. */ }
            catch (_: IllegalArgumentException) { /* Provider absent. */ }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!listening || !location.hasAccuracy()) return
        val old = latest
        if (old != null) {
            if (location.elapsedRealtimeNanos < old.elapsedRealtimeNanos) return
            val oldAgeMs = (SystemClock.elapsedRealtimeNanos() - old.elapsedRealtimeNanos) / 1_000_000L
            if (oldAgeMs in 0..5_000L && location.accuracy > old.accuracy + 10f) return
        }
        latest = location
        onFix(location)
    }

    override fun onProviderEnabled(provider: String) = refresh()

    fun stop() {
        listening = false
        oneShot?.cancel(); oneShot = null
        frameworkSignal?.cancel(); frameworkSignal = null
        if (usingFused) fused.removeLocationUpdates(fusedCallback)
        manager.removeUpdates(this)
        usingFused = false
        frameworkStarted = false
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        fun usable(location: Location?, maxAccuracyM: Float = 80f, maxAgeMs: Long = 30_000L): Boolean {
            if (location == null || !location.hasAccuracy() || location.accuracy > maxAccuracyM) return false
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            return ageMs in 0..maxAgeMs && location.latitude in -90.0..90.0 &&
                location.longitude in -180.0..180.0
        }
    }
}
