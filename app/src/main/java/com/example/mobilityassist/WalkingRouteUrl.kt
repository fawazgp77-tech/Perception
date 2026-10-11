package com.example.mobilityassist

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Opens a walking-route preview in Google Maps; Maps owns ETA and turn guidance. */
object WalkingRouteUrl {
    fun forDestination(destination: String): String {
        require(destination.isNotBlank())
        val encoded = URLEncoder.encode(destination.trim(), StandardCharsets.UTF_8.toString())
        return "https://www.google.com/maps/dir/?api=1&destination=$encoded&travelmode=walking"
    }
}
