package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HindiSttModelTest {
    @get:Rule val temp = TemporaryFolder()

    private fun check(bytes: ByteArray) = HindiSttModel.FileCheck(
        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    )

    @Test fun `Hindi specialization does not hijack other languages or auto detection`() {
        LanguageCode.entries.forEach { language ->
            assertFalse(HindiSttModel.selected(language, true))
            assertEquals(language == LanguageCode.HINDI, HindiSttModel.selected(language, false))
        }
    }

    @Test fun `old tiny files cannot satisfy Hindi readiness`() {
        val root = temp.newFolder()
        val old = File(root, "shared/stt").apply { mkdirs() }
        File(old, "tiny-encoder.int8.onnx").writeText("old")
        File(old, ".verified_v1").writeText("1")
        assertFalse(HindiSttModel.isInstalled(root))
    }

    @Test fun `corrupt replacement preserves installed model and leaves shared tiny untouched`() {
        val root = temp.newFolder()
        val old = "valid model".toByteArray()
        val checks = mapOf("encoder.int8.onnx" to check(old))
        val tiny = File(root, "shared/stt/tiny-encoder.int8.onnx")
        tiny.parentFile.mkdirs()
        tiny.writeText("tiny")
        HindiSttModel.install(root, checks) { ByteArrayInputStream(old) }
        val replacement = "replacement".toByteArray()
        assertThrows(IllegalStateException::class.java) {
            HindiSttModel.install(root, mapOf("encoder.int8.onnx" to check(replacement))) {
                ByteArrayInputStream("bad".toByteArray())
            }
        }
        assertArrayEquals(old, File(HindiSttModel.directory(root), "encoder.int8.onnx").readBytes())
        assertEquals("tiny", tiny.readText())
        assertFalse(File(root, "shared").listFiles()!!.any { it.name.startsWith(".hi_") })
    }

    @Test fun `interrupted install never becomes ready and retry can complete`() {
        val root = temp.newFolder()
        val bytes = "model".toByteArray()
        val checks = linkedMapOf("encoder.int8.onnx" to check(bytes), "tokens.txt" to check(bytes))
        assertThrows(java.io.IOException::class.java) {
            HindiSttModel.install(root, checks) { name ->
                if (name == "tokens.txt") throw java.io.IOException("Interrupted")
                ByteArrayInputStream(bytes)
            }
        }
        assertFalse(HindiSttModel.directory(root).exists())
        HindiSttModel.install(root, checks) { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes, File(HindiSttModel.directory(root), "tokens.txt").readBytes())
        HindiSttModel.install(root, checks) { error("Verified install should be reused") }
    }

    @Test fun `downloaded files must pass every checksum before activation`() {
        val root = temp.newFolder()
        val staged = File(root, "download").apply { mkdirs() }
        val model = "valid downloaded model".toByteArray()
        val checks = mapOf("encoder.int8.onnx" to check(model))
        File(staged, "encoder.int8.onnx").writeText("corrupt")
        assertThrows(IllegalStateException::class.java) {
            HindiSttModel.installDownloaded(root, staged, checks)
        }
        assertFalse(HindiSttModel.directory(root).exists())
        File(staged, "encoder.int8.onnx").writeBytes(model)
        HindiSttModel.installDownloaded(root, staged, checks)
        assertTrue(File(HindiSttModel.directory(root), "encoder.int8.onnx").isFile)
        assertFalse(staged.exists())
    }
}
