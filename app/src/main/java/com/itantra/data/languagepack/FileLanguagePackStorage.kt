package com.itantra.data.languagepack

import android.content.Context
import com.itantra.core.inference.ModelFileSpecs
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import com.itantra.core.translation.OfflineTranslationModelManager

class FileLanguagePackStorage(
    private val filesDir: File
) : LanguagePackStorage {

    constructor(context: Context) : this(context.filesDir)

    private val packsDir = File(filesDir, "language_packs")

    init {
        if (!packsDir.exists()) {
            packsDir.mkdirs()
        }
    }

    override fun packDirectory(code: LanguageCode): File {
        return File(packsDir, code.wireCode)
    }

    override fun sharedSttDirectory(): File {
        return File(packsDir, "shared/stt")
    }

    override fun isSharedSttInstalled(): Boolean {
        val spec = ModelFileSpecs.getSttSpec(LanguageCode.HINDI)
        val sttDir = sharedSttDirectory()
        if (!sttDir.exists()) return false

        val filesPresent = spec.requiredFiles.all { fileName ->
            val f = File(sttDir, fileName)
            f.exists() && f.length() > 0L
        }
        if (!filesPresent) {
            // If files were deleted, invalidate verified marker
            val marker = File(sttDir, ".verified_v1")
            if (marker.exists()) marker.delete()
            return false
        }
        return true
    }

    override fun isTtsInstalled(code: LanguageCode): Boolean {
        val spec = ModelFileSpecs.getTtsSpec(code) ?: return false
        val ttsDir = File(packDirectory(code), "tts")
        if (!ttsDir.exists()) return false

        val filesPresent = spec.requiredFiles.all { fileName ->
            val f = File(ttsDir, fileName)
            f.exists() && f.length() > 0L
        }
        if (!filesPresent) {
            val marker = File(ttsDir, ".verified_v1")
            if (marker.exists()) marker.delete()
            return false
        }

        // Validate tokens.txt has readable non-empty token lines
        val tokensFile = File(ttsDir, "tokens.txt")
        if (tokensFile.exists()) {
            val hasValidTokens = try {
                tokensFile.useLines { lines ->
                    lines.any { it.trim().isNotEmpty() }
                }
            } catch (_: Throwable) {
                false
            }
            if (!hasValidTokens) {
                val marker = File(ttsDir, ".verified_v1")
                if (marker.exists()) marker.delete()
                return false
            }
        }

        return true
    }

    fun isSharedSttVerified(): Boolean {
        val sttDir = sharedSttDirectory()
        return isSharedSttInstalled() && File(sttDir, ".verified_v1").exists()
    }

    fun isTtsVerified(code: LanguageCode): Boolean {
        val ttsDir = File(packDirectory(code), "tts")
        return isTtsInstalled(code) && File(ttsDir, ".verified_v1").exists()
    }

    fun markSharedSttVerified(version: String = "1.0.0") {
        val sttDir = sharedSttDirectory()
        if (sttDir.exists()) {
            File(sttDir, ".verified_v1").writeText(version)
        }
    }

    fun markTtsVerified(code: LanguageCode, version: String = "1.0.0") {
        val ttsDir = File(packDirectory(code), "tts")
        if (ttsDir.exists()) {
            File(ttsDir, ".verified_v1").writeText(version)
        }
    }

    /**
     * A language pack is genuinely installed only when BOTH its shared STT
     * model and its per-language TTS model are present and non-empty.
     */
    override fun isInstalled(code: LanguageCode): Boolean {
        return isSharedSttInstalled() && isTtsInstalled(code)
    }

    override suspend fun verifyChecksums(
        code: LanguageCode,
        expectedChecksums: Map<String, String>
    ): Boolean {
        val dir = packDirectory(code)
        val ttsDir = File(dir, "tts")
        val sharedDir = sharedSttDirectory()

        for ((filename, expectedSha) in expectedChecksums) {
            val file = when {
                filename.startsWith("shared/stt/") || filename.startsWith("tiny-") -> {
                    val cleanName = filename.removePrefix("shared/stt/")
                    File(sharedDir, cleanName)
                }
                filename.startsWith("tts/") -> {
                    val cleanName = filename.removePrefix("tts/")
                    File(ttsDir, cleanName)
                }
                File(ttsDir, filename).exists() -> File(ttsDir, filename)
                File(sharedDir, filename).exists() -> File(sharedDir, filename)
                else -> File(dir, filename)
            }

            if (!file.exists() || file.length() == 0L) {
                return false
            }

            if (expectedSha.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { stream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    return false
                }
            }
        }

        if (ttsDir.exists()) {
            markTtsVerified(code)
        }
        if (expectedChecksums.keys.any { it.startsWith("shared/stt/") || it.startsWith("tiny-") }) {
            markSharedSttVerified()
        }
        return true
    }

    override suspend fun deletePack(code: LanguageCode) {
        val dir = packDirectory(code)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        // NOTE: Does NOT delete shared STT directory, keeping Whisper available for remaining languages.
    }

    override fun totalInstalledBytes(): Long {
        return packsDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun sha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Atomically imports TTS model files for [code] from [srcTtsDir] (FIX 036).
     * 1. Validates that [srcTtsDir] exists and is a directory.
     * 2. Validates that all required files exist and are non-empty.
     * 3. Stages files in a temporary staging directory.
     * 4. Verifies checksums if manifest is provided in src or expectedChecksums given.
     * 5. Atomically promotes staging to destination with backup and rollback.
     */
    fun importLanguageTts(
        code: LanguageCode,
        srcTtsDir: File,
        expectedChecksums: Map<String, String>? = null
    ): Boolean {
        if (!srcTtsDir.exists() || !srcTtsDir.isDirectory) return false
        val spec = ModelFileSpecs.getTtsSpec(code) ?: return false

        // 1. Validate all required files in source are present and non-empty
        for (requiredFile in spec.requiredFiles) {
            val f = File(srcTtsDir, requiredFile)
            if (!f.exists() || f.length() == 0L) {
                return false
            }
        }

        val srcTokens = File(srcTtsDir, "tokens.txt")
        if (srcTokens.exists()) {
            val hasValidTokens = try {
                srcTokens.useLines { lines -> lines.any { it.trim().isNotEmpty() } }
            } catch (_: Throwable) {
                false
            }
            if (!hasValidTokens) return false
        }

        val targetPackDir = packDirectory(code)
        if (!targetPackDir.exists()) targetPackDir.mkdirs()

        val stagingDir = File(targetPackDir, ".tts_staging_${System.nanoTime()}")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return false

        try {
            // 2. Copy files to staging
            for (requiredFile in spec.requiredFiles) {
                val srcFile = File(srcTtsDir, requiredFile)
                val destFile = File(stagingDir, requiredFile)
                srcFile.copyTo(destFile, overwrite = true)
                if (!destFile.exists() || destFile.length() == 0L) {
                    stagingDir.deleteRecursively()
                    return false
                }
            }

            // 3. Verify checksums against expectedChecksums or manifest if present
            val checksums = expectedChecksums ?: parseManifestChecksums(srcTtsDir)
            if (checksums != null) {
                for (requiredFile in spec.requiredFiles) {
                    val expectedSha = checksums[requiredFile]
                    if (!expectedSha.isNullOrEmpty()) {
                        val actualSha = sha256(File(stagingDir, requiredFile))
                        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                            stagingDir.deleteRecursively()
                            return false
                        }
                    }
                }
            }

            // Write verified marker into staging
            File(stagingDir, ".verified_v1").writeText("1.0.0")

            // 4. Atomically replace destination with backup and rollback
            val finalTtsDir = File(targetPackDir, "tts")
            val backupDir = File(targetPackDir, ".tts_backup_${System.nanoTime()}")
            var hadBackup = false

            if (finalTtsDir.exists()) {
                if (finalTtsDir.renameTo(backupDir)) {
                    hadBackup = true
                } else {
                    stagingDir.deleteRecursively()
                    return false
                }
            }

            val promoted = stagingDir.renameTo(finalTtsDir)
            if (promoted) {
                if (hadBackup) backupDir.deleteRecursively()
                return isTtsInstalled(code)
            } else {
                // Rollback
                if (hadBackup) backupDir.renameTo(finalTtsDir)
                stagingDir.deleteRecursively()
                return false
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return false
        }
    }

    /**
     * Atomically imports shared STT model files from [srcSttDir] (FIX 037).
     * Validates required files, verifies against trusted SHA-256 hashes,
     * and atomically promotes with backup and rollback.
     */
    fun importSharedStt(
        srcSttDir: File,
        expectedChecksums: Map<String, String>? = null
    ): Boolean {
        if (!srcSttDir.exists() || !srcSttDir.isDirectory) return false
        val spec = ModelFileSpecs.getSttSpec(LanguageCode.HINDI)

        for (requiredFile in spec.requiredFiles) {
            val f = File(srcSttDir, requiredFile)
            if (!f.exists() || f.length() == 0L) return false
        }

        val dstStt = sharedSttDirectory()
        val parent = dstStt.parentFile ?: return false
        if (!parent.exists()) parent.mkdirs()

        val stagingDir = File(parent, ".stt_staging_${System.nanoTime()}")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return false

        try {
            for (requiredFile in spec.requiredFiles) {
                val srcFile = File(srcSttDir, requiredFile)
                val destFile = File(stagingDir, requiredFile)
                srcFile.copyTo(destFile, overwrite = true)
                if (!destFile.exists() || destFile.length() == 0L) {
                    stagingDir.deleteRecursively()
                    return false
                }
            }

            // Verify checksums against expectedChecksums or trusted STT hashes
            val checksums = expectedChecksums ?: AssetLanguagePackStorage.TRUSTED_STT_SHA256
            for (requiredFile in spec.requiredFiles) {
                val expectedSha = checksums[requiredFile]
                if (!expectedSha.isNullOrEmpty()) {
                    val actualSha = sha256(File(stagingDir, requiredFile))
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        stagingDir.deleteRecursively()
                        return false
                    }
                }
            }

            File(stagingDir, ".verified_v1").writeText("1.0.0")

            val backupDir = File(parent, ".stt_backup_${System.nanoTime()}")
            var hadBackup = false

            if (dstStt.exists()) {
                if (dstStt.renameTo(backupDir)) {
                    hadBackup = true
                } else {
                    stagingDir.deleteRecursively()
                    return false
                }
            }

            val promoted = stagingDir.renameTo(dstStt)
            if (promoted) {
                if (hadBackup) backupDir.deleteRecursively()
                return isSharedSttInstalled()
            } else {
                if (hadBackup) dstStt.parentFile?.let { backupDir.renameTo(dstStt) }
                stagingDir.deleteRecursively()
                return false
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return false
        }
    }

    /**
     * Atomically imports translation models from [srcMt] directory with full
     * manifest, canonical path, and SHA-256 verification (FIX 038).
     */
    fun importTranslationModels(srcMt: File): Boolean {
        if (!srcMt.exists() || !srcMt.isDirectory) return false
        val dstMt = File(filesDir, "translation_models")
        val parent = dstMt.parentFile ?: return false
        if (!parent.exists()) parent.mkdirs()

        val stagingDir = File(parent, ".mt_staging_${System.nanoTime()}")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return false

        try {
            srcMt.copyRecursively(stagingDir, overwrite = true)

            // Validate both directions with OfflineTranslationModelManager
            val manager = OfflineTranslationModelManager(stagingDir)
            var validDirectionCount = 0

            for (dir in OfflineTranslationModelManager.Direction.values()) {
                val subDir = manager.modelDir(dir)
                if (subDir.exists()) {
                    val inspection = manager.inspectDirectory(subDir, dir)
                    if (inspection.status != OfflineTranslationModelManager.Status.READY) {
                        stagingDir.deleteRecursively()
                        return false
                    }
                    validDirectionCount++
                }
            }

            if (validDirectionCount == 0) {
                stagingDir.deleteRecursively()
                return false
            }

            val backupDir = File(parent, ".mt_backup_${System.nanoTime()}")
            var hadBackup = false

            if (dstMt.exists()) {
                if (dstMt.renameTo(backupDir)) {
                    hadBackup = true
                } else {
                    stagingDir.deleteRecursively()
                    return false
                }
            }

            val promoted = stagingDir.renameTo(dstMt)
            if (promoted) {
                if (hadBackup) backupDir.deleteRecursively()
                return true
            } else {
                if (hadBackup) backupDir.renameTo(dstMt)
                stagingDir.deleteRecursively()
                return false
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return false
        }
    }

    private fun parseManifestChecksums(dir: File): Map<String, String>? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return null
        return try {
            val json = manifestFile.readText()
            LanguagePackManifestParser.parseOrNull(json)?.ttsModel?.checksumsSha256
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sideload / Import pre-provisioned models from a local directory.
     * Supports both full language_packs tree (with shared/stt and <wireCode>/tts)
     * and single language directories.
     */
    fun importFromDirectory(sourceDir: File): Boolean {
        return try {
            if (!sourceDir.exists()) return false
            var importedAny = false

            // 1. Shared STT
            val srcStt = File(sourceDir, "shared/stt")
            if (srcStt.exists() && importSharedStt(srcStt)) {
                importedAny = true
            }

            // 2. Language TTS (e.g. sourceDir/en/tts or sourceDir/hi/tts)
            for (code in LanguageCode.entries) {
                val directTts = File(sourceDir, "${code.wireCode}/tts")
                val flatTts = File(sourceDir, code.wireCode)
                if (directTts.exists() && directTts.isDirectory) {
                    if (importLanguageTts(code, directTts)) importedAny = true
                } else if (flatTts.exists() && File(flatTts, "model.onnx").exists()) {
                    if (importLanguageTts(code, flatTts)) importedAny = true
                }
            }

            // 3. Translation Models (validated via FIX 038)
            val srcMt = File(sourceDir, "mt")
            if (srcMt.exists()) {
                if (importTranslationModels(srcMt)) {
                    importedAny = true
                }
            }

            importedAny
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
