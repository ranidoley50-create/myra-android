package com.myra.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun parsesAbsoluteVolume() {
        assertEquals(VoiceCommand.SetVolume(35), VoiceCommandParser.parse("set volume to 35 percent"))
    }

    @Test
    fun parsesRelativeVolume() {
        assertEquals(VoiceCommand.ChangeVolume(-10), VoiceCommandParser.parse("turn down the volume"))
    }

    @Test
    fun permanentlyBlocksCommonPaymentApps() {
        assertTrue(VoiceCommandParser.parse("open PhonePe") is VoiceCommand.BlockedSensitiveApp)
        assertTrue(VoiceCommandParser.parse("open my bank app") is VoiceCommand.BlockedSensitiveApp)
    }

    @Test
    fun parsesDialNumberWithoutPlacingCall() {
        assertEquals(VoiceCommand.DialNumber("9876543210"), VoiceCommandParser.parse("call 9876543210"))
    }

    @Test
    fun sendsOrdinaryQuestionsToAssistant() {
        assertEquals(
            VoiceCommand.AskAssistant("What time is it"),
            VoiceCommandParser.parse("What time is it?"),
        )
    }
}
