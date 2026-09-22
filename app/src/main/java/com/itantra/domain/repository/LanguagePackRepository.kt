package com.itantra.domain.repository

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.LanguagePackManifest
import com.itantra.domain.model.LanguagePackSummary
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for what language packs exist, their install state, and
 * which one is active.
 */
interface LanguagePackRepository {

    /** Live view of every language's pack summary, in [com.itantra.domain.model.LanguageCatalog] order. */
    fun observePackSummaries(): Flow<List<LanguagePackSummary>>

    /** Live view of whichever language is currently ACTIVE, if any. */
    fun observeActiveLanguage(): Flow<LanguageCode?>

    /** Live view of the currently selected TARGET language, if any. */
    fun observeTargetLanguage(): Flow<LanguageCode?>

    /** Live view of the currently selected RECEIVE language, if any. */
    fun observeReceiveLanguage(): Flow<LanguageCode?>

    /** Sets the preferred language for incoming messages. Null reverts to auto/sender language. */
    suspend fun setReceiveLanguage(code: LanguageCode?): Boolean

    /** Live view of speech input mode (AUTO vs MANUAL). */
    fun observeSpeechInputMode(): Flow<com.itantra.domain.model.SpeechInputMode>

    /** Sets speech input mode. */
    suspend fun setSpeechInputMode(mode: com.itantra.domain.model.SpeechInputMode)

    /** Live view of manual STT fallback language hint. */
    fun observeManualSttLanguage(): Flow<LanguageCode?>

    /** Sets manual STT language hint. */
    suspend fun setManualSttLanguage(code: LanguageCode?): Boolean

    suspend fun getManifest(code: LanguageCode): LanguagePackManifest?

    /**
     * Marks [code] as the single active language, enforcing the "only one
     * ACTIVE pack at a time" invariant. Fails (returns false) if the pack
     * is not DOWNLOADED. Does not itself load any inference engine — that
     * is [com.itantra.core.inference.ActiveLanguageSessionManager]'s job;
     * this only updates the repository's state-of-record.
     */
    suspend fun setActiveLanguage(code: LanguageCode): Boolean

    /**
     * Sets the active target language. Passing null reverts to automatic
     * resolution (same-language bypass or peer's advertised language).
     */
    suspend fun setTargetLanguage(code: LanguageCode?): Boolean

    // --- Not implemented in Task 01. Declared so the UI/domain layer can
    // already depend on a stable contract; calling these throws
    // NotImplementedError until a real repository lands. ---

    suspend fun startDownload(code: LanguageCode)

    suspend fun cancelDownload(code: LanguageCode)

    suspend fun deleteInstalledPack(code: LanguageCode)
    fun refreshStates() {}
}
