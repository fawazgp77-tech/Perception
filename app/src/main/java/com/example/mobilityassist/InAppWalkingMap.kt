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
import com.mappls.sdk.maps.geometry.LatLngBounds
import com.mappls.sdk.geojson.Point

/** One MapView is reparented between mini and half-screen hosts; attribution remains visible. */
class InAppWalkingMap(
    activity: Activity,
    private val menuHost: FrameLayout,
    private val miniHost: FrameLayout,
    private val expandedHost: FrameLayout,
    private val onReady: () -> Unit,
    private val onError: (Int) -> Unit
) {
    private val view: MapView
    private var map: MapplsMap? = null
    private var currentMarker: Marker? = null
    private var route: WalkingRoute? = null
    private var selected: Point? = null
    private var latest: Location? = null
    private var lastPanMs = 0L
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var following = false

    init {
        Mappls.getInstance(activity.applicationContext)
        view = MapView(activity)
        view.setMaximumFps(20)
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        menuHost.addView(view, 0)
        view.onCreate(null)
        view.getMapAsync(object : OnMapReadyCallback {
            override fun onMapReady(mapplsMap: MapplsMap) {
                if (destroyed) return
                map = mapplsMap
                drawRoute()
                latest?.let(::updatePosition)
                onReady()
            }

            override fun onMapError(code: Int, message: String?) {
                onError(code)
            }
        })
    }

    fun setRoute(value: WalkingRoute) {
        route = value
        selected = value.destination
        drawRoute()
    }

    fun setSelectedPlace(point: Point?) {
        selected = point
        route = null
        drawRoute()
    }

    fun showMenu() {
        following = false
        reparent(menuHost)
        drawRoute()
    }

    private fun drawRoute() {
        val m = map ?: return
        m.clear()
        currentMarker = null
        val r = route
        if (r != null) {
            val points = r.route.getCoordinates().map { LatLng(it.latitude(), it.longitude()) }
            if (points.size > 1) {
                m.addPolyline(PolylineOptions().addAll(points).color(Color.rgb(0, 213, 232)).width(6f))
                val bounds = LatLngBounds.Builder().includes(points).build()
                view.post { if (!destroyed && !following) m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 32)) }
            }
        }
        selected?.let { m.addMarker(MarkerOptions().position(LatLng(it.latitude(), it.longitude()))
            .title("Destination")) }
        if (r == null) selected?.let {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude(), it.longitude()), 15.0))
        }
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
        if ((following || route == null && selected == null) && now - lastPanMs >= 750L) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16.5))
            lastPanMs = now
        }
    }

    fun setExpanded(expanded: Boolean) {
        val target = if (expanded) expandedHost else miniHost
        following = true
        reparent(target)
        latest?.let(::updatePosition)
    }

    private fun reparent(target: FrameLayout) {
        if (view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, 0)
        view.requestLayout()
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
