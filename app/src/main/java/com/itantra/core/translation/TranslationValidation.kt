package com.itantra.core.translation

import com.itantra.domain.model.LanguageCode

fun translationFailureMessage(error: String?): String = when (error) {
    "SOURCE_SCRIPT_MISMATCH" -> "Check the message's source language. Original text preserved."
    "OUTPUT_SCRIPT_MISMATCH" -> "The translation returned the wrong writing system. Original text preserved."
    "NUMERIC_CONTENT_REQUIRES_REVIEW" -> "The translation changed numeric content. Review the numbers; original text preserved."
    "TRANSLATION_EMPTY" -> "The translation returned no text. Original text preserved."
    "TRANSLATION_METADATA_MISMATCH" -> "The translation returned a different language route. Original text preserved."
    else -> "Translation unavailable (${error ?: "INFERENCE_FAILED"}); original text preserved."
}

/** Structural checks, never an accuracy score or a substitute for human meaning review. */
internal fun translationScript(language: LanguageCode): Character.UnicodeScript = when (language) {
    LanguageCode.HINDI, LanguageCode.MARATHI -> Character.UnicodeScript.DEVANAGARI
    LanguageCode.ENGLISH -> Character.UnicodeScript.LATIN
    LanguageCode.BENGALI -> Character.UnicodeScript.BENGALI
    LanguageCode.GUJARATI -> Character.UnicodeScript.GUJARATI
    LanguageCode.KANNADA -> Character.UnicodeScript.KANNADA
    LanguageCode.MALAYALAM -> Character.UnicodeScript.MALAYALAM
    LanguageCode.TAMIL -> Character.UnicodeScript.TAMIL
    LanguageCode.TELUGU -> Character.UnicodeScript.TELUGU
    LanguageCode.ODIA -> Character.UnicodeScript.ORIYA
}

private val indicScripts = LanguageCode.entries.filter { it != LanguageCode.ENGLISH }
    .map(::translationScript).toSet()
private fun letterScripts(text: String) = text.codePoints().toArray()
    .filter(Character::isLetter).map(Character.UnicodeScript::of).toSet()

internal fun sourceScriptConflict(text: String, source: LanguageCode): Boolean {
    val scripts = letterScripts(text)
    // Latin may be romanized Indic or an arbitrary name. Hindi and Marathi share
    // a script. Neither case permits automatic language detection here.
    return translationScript(source) !in scripts && scripts.any { it in indicScripts }
}

private val numericRuns = Regex("[+\\-−]?\\p{Nd}+(?:[.,:/]\\p{Nd}+)*")
private fun numbers(text: String) = numericRuns.findAll(text).map { match ->
    buildString {
        match.value.codePoints().forEach { cp ->
            if (Character.isDigit(cp)) append(Character.digit(cp, 10)) else appendCodePoint(cp)
        }
    }
}.toList()

internal fun translationOutputError(text: String, source: LanguageCode, target: LanguageCode,
                                    result: TranslationResult): String? {
    if (!result.isSuccessful) return null
    if (result.originalText != text || result.sourceLanguage != source || result.targetLanguage != target)
        return "TRANSLATION_METADATA_MISMATCH"
    if (result.translatedText.isBlank()) return "TRANSLATION_EMPTY"
    val outputScripts = letterScripts(result.translatedText)
    val expected = translationScript(target)
    if (expected !in outputScripts && (outputScripts.any { it in indicScripts } ||
            (letterScripts(text).any { it in indicScripts } && outputScripts.isNotEmpty() && target != LanguageCode.ENGLISH)))
        return "OUTPUT_SCRIPT_MISMATCH"
    // Conservatively require review if digits/signs/separators change, disappear,
    // reorder or appear. Written-out numbers may be legitimate: do not invent a repair.
    if (numbers(text) != numbers(result.translatedText)) return "NUMERIC_CONTENT_REQUIRES_REVIEW"
    return null
}
