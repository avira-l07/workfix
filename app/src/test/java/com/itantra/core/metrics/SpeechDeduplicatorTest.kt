package com.itantra.core.metrics

import com.itantra.core.inference.SpeechDeduplicator
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechDeduplicatorTest {

    @Test
    fun testShortPhraseLoop021() {
        val raw = "Patient is conscious. Patient is conscious. Patient is conscious. Patient"
        val clean = SpeechDeduplicator.deduplicate(raw)
        assertEquals("Patient is conscious.", clean)
    }

    @Test
    fun testShortPhraseLoop024() {
        val raw = "Fire is not controlled. Fire is not controlled. Fire is not controlled"
        val clean = SpeechDeduplicator.deduplicate(raw)
        assertEquals("Fire is not controlled.", clean)
    }

    @Test
    fun testNormalSentenceNotAltered() {
        val raw = "Good morning, have a great day."
        val clean = SpeechDeduplicator.deduplicate(raw)
        assertEquals("Good morning, have a great day.", clean)
    }

    @Test
    fun testDifferentMultiSentenceNotAltered() {
        val raw = "All telephone lines are down. Maintain radio contact."
        val clean = SpeechDeduplicator.deduplicate(raw)
        assertEquals("All telephone lines are down. Maintain radio contact.", clean)
    }

    @Test
    fun testHindiLoop() {
        val raw = "मदद की जरूरत है। मदद की जरूरत है। मदद की"
        val clean = SpeechDeduplicator.deduplicate(raw)
        assertEquals("मदद की जरूरत है।", clean)
    }

    @Test
    fun testInstructionAfterShortWordIsKept() {
        assertEquals("Stop. Stop the vehicle now.", SpeechDeduplicator.deduplicate("Stop. Stop the vehicle now."))
        assertEquals("Fire. Fire exit is blocked.", SpeechDeduplicator.deduplicate("Fire. Fire exit is blocked."))
        assertEquals(
            "Send medic. Send medic to bridge two immediately.",
            SpeechDeduplicator.deduplicate("Send medic. Send medic to bridge two immediately.")
        )
    }

    @Test
    fun testTwoRepeatsFollowedByNewContentAreKept() {
        val raw = "Move now, move now, then evacuate the east wing."
        assertEquals(raw, SpeechDeduplicator.deduplicate(raw))
    }

    @Test
    fun testLoopFollowedByRealContentKeepsTheContent() {
        assertEquals(
            "Move now, then evacuate the east wing.",
            SpeechDeduplicator.deduplicate("Move now, move now, move now, then evacuate the east wing.")
        )
        assertEquals(
            "Casualty at gate two proceed to the medical tent",
            SpeechDeduplicator.deduplicate("Casualty at gate two casualty at gate two casualty at gate two proceed to the medical tent")
        )
    }

    @Test
    fun testDeliberateTripleCallIsKept() {
        assertEquals("Mayday mayday mayday", SpeechDeduplicator.deduplicate("Mayday mayday mayday"))
    }

    @Test
    fun testSpokenNumbersAreNeverCollapsed() {
        assertEquals("Grid 0 0 0 0 0 5", SpeechDeduplicator.deduplicate("Grid 0 0 0 0 0 5"))
    }

    @Test
    fun testLongSingleWordLoopIsCollapsed() {
        assertEquals("the", SpeechDeduplicator.deduplicate("the the the the the the"))
    }

    @Test
    fun testBlankInputReturnsEmpty() {
        assertEquals("", SpeechDeduplicator.deduplicate("    "))
    }
}
