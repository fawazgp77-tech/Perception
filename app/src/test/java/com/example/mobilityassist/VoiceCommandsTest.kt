package com.example.mobilityassist

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
    @Test fun menuPhrases() {
        assertEquals(VoiceIntent.CAMERA, VoiceCommands.parse("Please open CameraX assistance"))
        assertEquals(VoiceIntent.NAVIGATE, VoiceCommands.parse("I want to enter destination"))
        assertEquals(VoiceIntent.NAVIGATE, VoiceCommands.parse("Navigate me home"))
    }

    @Test fun confirmationAndMicrophone() {
        assertEquals(VoiceIntent.YES, VoiceCommands.parse("Yes, that's correct"))
        assertEquals(VoiceIntent.NO, VoiceCommands.parse("No, next one"))
        assertEquals(VoiceIntent.MIC_OFF, VoiceCommands.parse("CameraX mic off"))
        assertEquals(VoiceIntent.MIC_ON, VoiceCommands.parse("microphone switch on"))
    }

    @Test fun unrelatedDestinationRemainsFreeForm() {
        assertEquals(VoiceIntent.UNKNOWN, VoiceCommands.parse("VNIT Nagpur campus"))
    }
}
