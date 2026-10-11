package com.example.mobilityassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WalkingRouteUrlTest {
    @Test fun encodesSpokenAddressForWalkingPreviewWithoutStartingNavigation() {
        val url = WalkingRouteUrl.forDestination("  12 Main St, Pune  ")
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=12+Main+St%2C+Pune&travelmode=walking", url)
        assertFalse(url.contains("dir_action=navigate"))
    }

    @Test fun destinationCannotInjectExtraMapsParameters() {
        val url = WalkingRouteUrl.forDestination("Home & Office?dir_action=navigate")
        assertFalse(url.contains("&dir_action=navigate"))
        assertEquals(1, "travelmode=walking".toRegex().findAll(url).count())
    }
}
