package com.myra.assistant

sealed interface VoiceCommand {
    data class SetVolume(val percent: Int) : VoiceCommand
    data class ChangeVolume(val deltaPercent: Int) : VoiceCommand
    data class OpenApp(val label: String) : VoiceCommand
    data class DialNumber(val number: String) : VoiceCommand
    data class BlockedSensitiveApp(val label: String) : VoiceCommand
    data class AskAssistant(val prompt: String) : VoiceCommand
}

object VoiceCommandParser {
    private val setVolumePattern =
        Regex("""(?:set|change|turn)?\s*(?:the\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*(?:percent|%)?""")
    private val dialPattern = Regex("""\b(?:call|dial|phone)\s+([\d\s()+.-]{6,})$""")
    private val openPattern = Regex("""^(?:please\s+)?(?:open|launch|start)\s+(.+)$""")

    fun parse(input: String): VoiceCommand {
        val text = input.trim().trimEnd('.', '!', '?')
        val normalized = text.lowercase()

        if (normalized.contains("mute") || normalized.contains("silence")) {
            return VoiceCommand.SetVolume(0)
        }
        if (normalized.contains("volume up") ||
            normalized.contains("increase volume") ||
            normalized.contains("turn up the volume")
        ) {
            return VoiceCommand.ChangeVolume(10)
        }
        if (normalized.contains("volume down") ||
            normalized.contains("decrease volume") ||
            normalized.contains("turn down the volume")
        ) {
            return VoiceCommand.ChangeVolume(-10)
        }

        setVolumePattern.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return VoiceCommand.SetVolume(it.coerceIn(0, 100))
        }

        openPattern.matchEntire(normalized)?.groupValues?.getOrNull(1)?.let { requested ->
            val label = requested.trim()
            return if (SensitiveAppPolicy.isSensitiveTarget(label)) {
                VoiceCommand.BlockedSensitiveApp(label)
            } else {
                VoiceCommand.OpenApp(label)
            }
        }

        dialPattern.find(text)?.groupValues?.getOrNull(1)?.let { entered ->
            val digits = entered.filter(Char::isDigit)
            if (digits.length in 6..15) return VoiceCommand.DialNumber(digits)
        }

        return VoiceCommand.AskAssistant(text)
    }
}
