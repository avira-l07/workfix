package com.itantra.feature.languages

import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.EngineFactory
import com.itantra.core.inference.SpeechRecognizerEngine
import com.itantra.core.inference.SpeechSynthesizerEngine
import com.itantra.data.languagepack.MockLanguagePackRepository
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.firstOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test


@OptIn(ExperimentalCoroutinesApi::class)
class LanguagePacksViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeRecognizer(override val languageCode: LanguageCode) : SpeechRecognizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() { loaded = true }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun reset() {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult =
            SpeechRecognitionResult(languageCode, "test", 1.0f, true, 0L)
        override suspend fun unload() { loaded = false }
    }

    private class FakeSynthesizer(override val languageCode: LanguageCode) : SpeechSynthesizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() { loaded = true }
        override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult =
            throw NotImplementedError()
        override suspend fun unload() { loaded = false }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun activateLanguage_setsRepositoryActiveLanguage() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository()
        val viewModel = LanguagePacksViewModel(repo)

        viewModel.activateLanguage(LanguageCode.HINDI)
        advanceUntilIdle()

        // ViewModel must only request the repository change.
        // Engine lifecycle is exclusively managed by AppGraph's lifecycle observer.
        assertEquals(LanguageCode.HINDI, repo.observeActiveLanguage().firstOrNull())
    }

    @Test
    fun activateLanguage_doesNotDirectlyManageEngineLifecycle() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository()
        repo.simulateInstall(LanguageCode.TAMIL)
        val engineFactory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine =
                FakeRecognizer(language)
            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine =
                FakeSynthesizer(language)
        }
        val sessionManager = ActiveLanguageSessionManager(engineFactory)
        val viewModel = LanguagePacksViewModel(repo, sessionManager)

        viewModel.activateLanguage(LanguageCode.TAMIL)
        advanceUntilIdle()

        // ViewModel must NOT touch sessionManager engines directly.
        // Engine loading is done exclusively by AppGraph's collectLatest observer.
        // In this isolated test (no AppGraph), sessionManager remains untouched.
        assertNull(sessionManager.activeLanguage.value)
        assertNull(sessionManager.currentSttEngine)
        assertNull(sessionManager.currentTtsEngine)
        // But the repository state must have changed to TAMIL
        assertEquals(LanguageCode.TAMIL, repo.observeActiveLanguage().firstOrNull())
    }

    @Test
    fun downloadPack_invokesRepositoryStartDownloadExactlyOnceWithoutChangingActiveLanguage() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository().apply { simulateDownloads = true }
        val viewModel = LanguagePacksViewModel(repo)

        viewModel.downloadPack(LanguageCode.TAMIL)
        advanceUntilIdle()

        assertEquals(listOf(LanguageCode.TAMIL), repo.downloadCalls)
        // Active language in repo should not be TAMIL
        assertEquals(LanguageCode.HINDI, repo.observeActiveLanguage().firstOrNull())
    }

    @Test
    fun cancelDownload_invokesRepositoryCancelDownload() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository().apply { simulateDownloads = true }
        val viewModel = LanguagePacksViewModel(repo)

        viewModel.cancelDownload(LanguageCode.TAMIL)
        advanceUntilIdle()

        assertEquals(listOf(LanguageCode.TAMIL), repo.cancelCalls)
    }

    @Test
    fun ttsInstallForActiveLanguage_viewModelDoesNotDirectlyLoadEngines() = runTest(testDispatcher) {
        val repo = MockLanguagePackRepository()
        var synthCreated = false
        val engineFactory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine =
                FakeRecognizer(language)
            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine {
                synthCreated = true
                return FakeSynthesizer(language)
            }
        }
        val sessionManager = ActiveLanguageSessionManager(engineFactory)
        val viewModel = LanguagePacksViewModel(repo, sessionManager)
        advanceUntilIdle()

        // Simulate install of HINDI pack — ViewModel observes this change
        repo.simulateInstall(LanguageCode.HINDI)
        advanceUntilIdle()

        // ViewModel must NOT directly trigger engine creation; that is AppGraph's role.
        // Verify ViewModel itself did not instantiate a synthesizer.
        assertFalse(
            "ViewModel must not create synthesizer engines directly (AppGraph owns this)",
            synthCreated
        )
    }
}
