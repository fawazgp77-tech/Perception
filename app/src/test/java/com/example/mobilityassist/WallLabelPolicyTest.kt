package com.example.mobilityassist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallLabelPolicyTest {
    @Test fun confidentWallLabelQualifies() {
        assertTrue(WallLabelPolicy.isLikelyWall(listOf("Wall" to 0.82f)))
    }

    @Test fun lowConfidenceOrUnrelatedSceneDoesNotQualify() {
        assertFalse(WallLabelPolicy.isLikelyWall(listOf("Wall" to 0.68f)))
        assertFalse(WallLabelPolicy.isLikelyWall(listOf("Road" to 0.93f)))
    }
}
