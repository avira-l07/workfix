package com.itantra.core.inference

import com.itantra.data.languagepack.FileLanguagePackStorage
import com.itantra.domain.model.LanguageCode
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarathiPiperVoiceTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(vararg names: String): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }

    @Test fun `Marathi doctor vowels use phonemes without editing the text`() {
        val text = "डॉक्टरांना बोलवा. ऑक्सिजन द्या. अॅम्ब्युलन्स बोलवा. ॲम्ब्युलन्स बोलवा."
        requireTtsTextCoverage(LanguageCode.MARATHI, text, setOf("ɔ", "ə"), phonemeFrontend = true)
        assertTrue(text.contains("डॉक्टरांना"))
    }

    @Test fun `phoneme frontend still rejects foreign alphabets explicitly`() {
        val error = assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.MARATHI, "Call डॉक्टर", emptySet(), phonemeFrontend = true)
        }
        assertTrue(error.message!!.contains("Text is preserved"))
    }

    @Test fun `legacy MMS pack cannot satisfy Piper readiness`() {
        val storage = FileLanguagePackStorage(temp.newFolder())
        val tts = File(storage.packDirectory(LanguageCode.MARATHI), "tts").apply { mkdirs() }
        File(tts, "model.onnx").writeBytes(byteArrayOf(1))
        File(tts, "tokens.txt").writeText("अ 0")
        File(tts, ".verified_v1").writeText("2.0.0")
        assertFalse(storage.isTtsInstalled(LanguageCode.MARATHI))
        assertFalse(File(tts, ".verified_v1").exists())
    }

    @Test fun `frontend extraction supports nested Marathi data paths`() {
        val root = temp.newFolder()
        MarathiPiperVoice.extractFrontend(archive(*MarathiPiperVoice.requiredDataFiles.toTypedArray()), root)
        assertTrue(MarathiPiperVoice.requiredDataFiles.all { File(root, it).length() == 3L })
    }

    @Test fun `frontend extraction rejects traversal without writing outside staging`() {
        val root = temp.newFolder()
        assertThrows(IllegalStateException::class.java) {
            MarathiPiperVoice.extractFrontend(archive("espeak-ng-data/../../escape"), root)
        }
        assertFalse(File(root.parentFile, "escape").exists())
    }

    @Test fun `frontend extraction rejects incomplete archives and unrelated files`() {
        assertThrows(IllegalStateException::class.java) {
            MarathiPiperVoice.extractFrontend(archive("espeak-ng-data/mr_dict"), temp.newFolder())
        }
        assertThrows(IllegalStateException::class.java) {
            MarathiPiperVoice.extractFrontend(archive("model.onnx"), temp.newFolder())
        }
    }

    @Test fun `Marathi manifest pins every required model and frontend file`() {
        val text = File("src/main/assets/language_packs/mr_dev_manifest.json").readText()
        val manifest = com.itantra.data.languagepack.LanguagePackManifestParser.parseOrNull(text)!!
        val spec = ModelFileSpecs.getTtsSpec(LanguageCode.MARATHI)!!
        assertEquals("piper-mr-IN-google-medium", manifest.ttsModel.modelId)
        assertTrue(spec.requiredFiles.all { !manifest.ttsModel.checksumsSha256[it].isNullOrBlank() })
        val tokens = File("src/main/assets/${MarathiPiperVoice.TOKENS_ASSET}").readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(tokens).joinToString("") { "%02x".format(it) }
        assertEquals(manifest.ttsModel.checksumsSha256["tokens.txt"], hash)
    }

    @Test fun `download conversion retains the desktop verified ONNX metadata`() {
        val bytes = MarathiPiperVoice.metadataSuffix
        assertEquals(128, bytes.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("73f79db63b661f00559a6b1868b119be10359f6ef0882ca929ae6df82abf1cd6", hash)
    }

    @Test fun `offline import stages nested Piper files and rejects incomplete replacement`() {
        val storage = FileLanguagePackStorage(temp.newFolder())
        val source = temp.newFolder()
        ModelFileSpecs.getTtsSpec(LanguageCode.MARATHI)!!.requiredFiles.forEach { name ->
            File(source, name).apply { parentFile!!.mkdirs(); writeText("phoneme 1") }
        }
        assertTrue(storage.importLanguageTts(LanguageCode.MARATHI, source))
        assertTrue(storage.isTtsInstalled(LanguageCode.MARATHI))
        File(source, MarathiPiperVoice.requiredDataFiles.last()).delete()
        assertFalse(storage.importLanguageTts(LanguageCode.MARATHI, source))
        assertTrue(storage.isTtsInstalled(LanguageCode.MARATHI))
    }

    @Test fun `other language TTS layouts retain the existing two files`() {
        LanguageCode.entries.filter { it != LanguageCode.MARATHI }.forEach { language ->
            assertEquals(listOf("model.onnx", "tokens.txt"), ModelFileSpecs.getTtsSpec(language)!!.requiredFiles)
        }
    }
}
