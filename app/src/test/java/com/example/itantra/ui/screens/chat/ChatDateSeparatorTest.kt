package com.example.itantra.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChatDateSeparatorTest {

    @Test
    fun testFormatTime12Hour() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 30)
        }
        val formatted = DateUtils.formatTime12Hour(cal.timeInMillis)
        assertTrue(formatted.contains("2:30"))
        assertTrue(formatted.uppercase().contains("PM"))
    }

    @Test
    fun testDateSeparatorToday() {
        val now = System.currentTimeMillis()
        val label = DateUtils.getDateSeparatorLabel(now, now)
        assertEquals("TODAY", label)
    }

    @Test
    fun testDateSeparatorYesterday() {
        val nowCal = Calendar.getInstance()
        val yesterdayCal = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val label = DateUtils.getDateSeparatorLabel(yesterdayCal.timeInMillis, nowCal.timeInMillis)
        assertEquals("YESTERDAY", label)
    }

    @Test
    fun testDateSeparatorOlderDate() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 22, 12, 0, 0)
        }
        val olderCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 15, 10, 0, 0)
        }
        val label = DateUtils.getDateSeparatorLabel(olderCal.timeInMillis, nowCal.timeInMillis)
        assertTrue(label.contains("15") && label.contains("September") && label.contains("2026"))
    }

    @Test
    fun testDayKeyGrouping() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 21, 15, 30, 0)
        }
        val key = DateUtils.getDayKey(cal.timeInMillis)
        assertEquals("2026-09-21", key)
    }
}
