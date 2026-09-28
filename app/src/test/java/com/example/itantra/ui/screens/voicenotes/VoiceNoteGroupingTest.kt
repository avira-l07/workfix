package com.example.itantra.ui.screens.voicenotes

import java.time.ZoneId
import java.time.YearMonth
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNoteGroupingTest {
    private val zone = ZoneId.systemDefault()
    private val nowDate = ZonedDateTime.of(2026, 8, 20, 15, 0, 0, 0, zone)
    private val now = nowDate.toInstant().toEpochMilli()

    private fun daysAgo(days: Long) = nowDate.minusDays(days).toInstant().toEpochMilli()

    @Test fun bucketsRecentAndOlderNotes() {
        assertEquals(VoiceNoteBucket.TODAY, VoiceNoteGrouping.keyFor(now, now, zone).bucket)
        assertEquals(VoiceNoteBucket.YESTERDAY, VoiceNoteGrouping.keyFor(daysAgo(1), now, zone).bucket)
        assertEquals(VoiceNoteBucket.THIS_WEEK, VoiceNoteGrouping.keyFor(daysAgo(6), now, zone).bucket)
        assertEquals(VoiceNoteBucket.THIS_MONTH, VoiceNoteGrouping.keyFor(daysAgo(10), now, zone).bucket)
        assertEquals(VoiceNoteBucket.OLDER, VoiceNoteGrouping.keyFor(nowDate.minusMonths(1).toInstant().toEpochMilli(), now, zone).bucket)

        val thirteenMonthsAgo = nowDate.minusMonths(13)
        val oldKey = VoiceNoteGrouping.keyFor(thirteenMonthsAgo.toInstant().toEpochMilli(), now, zone)
        assertEquals(VoiceNoteBucket.OLDER, oldKey.bucket)
        assertEquals(YearMonth.of(2025, 7), oldKey.olderMonth)
    }
}
