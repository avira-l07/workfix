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

    /**
     * Returns a human-readable relative age string (e.g. "Just now", "2 min ago", "1 hr ago", "3 days ago")
     */
    fun formatRelativeAge(timestampMillis: Long, currentTimestampMillis: Long = System.currentTimeMillis()): String {
        val diffMs = (currentTimestampMillis - timestampMillis).coerceAtLeast(0L)
        val seconds = diffMs / 1000L
        val minutes = seconds / 60L
        val hours = minutes / 60L
        val days = hours / 24L

        return when {
            minutes < 1 -> "Just now"
            minutes == 1L -> "1 min ago"
            minutes < 60 -> "$minutes min ago"
            hours == 1L -> "1 hr ago"
            hours < 24 -> "$hours hr ago"
            days == 1L -> "1 day ago"
            else -> "$days days ago"
        }
    }

    /**
     * Returns the location age label for the chat bubble.
     * When [isTimeUnverified] is true, returns "Time unverified" instead of the "(x min ago)" text.
     */
    fun formatLocationAgeLabel(
        timestampMillis: Long?,
        isTimeUnverified: Boolean,
        currentTimestampMillis: Long = System.currentTimeMillis()
    ): String {
        if (isTimeUnverified || timestampMillis == null || timestampMillis <= 0L) {
            return "Time unverified"
        }
        return formatRelativeAge(timestampMillis, currentTimestampMillis)
    }

    /**
     * Returns the full time string displayed on a location bubble.
     * When [isTimeUnverified] is true, displays "Time unverified" instead of the "(x min ago)" text.
     */
    fun formatLocationBubbleTime(
        timestampMillis: Long?,
        isTimeUnverified: Boolean,
        currentTimestampMillis: Long = System.currentTimeMillis()
    ): String {
        if (isTimeUnverified) {
            return if (timestampMillis != null && timestampMillis > 0L) {
                val timeStr = formatTime12Hour(timestampMillis)
                "Fix taken: $timeStr (Time unverified)"
            } else {
                "Time unverified"
            }
        }
        val time = timestampMillis ?: return "Time unverified"
        val timeStr = formatTime12Hour(time)
        val relativeAge = formatRelativeAge(time, currentTimestampMillis)
        return "Fix taken: $timeStr ($relativeAge)"
    }
}
