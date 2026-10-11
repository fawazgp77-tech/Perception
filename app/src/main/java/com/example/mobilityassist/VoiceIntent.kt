package com.example.mobilityassist

/** Small, deterministic command vocabulary; place names are handled as free-form text. */
enum class VoiceIntent { CAMERA, NAVIGATE, YES, NO, MIC_OFF, MIC_ON, MENU, UNKNOWN }

object VoiceCommands {
    fun parse(raw: String): VoiceIntent {
        val words = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        fun has(vararg values: String) = values.any { it in words }
        if (has("mic", "microphone") && has("off", "mute", "disable")) return VoiceIntent.MIC_OFF
        if (has("mic", "microphone") && has("on", "unmute", "enable")) return VoiceIntent.MIC_ON
        if (has("cancel", "menu", "back")) return VoiceIntent.MENU
        if (has("camera", "camerax", "assist", "assistance", "scan")) return VoiceIntent.CAMERA
        if (has("destination", "navigate", "navigation", "directions", "route")) return VoiceIntent.NAVIGATE
        if (has("yes", "correct", "confirm", "okay", "ok", "right", "start")) return VoiceIntent.YES
        if (has("no", "wrong", "next", "different", "reject")) return VoiceIntent.NO
        return VoiceIntent.UNKNOWN
    }
}
