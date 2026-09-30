package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Versioned Hindi-only model; never used for auto-detection or other languages. */
object HindiSttModel {
    const val relativePath = "shared/stt-hi-v1"
    const val displayName = "Whisper Hindi Small INT8"
    const val totalBytes = 375430905L
    const val downloadUrl = "https://huggingface.co/ippocode/indic-asr-onnx/resolve/1f6243ec6dffc4de2db84fe1446576d1ff52c252/models/whisper-small-hi"
    private const val marker = ".verified_hi_v1"

    data class FileCheck(val bytes: Long, val sha256: String)
    val files = linkedMapOf(
        "encoder.int8.onnx" to FileCheck(112413411, "37a8f9a573a30b8b0c55e0d5cd577096c666a6208529c4026b6c49a55e0dea7b"),
        "decoder.int8.onnx" to FileCheck(262200764, "9f2ab915e2b46236f4cc191fe1fb6519c7a75a5c3d4bfe6bce5e8e920e6fd99a"),
        "tokens.txt" to FileCheck(816730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126")
    )

    fun selected(language: LanguageCode, autoDetect: Boolean): Boolean =
        language == LanguageCode.HINDI && !autoDetect

    fun spec() = ModelFileSpec(
        type = ModelFileSpec.EngineType.STT, languageCode = LanguageCode.HINDI,
        requiredFiles = files.keys.toList(), mainModelFile = "encoder.int8.onnx",
        auxFile = "decoder.int8.onnx", tokensFile = "tokens.txt", isShared = true,
        sharedPath = relativePath
    )

    fun directory(packsRoot: File) = File(packsRoot, relativePath)

    fun isInstalled(packsRoot: File): Boolean = isInstalled(directory(packsRoot), files)

    /** Promote complete downloaded files in one step, retaining any previous version on failure. */
    @Synchronized
    internal fun installDownloaded(
        packsRoot: File,
        downloaded: File,
        checks: Map<String, FileCheck> = files
    ) {
        val target = directory(packsRoot)
        if (isInstalled(target, checks)) return
        require(downloaded.isDirectory) { "Hindi speech download is missing" }
        checks.forEach { (name, expected) ->
            val file = File(downloaded, name)
            check(file.isFile && file.length() == expected.bytes && sha256(file) == expected.sha256) {
                "Hindi speech download failed integrity check: $name"
            }
        }
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot prepare Hindi speech storage" }
        File(downloaded, marker).writeText(signature(checks))
        val backup = File(parent, ".hi_backup_${System.nanoTime()}")
        val backedUp = target.exists()
        if (backedUp) check(target.renameTo(backup)) { "Cannot preserve previous Hindi speech model" }
        if (!downloaded.renameTo(target)) {
            if (backedUp) check(backup.renameTo(target)) { "Cannot restore previous Hindi speech model" }
            throw IllegalStateException("Cannot activate downloaded Hindi speech model")
        }
        if (backedUp) backup.deleteRecursively()
    }

    private fun signature(checks: Map<String, FileCheck>) =
        checks.entries.joinToString("\n") { "${it.key}:${it.value.bytes}:${it.value.sha256}" }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isInstalled(dir: File, checks: Map<String, FileCheck>): Boolean =
        runCatching {
            File(dir, marker).readText() == signature(checks) &&
                checks.all { (name, check) -> File(dir, name).length() == check.bytes }
        }.getOrDefault(false)

    /** Verify all staged bytes before promoting the directory. Keep the old model on failure. */
    @Synchronized
    internal fun install(
        packsRoot: File,
        checks: Map<String, FileCheck> = files,
        open: (String) -> InputStream
    ) {
        val target = directory(packsRoot)
        if (isInstalled(target, checks)) return
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create STT model directory" }
        val staging = File(parent, ".hi_staging_${System.nanoTime()}")
        val backup = File(parent, ".hi_backup_${System.nanoTime()}")
        check(staging.mkdir())
        var backedUp = false
        try {
            checks.forEach { (name, expected) ->
                val digest = MessageDigest.getInstance("SHA-256")
                val destination = File(staging, name)
                open(name).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(destination.length() == expected.bytes && hash == expected.sha256) {
                    "Hindi STT model integrity check failed: $name"
                }
            }
            File(staging, marker).writeText(signature(checks))
            if (target.exists()) {
                check(target.renameTo(backup)) { "Cannot preserve previous Hindi STT model" }
                backedUp = true
            }
            check(staging.renameTo(target)) { "Cannot activate Hindi STT model" }
            if (backedUp) backup.deleteRecursively()
        } catch (failure: Exception) {
            if (backedUp && !target.exists()) backup.renameTo(target)
            throw failure
        } finally {
            staging.deleteRecursively()
        }
    }
}
