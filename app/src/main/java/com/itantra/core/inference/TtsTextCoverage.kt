package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.text.Normalizer

/** Reject unsupported Marathi letters before inference instead of silently skipping them. */
internal fun requireTtsTextCoverage(language: LanguageCode, text: String, tokens: Set<String>, phonemeFrontend: Boolean = false) {
    if (language != LanguageCode.MARATHI) return
    if (phonemeFrontend) {
        // Piper tokens are phonemes, not Devanagari characters. eSpeak converts
        // the native text; reject other alphabets rather than silently dropping them.
        val unsupported = text.codePoints().toArray().filter {
            (Character.isLetter(it) || Character.getType(it) in listOf(
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) &&
                Character.UnicodeScript.of(it) != Character.UnicodeScript.DEVANAGARI
        }
        if (unsupported.isNotEmpty()) throw TtsSynthesisException(
            "The Marathi voice requires Devanagari text. Text is preserved; speech was not played. Translate or edit the text to retry."
        )
        return
    }
    val missing = Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().toArray()
        .filter { Character.isLetter(it) || Character.getType(it) in listOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) }
        .map { String(Character.toChars(it)) }.distinct().filterNot { it in tokens }
    if (missing.isNotEmpty()) throw TtsSynthesisException(
        "The current Marathi voice cannot pronounce ${missing.joinToString(" ")}. Text is preserved; speech was not played. Edit the text to retry."
    )
}
