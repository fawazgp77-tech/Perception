package com.example.mobilityassist

import android.app.Activity
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import com.mappls.sdk.maps.MapView
import com.mappls.sdk.maps.Mappls
import com.mappls.sdk.maps.MapplsMap
import com.mappls.sdk.maps.OnMapReadyCallback
import com.mappls.sdk.maps.annotations.Marker
import com.mappls.sdk.maps.annotations.MarkerOptions
import com.mappls.sdk.maps.annotations.PolylineOptions
import com.mappls.sdk.maps.camera.CameraUpdateFactory
import com.mappls.sdk.maps.geometry.LatLng

/** One MapView is reparented between mini and half-screen hosts; attribution remains visible. */
class InAppWalkingMap(
    activity: Activity,
    private val miniHost: FrameLayout,
    private val expandedHost: FrameLayout,
    private val onError: (String) -> Unit
) {
    private val view: MapView
    private var map: MapplsMap? = null
    private var currentMarker: Marker? = null
    private var route: WalkingRoute? = null
    private var latest: Location? = null
    private var lastPanMs = 0L
    private var started = false
    private var resumed = false
    private var destroyed = false

    init {
        Mappls.getInstance(activity.applicationContext)
        view = MapView(activity)
        view.setMaximumFps(20)
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        miniHost.addView(view, 0)
        view.onCreate(null)
        view.getMapAsync(object : OnMapReadyCallback {
            override fun onMapReady(mapplsMap: MapplsMap) {
                if (destroyed) return
                map = mapplsMap
                drawRoute()
                latest?.let(::updatePosition)
            }

            override fun onMapError(code: Int, message: String?) {
                onError("Mappls map error $code: ${message.orEmpty()}")
            }
        })
    }

    fun setRoute(value: WalkingRoute) {
        route = value
        drawRoute()
    }

    private fun drawRoute() {
        val m = map ?: return
        val r = route ?: return
        m.clear()
        currentMarker = null
        val points = r.route.getCoordinates().map { LatLng(it.latitude(), it.longitude()) }
        if (points.size > 1) {
            m.addPolyline(PolylineOptions().addAll(points).color(Color.rgb(0, 213, 232)).width(6f))
        }
        m.addMarker(MarkerOptions().position(LatLng(r.destination.latitude(), r.destination.longitude()))
            .title("Destination"))
        latest?.let(::updatePosition)
    }

    fun updatePosition(location: Location) {
        latest = location
        val m = map ?: return
        val latLng = LatLng(location.latitude, location.longitude)
        val marker = currentMarker
        if (marker == null) currentMarker = m.addMarker(MarkerOptions().position(latLng).title("You are here"))
        else marker.position = latLng
        val now = SystemClock.elapsedRealtime()
        if (now - lastPanMs >= 1_500L) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16.5))
            lastPanMs = now
        }
    }

    fun setExpanded(expanded: Boolean) {
        val target = if (expanded) expandedHost else miniHost
        if (view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, 0)
        view.requestLayout()
        latest?.let(::updatePosition)
    }

    fun onStart() { if (!started) { view.onStart(); started = true } }
    fun onResume() { if (!resumed) { view.onResume(); resumed = true } }
    fun onPause() { if (resumed) { view.onPause(); resumed = false } }
    fun onStop() { if (started) { view.onStop(); started = false } }
    fun onSaveInstanceState(out: Bundle) { view.onSaveInstanceState(out) }
    fun destroy() {
        if (destroyed) return
        onPause()
        onStop()
        view.onDestroy()
        (view.parent as? ViewGroup)?.removeView(view)
        map = null
        destroyed = true
    }
}
