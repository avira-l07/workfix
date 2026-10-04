package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.text.Normalizer

/** Reject unsupported Marathi letters before inference instead of silently skipping them. */
internal fun requireTtsTextCoverage(language: LanguageCode, text: String, tokens: Set<String>) {
    if (language != LanguageCode.MARATHI) return
    val missing = Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().toArray()
        .filter { Character.isLetter(it) || Character.getType(it) in listOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) }
        .map { String(Character.toChars(it)) }.distinct().filterNot { it in tokens }
    if (missing.isNotEmpty()) throw TtsSynthesisException(
        "The current Marathi voice cannot pronounce ${missing.joinToString(" ")}. Text is preserved; speech was not played. Edit the text to retry."
    )
}
