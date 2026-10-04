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

    @Test fun `ten manual languages use distinct pinned CTC packs`() {
        val tamil = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.TAMIL))
        val telugu = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.TELUGU))
        val hindi = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.HINDI))
        val english = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.ENGLISH))
        val odia = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.ODIA))
        val bengali = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.BENGALI))
        val gujarati = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.GUJARATI))
        val marathi = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.MARATHI))
        val malayalam = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.MALAYALAM))
        assertNotEquals(marathi.relativePath, malayalam.relativePath)
        assertNotEquals(marathi.files["model.int8.onnx"]?.sha256, malayalam.files["model.int8.onnx"]?.sha256)
        assertTrue(malayalam.downloadUrlFor("model.int8.onnx").endsWith("/ml/model.int8.onnx"))
        assertTrue(malayalam.downloadUrlFor("tokens.txt").endsWith("/tokens.txt"))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.MALAYALAM, autoDetect = true))
        val kannada = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.KANNADA))
        assertNotEquals(kannada.relativePath, malayalam.relativePath)
        assertTrue(kannada.downloadUrlFor("model.int8.onnx").endsWith("/kn/model.int8.onnx"))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.KANNADA, autoDetect = true))
        assertNotEquals(hindi.relativePath, marathi.relativePath)
        assertNotEquals(hindi.files["model.int8.onnx"]?.sha256, marathi.files["model.int8.onnx"]?.sha256)
        assertTrue(marathi.downloadUrlFor("model.int8.onnx").endsWith("/mr/model.int8.onnx"))
        assertTrue(marathi.downloadUrlFor("tokens.txt").endsWith("/tokens.txt"))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.MARATHI, autoDetect = true))
        assertNotEquals(tamil.relativePath, telugu.relativePath)
        assertNotEquals(tamil.files["model.int8.onnx"]?.sha256, telugu.files["model.int8.onnx"]?.sha256)
        assertNotEquals(hindi.relativePath, english.relativePath)
        assertNotEquals(hindi.relativePath, odia.relativePath)
        assertNotEquals(bengali.relativePath, gujarati.relativePath)
        assertNotEquals(bengali.files["model.int8.onnx"]?.sha256, gujarati.files["model.int8.onnx"]?.sha256)
        assertEquals(listOf("model.int8.onnx", "tokens.txt"), tamil.spec().requiredFiles)
        assertNull(tamil.spec().auxFile)
        assertTrue(tamil.downloadUrlFor("tokens.txt").endsWith("/tokens.txt"))
        assertTrue(english.downloadUrlFor("tokens.txt").endsWith("/en/tokens.txt"))
        assertTrue(odia.downloadUrlFor("tokens.txt").endsWith("/tokens.txt"))
        assertTrue(bengali.downloadUrlFor("model.int8.onnx").endsWith("/bn/model.int8.onnx"))
        assertTrue(gujarati.downloadUrlFor("model.int8.onnx").endsWith("/gu/model.int8.onnx"))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.TAMIL, autoDetect = true))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.TELUGU, autoDetect = true))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.ENGLISH, autoDetect = true))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.BENGALI, autoDetect = true))
        assertNull(AdditionalSttModel.forLanguage(LanguageCode.GUJARATI, autoDetect = true))
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

    @Test fun `candidate update keeps the installed pack for all four training languages`() {
        for (language in listOf(LanguageCode.TAMIL, LanguageCode.TELUGU,
                                LanguageCode.BENGALI, LanguageCode.GUJARATI)) {
            val root = temp.newFolder(language.wireCode)
            val original = "${language.wireCode} baseline".toByteArray()
            val model = smallModel(check(original), language)
            val stage = File(root, "baseline").apply { mkdirs() }
            File(stage, "model.int8.onnx").writeBytes(original)
            AdditionalSttModel.installDownloaded(root, model, stage)
            val candidate = smallModel(check("candidate".toByteArray()), language)
            val broken = File(root, "candidate").apply { mkdirs() }
            File(broken, "model.int8.onnx").writeText("bad checksum")
            assertThrows(IllegalStateException::class.java) {
                AdditionalSttModel.installDownloaded(root, candidate, broken)
            }
            assertTrue(AdditionalSttModel.isInstalled(root, model))
            assertArrayEquals(original, File(model.directory(root), "model.int8.onnx").readBytes())
        }
    }

    @Test fun `ten download manifests match the pinned runtime model files`() {
        for (code in LanguageCode.entries) {
            val model = requireNotNull(AdditionalSttModel.forLanguage(code))
            val raw = File("src/main/assets/language_packs/${code.wireCode}_dev_manifest.json").readText()
            val manifest = requireNotNull(LanguagePackManifestParser.parseOrNull(raw))
            assertEquals(model.files.keys.toList(), manifest.sttModel.files.map { it.substringAfterLast('/') })
            assertEquals(model.totalBytes, manifest.sttModel.sizeBytes)
            for (remotePath in manifest.sttModel.files) {
                val name = remotePath.substringAfterLast('/')
                val check = model.files.getValue(name)
                assertEquals(check.sha256, manifest.sttModel.checksumsSha256[remotePath])
                assertEquals(model.downloadUrlFor(name), "${manifest.sttModel.downloadUrl}/$remotePath")
            }
        }
    }

    @Test fun `old shared Tiny install cannot mark dedicated Marathi ready`() {
        val root = temp.newFolder()
        val tiny = File(root, "shared/stt").apply { mkdirs() }
        File(tiny, ".verified_v1").writeText("1.0.0")
        File(tiny, "tiny-encoder.int8.onnx").writeText("old encoder")
        File(tiny, "tiny-decoder.int8.onnx").writeText("old decoder")
        File(tiny, "tiny-tokens.txt").writeText("old tokens")
        val marathi = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.MARATHI))
        assertFalse(AdditionalSttModel.isInstalled(root, marathi))

        val bytes = "checksum-verified Marathi fixture".toByteArray()
        val fixture = marathi.copy(files = mapOf("model.int8.onnx" to check(bytes)))
        val stage = File(root, "mr-stage").apply { mkdirs() }
        File(stage, "model.int8.onnx").writeBytes(bytes)
        AdditionalSttModel.installDownloaded(root, fixture, stage)
        assertTrue(AdditionalSttModel.isInstalled(root, fixture))
        assertEquals("old decoder", File(tiny, "tiny-decoder.int8.onnx").readText())
        assertFalse(AdditionalSttModel.isInstalled(root, marathi))
    }

    @Test fun `legacy Malayalam Tiny files do not satisfy dedicated pack readiness`() {
        val root = temp.newFolder()
        val tiny = File(root, "shared/stt").apply { mkdirs() }
        File(tiny, "tiny-decoder.int8.onnx").writeText("existing shared model")
        val model = requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.MALAYALAM))
        assertFalse(AdditionalSttModel.isInstalled(root, model))

        val original = "Malayalam model fixture".toByteArray()
        val fixture = model.copy(files = mapOf("model.int8.onnx" to check(original)))
        val staged = File(root, "ml-stage").apply { mkdirs() }
        File(staged, "model.int8.onnx").writeBytes(original)
        AdditionalSttModel.installDownloaded(root, fixture, staged)
        assertTrue(AdditionalSttModel.isInstalled(root, fixture))
        assertFalse(AdditionalSttModel.isInstalled(root, model))
        assertEquals("existing shared model", File(tiny, "tiny-decoder.int8.onnx").readText())
    }

    private fun smallModel(fileCheck: AdditionalSttModel.FileCheck,
                           language: LanguageCode = LanguageCode.TAMIL) = AdditionalSttModel.Model(
        language = language,
        relativePath = "shared/stt-${language.wireCode}-test",
        displayName = "test",
        remoteFolder = "ta",
        files = mapOf("model.int8.onnx" to fileCheck),
    )

    private fun check(bytes: ByteArray) = AdditionalSttModel.FileCheck(
        bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )
}
