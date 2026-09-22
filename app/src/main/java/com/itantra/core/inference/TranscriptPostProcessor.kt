package com.itantra.core.inference

import java.text.Normalizer

/**
 * Production-safe post-processing for Whisper transcripts:
 * 1. Strips Whisper special tokens (`<|...|>`).
 * 2. Strips unwanted surrounding brackets and parentheses.
 * 3. Unicode NFC normalization.
 * 4. Trims whitespace.
 * 5. Deduplicates repetitive Whisper autoregressive loops via [SpeechDeduplicator].
 * 6. Basic punctuation cleanup.
 *
 * NOTE: Does NOT perform hardcoded phrase transliterations.
 */
object TranscriptPostProcessor {

    private val SPECIAL_TOKEN_REGEX = Regex("<\\|.*?\\|>")
    private val SURROUNDING_BRACKETS_REGEX = Regex("^[\\(\\)\\[\\]\\{\\}\\s]+|[\\(\\)\\[\\]\\{\\}\\s]+$")
    private val EXCESS_SPACE_REGEX = Regex("[ \\t]+")

    fun postProcess(rawText: String): String {
        if (rawText.isBlank()) return ""

        // 1. Strip special tokens
        var text = rawText.replace(SPECIAL_TOKEN_REGEX, "")

        // 2. Strip surrounding brackets and trim
        text = text.replace(SURROUNDING_BRACKETS_REGEX, "").trim()

        if (text.isEmpty()) return ""

        // 3. Unicode NFC normalization
        text = Normalizer.normalize(text, Normalizer.Form.NFC)

        // 4. Clean consecutive whitespace
        text = text.replace(EXCESS_SPACE_REGEX, " ")

        // 5. Speech deduplication to remove looping hallucinations
        text = SpeechDeduplicator.deduplicate(text)

        return text.trim()
    }
}
