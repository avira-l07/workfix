package com.itantra.feature.languages

import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.OfflineReadinessChecker
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.core.translation.MlKitOfflineTranslationEngine
import com.itantra.core.translation.TranslationEngine
import com.itantra.core.translation.TranslationModelState
import com.itantra.data.languagepack.MockLanguagePackRepository
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.LanguagePackInstallState
import com.itantra.domain.model.LanguagePackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SelectiveLanguageProvisioningTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun selectiveProvisioning_triggersDownloadsOnlyForSelectedLanguagesNotAll10() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository().apply { simulateDownloads = true }
        val provisionedLangs = mutableSetOf<LanguageCode>()

        val viewModel = LanguagePacksViewModel(
            repository = repo,
            provisioner = { langs -> provisionedLangs.addAll(langs) }
        )

        // Initial state: Hindi and English are pre-installed
        // User stages Bengali (Speak) and Gujarati (Listen)
        viewModel.toggleStagedMicLanguage(LanguageCode.BENGALI)
        viewModel.toggleStagedListenLanguage(LanguageCode.GUJARATI)
        advanceUntilIdle()

        // Before applying, download calls should be empty
        assertEquals(emptyList<LanguageCode>(), repo.downloadCalls)
        assertEquals(emptySet<LanguageCode>(), provisionedLangs)

        // User clicks Apply & Provision
        viewModel.applyAndProvisionSelected()
        advanceUntilIdle()

        // Repository should have received download calls ONLY for Bengali and Gujarati
        // (Hindi and English are already installed in MockLanguagePackRepository, so they shouldn't re-download)
        val expectedDownloads = setOf(LanguageCode.BENGALI, LanguageCode.GUJARATI)
        assertEquals(expectedDownloads, repo.downloadCalls.toSet())
        assertEquals(2, repo.downloadCalls.size)
        assertEquals(
            setOf(
                Triple(LanguageCode.BENGALI, true, false),
                Triple(LanguageCode.GUJARATI, false, true),
            ),
            repo.componentDownloadCalls.toSet(),
        )

        // ML Kit provisioner should have received selected languages (Hindi, English, Bengali, Gujarati)
        val expectedMlKit = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH, LanguageCode.BENGALI, LanguageCode.GUJARATI)
        assertEquals(expectedMlKit, provisionedLangs)

        // Tamil, Telugu, Kannada, Malayalam, Marathi, Odia were NOT downloaded
        val unselected = setOf(
            LanguageCode.TAMIL, LanguageCode.TELUGU, LanguageCode.KANNADA,
            LanguageCode.MALAYALAM, LanguageCode.MARATHI, LanguageCode.ODIA
        )
        for (code in unselected) {
            assertFalse("Unselected language $code should NOT be downloaded", repo.downloadCalls.contains(code))
        }
    }

    @Test
    fun resetStagedLanguages_clearsStagingWithoutDownloading() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository().apply { simulateDownloads = true }
        var provisionCalled = false

        val viewModel = LanguagePacksViewModel(
            repository = repo,
            provisioner = { provisionCalled = true }
        )

        viewModel.toggleStagedMicLanguage(LanguageCode.TAMIL)
        viewModel.toggleStagedListenLanguage(LanguageCode.TELUGU)
        advanceUntilIdle()

        assertTrue(viewModel.stagedMicLanguages.value?.contains(LanguageCode.TAMIL) == true)
        assertTrue(viewModel.stagedListenLanguages.value?.contains(LanguageCode.TELUGU) == true)

        viewModel.resetStagedLanguages()
        advanceUntilIdle()

        assertNull(viewModel.stagedMicLanguages.value)
        assertNull(viewModel.stagedListenLanguages.value)
        assertEquals(emptyList<LanguageCode>(), repo.downloadCalls)
        assertFalse(provisionCalled)
    }

    @Test fun stagingUsesSavedSelectionsBeforeUiFlowsAreSubscribed() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository().apply { simulateDownloads = true }
        repo.setEnabledMicLanguages(setOf(LanguageCode.GUJARATI))
        repo.setEnabledListenLanguages(setOf(LanguageCode.TELUGU))
        val vm = LanguagePacksViewModel(repo, provisioner = {})
        vm.toggleStagedMicLanguage(LanguageCode.BENGALI)
        vm.applyAndProvisionSelected()
        advanceUntilIdle()
        assertEquals(setOf(LanguageCode.GUJARATI, LanguageCode.BENGALI), repo.getEnabledMicLanguages())
        assertEquals(setOf(LanguageCode.TELUGU), repo.getEnabledListenLanguages())
        assertEquals(setOf(Triple(LanguageCode.GUJARATI, true, false), Triple(LanguageCode.BENGALI, true, false),
            Triple(LanguageCode.TELUGU, false, true)), repo.componentDownloadCalls.toSet())
    }

    @Test fun retryUsesPersistedListenOnlySelectionWithoutDownloadingItsMicModel() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository().apply { simulateDownloads = true }
        repo.setEnabledMicLanguages(setOf(LanguageCode.HINDI))
        repo.setEnabledListenLanguages(setOf(LanguageCode.GUJARATI))
        LanguagePacksViewModel(repo, provisioner = {}).retryProvision(LanguageCode.GUJARATI)
        advanceUntilIdle()
        assertEquals(listOf(Triple(LanguageCode.GUJARATI, false, true)), repo.componentDownloadCalls)
    }

    @Test
    fun mlKitSupportedLanguages_excludesMalayalamAndOdia() {
        val supported = MlKitOfflineTranslationEngine.ML_KIT_SUPPORTED_LANGUAGES

        assertTrue(supported.contains(LanguageCode.HINDI))
        assertTrue(supported.contains(LanguageCode.ENGLISH))
        assertTrue(supported.contains(LanguageCode.BENGALI))
        assertTrue(supported.contains(LanguageCode.GUJARATI))
        assertTrue(supported.contains(LanguageCode.MARATHI))
        assertTrue(supported.contains(LanguageCode.KANNADA))
        assertTrue(supported.contains(LanguageCode.TAMIL))
        assertTrue(supported.contains(LanguageCode.TELUGU))

        // Malayalam and Odia MUST NOT be present in ML Kit supported languages
        assertFalse("Malayalam (ml) has no ML Kit model", supported.contains(LanguageCode.MALAYALAM))
        assertFalse("Odia (or) has no ML Kit model", supported.contains(LanguageCode.ODIA))
        assertEquals(8, supported.size)
    }

    @Test
    fun offlineReadinessChecker_malayalamAndOdiaReturnNotInstalledForMtBypass() {
        val tempDir = java.nio.file.Files.createTempDirectory("readiness_test").toFile()
        tempDir.deleteOnExit()

        val fakeStorage = object : LanguagePackStorage {
            override fun packDirectory(code: LanguageCode): File = File(tempDir, code.wireCode)
            override fun isInstalled(code: LanguageCode): Boolean = true
            override fun isTtsInstalled(code: LanguageCode): Boolean = true
            override suspend fun verifyChecksums(code: LanguageCode, expectedChecksums: Map<String, String>): Boolean = true
            override suspend fun deletePack(code: LanguageCode) {}
            override fun totalInstalledBytes(): Long = 0L
        }

        val fakeTranslationEngine = object : TranslationEngine {
            override val isLoaded: Boolean = true
            override val supportedSourceLanguages: Set<LanguageCode> = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
            override val supportedTargetLanguages: Set<LanguageCode> = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
            override fun init(modelsDir: File) {}
            override fun release() {}
            override suspend fun translate(
                text: String,
                sourceLang: LanguageCode,
                targetLang: LanguageCode
            ): com.itantra.core.translation.TranslationResult =
                com.itantra.core.translation.TranslationResult(
                    originalText = text,
                    translatedText = text,
                    isSuccessful = true,
                    sourceLanguage = sourceLang,
                    targetLanguage = targetLang
                )
        }

        val engineFactory = object : com.itantra.core.inference.EngineFactory {
            override fun createRecognizer(language: LanguageCode): com.itantra.core.inference.SpeechRecognizerEngine =
                throw NotImplementedError()
            override fun createSynthesizer(language: LanguageCode): com.itantra.core.inference.SpeechSynthesizerEngine =
                throw NotImplementedError()
        }
        val sessionManager = ActiveLanguageSessionManager(engineFactory)

        val checker = OfflineReadinessChecker(fakeStorage, sessionManager, fakeTranslationEngine)

        // Check Malayalam MT
        val mlMt = checker.checkLanguageMt(LanguageCode.MALAYALAM, LanguageCode.ENGLISH)
        assertEquals(OfflineReadinessChecker.ComponentStatus.NOT_INSTALLED, mlMt.status)

        // Check Odia MT
        val orMt = checker.checkLanguageMt(LanguageCode.ENGLISH, LanguageCode.ODIA)
        assertEquals(OfflineReadinessChecker.ComponentStatus.NOT_INSTALLED, orMt.status)

        // Check TTS is ready
        val mlTts = checker.checkTts(LanguageCode.MALAYALAM)
        assertEquals(OfflineReadinessChecker.ComponentStatus.READY_OFFLINE, mlTts.status)

        val orTts = checker.checkTts(LanguageCode.ODIA)
        assertEquals(OfflineReadinessChecker.ComponentStatus.READY_OFFLINE, orTts.status)
    }

    @Test
    fun baselineHindiEnglishFlow_persistsAndRetainsFullReadiness() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository()
        assertEquals(setOf(LanguageCode.HINDI, LanguageCode.ENGLISH), repo.getEnabledMicLanguages())
        assertEquals(setOf(LanguageCode.HINDI, LanguageCode.ENGLISH), repo.getEnabledListenLanguages())

        val micLangs = repo.observeEnabledMicLanguages().firstOrNull()
        val listenLangs = repo.observeEnabledListenLanguages().firstOrNull()

        assertEquals(setOf(LanguageCode.HINDI, LanguageCode.ENGLISH), micLangs)
        assertEquals(setOf(LanguageCode.HINDI, LanguageCode.ENGLISH), listenLangs)
    }
}
