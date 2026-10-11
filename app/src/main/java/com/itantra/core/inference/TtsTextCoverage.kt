package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.text.Normalizer

/** Match the MMS character frontend: canonical Unicode and ASCII case folding only. */
internal fun prepareTtsText(text: String): String = buildString {
    Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().forEach { cp ->
        appendCodePoint(when {
            cp in 'A'.code..'Z'.code -> cp + ('a'.code - 'A'.code)
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> ' '.code
            else -> cp
        })
    }
}

/** MMS skips unknown characters. Reject meaningful omissions before native inference. */
internal fun requireTtsTextCoverage(language: LanguageCode, text: String, tokens: Set<String>, phonemeFrontend: Boolean = false) {
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
    val prepared = prepareTtsText(text)
    // These installed MMS exports have a character frontend and no number
    // normalizer. Even Bengali's digit IDs do not establish audible numbers
    // (the real numeric probe loses them). Never play a shortened identifier.
    if (prepared.codePoints().anyMatch(Character::isDigit)) throw TtsSynthesisException(
        "The current ${language.name.lowercase()} voice needs numbers written in words. Text is preserved; speech was not played."
    )
    val missing = prepared.codePoints().toArray()
        .filter { Character.isLetterOrDigit(it) || Character.getType(it) in listOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
            Character.MATH_SYMBOL.toInt(), Character.CURRENCY_SYMBOL.toInt()) ||
            it in listOf('−'.code, '%'.code) }
        .map { String(Character.toChars(it)) }.distinct().filterNot { it in tokens }
    if (missing.isNotEmpty()) throw TtsSynthesisException(
        "The current ${language.name.lowercase()} voice cannot pronounce ${missing.joinToString(" ")}. Text is preserved; speech was not played. Edit the text or use another voice to retry."
    )
}
