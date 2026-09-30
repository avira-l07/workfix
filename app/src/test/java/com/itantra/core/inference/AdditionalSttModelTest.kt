package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import com.itantra.data.languagepack.LanguagePackManifestParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdditionalSttModelTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `four manual languages use distinct pinned CTC packs`() {
        val tamil = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.TAMIL))
        val telugu = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.TELUGU))
        val hindi = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.HINDI))
        val english = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.ENGLISH))
        assertNotEquals(tamil.relativePath, telugu.relativePath)
        assertNotEquals(tamil.files["model.int8.onnx"]?.sha256, telugu.files["model.int8.onnx"]?.sha256)
        assertNotEquals(hindi.relativePath, english.relativePath)
        assertEquals(listOf("model.int8.onnx", "tokens.txt"), tamil.spec().requiredFiles)
        assertNull(tamil.spec().auxFile)
        assertTrue(tamil.downloadUrlFor("tokens.txt").endsWith("/tokens.txt"))
        assertTrue(english.downloadUrlFor("tokens.txt").endsWith("/en/tokens.txt"))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.TAMIL, autoDetect = true))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.TELUGU, autoDetect = true))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.ENGLISH, autoDetect = true))
    }

    @Test fun `corrupt downloaded pack does not replace a valid speech model`() {
        val root = temp.newFolder()
        val original = "original Tamil model".toByteArray()
        val replacement = "new Tamil model".toByteArray()
        val model = smallModel(check(original))
        val staged = File(root, "staged").apply { mkdirs() }
        File(staged, "model.int8.onnx").writeBytes(original)
        AdditionalSttModel.installDownloaded(root, model, staged)
        assertTrue(AdditionalSttModel.isInstalled(root, model))

        val changedModel = smallModel(check(replacement))
        val changedStage = File(root, "changed").apply { mkdirs() }
        File(changedStage, "model.int8.onnx").writeText("corrupt")
        assertThrows(IllegalStateException::class.java) {
            AdditionalSttModel.installDownloaded(root, changedModel, changedStage)
        }
        assertArrayEquals(original, File(model.directory(root), "model.int8.onnx").readBytes())
        assertTrue(AdditionalSttModel.isInstalled(root, model))
    }

    @Test fun `four download manifests match the pinned runtime model files`() {
        for (code in listOf(LanguageCode.HINDI, LanguageCode.ENGLISH, LanguageCode.TAMIL, LanguageCode.TELUGU)) {
            val model = requireNotNull(AdditionalSttModel.forLanguage(code))
            val raw = File("src/main/assets/language_packs/${code.wireCode}_dev_manifest.json").readText()
            val manifest = requireNotNull(LanguagePackManifestParser.parseOrNull(raw))
            assertEquals(model.files.keys.toList(), manifest.sttModel.files)
            assertEquals(model.totalBytes, manifest.sttModel.sizeBytes)
            for ((name, check) in model.files) {
                assertEquals(check.sha256, manifest.sttModel.checksumsSha256[name])
            }
        }
    }

    private fun smallModel(fileCheck: AdditionalSttModel.FileCheck) = AdditionalSttModel.Model(
        language = LanguageCode.TAMIL,
        relativePath = "shared/stt-ta-test",
        displayName = "test",
        remoteFolder = "ta",
        files = mapOf("model.int8.onnx" to fileCheck),
    )

    private fun check(bytes: ByteArray) = AdditionalSttModel.FileCheck(
        bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )
}
