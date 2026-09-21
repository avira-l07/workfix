package com.itantra.core.inference

import com.itantra.core.metrics.TextNormalizer

/**
 * Removes Whisper's autoregressive looping artifact without discarding unique words.
 */
object SpeechDeduplicator {

    private const val MAX_UNIT_WORDS = 12
    private const val MIN_REPEATS_MULTI_WORD = 3
    private const val MIN_REPEATS_SINGLE_WORD = 4

    fun deduplicate(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        val words = trimmed.split(Regex("\\s+"))
        val norm = words.map { TextNormalizer.normalize(it) }

        val out = ArrayList<String>(words.size)
        var collapsedAny = false
        var i = 0
        while (i < words.size) {
            var advanced = false
            val maxN = minOf(MAX_UNIT_WORDS, words.size - i)
            for (n in 1..maxN) {
                val unit = norm.subList(i, i + n)
                if (unit.all { it.isEmpty() }) continue
                if (unit.any { token -> token.any { ch -> ch.isDigit() } }) continue

                var reps = 1
                while (i + (reps + 1) * n <= words.size && unitEquals(norm, i, i + reps * n, n)) {
                    reps++
                }
                val end = i + reps * n
                val tailLen = words.size - end
                val truncatedTail = n >= 2 && tailLen in 1 until n && unitEquals(norm, i, end, tailLen)
                val minReps = if (n == 1) MIN_REPEATS_SINGLE_WORD else MIN_REPEATS_MULTI_WORD

                if (reps >= minReps || (n >= 2 && reps >= 2 && truncatedTail)) {
                    out.addAll(words.subList(i, i + n))
                    i = if (truncatedTail) words.size else end
                    collapsedAny = true
                    advanced = true
                    break
                }
            }
            if (!advanced) {
                out.add(words[i])
                i++
            }
        }

        return if (collapsedAny) out.joinToString(" ") else trimmed
    }

    private fun unitEquals(norm: List<String>, a: Int, b: Int, len: Int): Boolean {
        for (k in 0 until len) {
            if (norm[a + k] != norm[b + k]) return false
        }
        return true
    }
}
