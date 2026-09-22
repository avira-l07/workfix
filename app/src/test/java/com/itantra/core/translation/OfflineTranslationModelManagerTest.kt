package com.itantra.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class OfflineTranslationModelManagerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `inspect returns MISSING when directory does not exist`() {
        val baseDir = tempFolder.newFolder("translation_models")
        val manager = OfflineTranslationModelManager(baseDir)

        val inspection = manager.inspect(OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH)
        assertEquals(OfflineTranslationModelManager.Status.MISSING, inspection.status)
        assertEquals("MODEL_DIRECTORY_MISSING", inspection.reason)
    }

    @Test
    fun `inspect returns INVALID when manifest is missing`() {
        val baseDir = tempFolder.newFolder("translation_models")
        val indicEnDir = File(baseDir, "indic-en").apply { mkdirs() }
        val manager = OfflineTranslationModelManager(baseDir)

        val inspection = manager.inspect(OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH)
        assertEquals(OfflineTranslationModelManager.Status.INVALID, inspection.status)
        assertEquals("MANIFEST_MISSING", inspection.reason)
    }

    @Test
    fun `inspect rejects manifest with path traversal`() {
        val baseDir = tempFolder.newFolder("translation_models")
        val indicEnDir = File(baseDir, "indic-en").apply { mkdirs() }
        val manifestJson = """
            {
                "modelId": "test-model",
                "version": "1.0",
                "direction": "hi-en",
                "runtime": "ctranslate2",
                "requiredFiles": ["../secret.txt"],
                "sha256": {"../secret.txt": "0000000000000000000000000000000000000000000000000000000000000000"}
            }
        """.trimIndent()
        File(indicEnDir, "manifest.json").writeText(manifestJson)

        val manager = OfflineTranslationModelManager(baseDir)
        val inspection = manager.inspect(OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH)
        assertEquals(OfflineTranslationModelManager.Status.INVALID, inspection.status)
        assertTrue(inspection.reason?.startsWith("UNSAFE_PATH") == true)
    }

    @Test
    fun `inspect rejects manifest with duplicate files`() {
        val baseDir = tempFolder.newFolder("translation_models")
        val indicEnDir = File(baseDir, "indic-en").apply { mkdirs() }
        val manifestJson = """
            {
                "modelId": "test-model",
                "version": "1.0",
                "direction": "hi-en",
                "runtime": "ctranslate2",
                "requiredFiles": ["model.bin", "model.bin"],
                "sha256": {"model.bin": "0000000000000000000000000000000000000000000000000000000000000000"}
            }
        """.trimIndent()
        File(indicEnDir, "manifest.json").writeText(manifestJson)

        val manager = OfflineTranslationModelManager(baseDir)
        val inspection = manager.inspect(OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH)
        assertEquals(OfflineTranslationModelManager.Status.INVALID, inspection.status)
        assertTrue(inspection.reason?.startsWith("DUPLICATE_FILE") == true)
    }

    @Test
    fun `inspect returns READY for valid manifest and verified files`() {
        val baseDir = tempFolder.newFolder("translation_models")
        val indicEnDir = File(baseDir, "indic-en").apply { mkdirs() }
        val modelContent = "valid model content"
        val modelHash = sha256(modelContent)

        File(indicEnDir, "model.bin").writeText(modelContent)
        val manifestJson = """
            {
                "modelId": "test-model",
                "version": "1.0",
                "direction": "hi-en",
                "runtime": "ctranslate2",
                "requiredFiles": ["model.bin"],
                "sha256": {"model.bin": "$modelHash"}
            }
        """.trimIndent()
        File(indicEnDir, "manifest.json").writeText(manifestJson)

        val manager = OfflineTranslationModelManager(baseDir)
        val inspection = manager.inspect(OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH)
        assertEquals(OfflineTranslationModelManager.Status.READY, inspection.status)
        assertTrue(manager.isReady(OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH))
    }
}
