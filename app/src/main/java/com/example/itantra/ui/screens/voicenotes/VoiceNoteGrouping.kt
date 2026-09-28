package com.example.itantra.ui.screens.voicenotes

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class VoiceNoteBucket { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, OLDER }

data class VoiceNoteGroupKey(val bucket: VoiceNoteBucket, val olderMonth: YearMonth? = null) {
    fun title(): String = when (bucket) {
        VoiceNoteBucket.TODAY -> "Today"
        VoiceNoteBucket.YESTERDAY -> "Yesterday"
        VoiceNoteBucket.THIS_WEEK -> "This week"
        VoiceNoteBucket.THIS_MONTH -> "This month"
        VoiceNoteBucket.OLDER -> olderMonth!!.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }
}

object VoiceNoteGrouping {
    fun keyFor(createdAtMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): VoiceNoteGroupKey {
        val date = Instant.ofEpochMilli(createdAtMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val daysAgo = today.toEpochDay() - date.toEpochDay()
        return when {
            daysAgo == 0L -> VoiceNoteGroupKey(VoiceNoteBucket.TODAY)
            daysAgo == 1L -> VoiceNoteGroupKey(VoiceNoteBucket.YESTERDAY)
            daysAgo in 2..7 -> VoiceNoteGroupKey(VoiceNoteBucket.THIS_WEEK)
            date.year == today.year && date.month == today.month -> VoiceNoteGroupKey(VoiceNoteBucket.THIS_MONTH)
            else -> VoiceNoteGroupKey(VoiceNoteBucket.OLDER, YearMonth.from(date))
        }
    }

    fun <T> group(items: List<T>, createdAtMillis: (T) -> Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LinkedHashMap<VoiceNoteGroupKey, List<T>> =
        items.sortedByDescending(createdAtMillis).groupByTo(LinkedHashMap()) { keyFor(createdAtMillis(it), nowMillis, zone) }
}
