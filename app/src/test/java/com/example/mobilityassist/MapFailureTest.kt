package com.example.mobilityassist

import org.junit.Assert.assertEquals
import org.junit.Test

class MapFailureTest {
    @Test fun serviceFailuresRemainDistinctFromMissingWalkingRoute() {
        assertEquals(MapFailureKind.SERVICE, MapFailure.from("route", 4, "Method not Provisioned").kind)
        assertEquals(MapFailureKind.ACCESS, MapFailure.from("suggest", 401, "Unauthorized").kind)
        assertEquals(MapFailureKind.QUOTA, MapFailure.from("route", 403, "Limit reached").kind)
        assertEquals(MapFailureKind.NETWORK, MapFailure.from("route", 408, "Timeout").kind)
        assertEquals(MapFailureKind.NETWORK, MapFailure.from("suggest", 10, "Unknown host").kind)
        assertEquals(MapFailureKind.NO_ROUTE, MapFailure(MapFailureKind.NO_ROUTE, "route").kind)
    }
}
