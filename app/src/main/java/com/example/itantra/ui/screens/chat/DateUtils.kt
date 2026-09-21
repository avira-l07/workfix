package com.example.itantra.ui.screens.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {

    /**
     * Formats timestamp into 12-hour format: "8:42 PM"
     */
    fun formatTime12Hour(timestampMillis: Long): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date(timestampMillis))
    }

    /**
     * Returns the date grouping header label for a timestamp:
     * - "TODAY" if on the current calendar day
     * - "YESTERDAY" if on the previous calendar day
     * - "d MMMM yyyy" (e.g. "21 September 2026") for older dates
     */
    fun getDateSeparatorLabel(timestampMillis: Long, currentTimestampMillis: Long = System.currentTimeMillis()): String {
        val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
        val nowCal = Calendar.getInstance().apply { timeInMillis = currentTimestampMillis }

        val isSameYear = msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val isSameDay = isSameYear && msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

        if (isSameDay) return "TODAY"

        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = currentTimestampMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val isYesterday = msgCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
                msgCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)

        if (isYesterday) return "YESTERDAY"

        val sdf = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        return sdf.format(Date(timestampMillis))
    }

    /**
     * Returns a unique day key (e.g. "2026-09-21") for grouping messages
     */
    fun getDayKey(timestampMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(timestampMillis))
    }
}
