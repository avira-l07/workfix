package com.itantra.data.languagepack

import android.content.Context
import com.itantra.core.inference.ModelFileSpecs
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

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

        return spec.requiredFiles.all { fileName ->
            val f = File(sttDir, fileName)
            f.exists() && f.length() > 0L
        }
    }

    override fun isTtsInstalled(code: LanguageCode): Boolean {
        val spec = ModelFileSpecs.getTtsSpec(code) ?: return false
        val ttsDir = File(packDirectory(code), "tts")
        if (!ttsDir.exists()) return false

        return spec.requiredFiles.all { fileName ->
            val f = File(ttsDir, fileName)
            f.exists() && f.length() > 0L
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

    /**
     * Atomically imports TTS model files for [code] from [srcTtsDir].
     * 1. Validates that [srcTtsDir] exists and is a directory.
     * 2. Validates that all required files (model.onnx, tokens.txt) exist and are non-empty.
     * 3. Stages files in a temporary staging directory.
     * 4. Atomically promotes staging to destination 'tts/' directory.
     * 5. Confirms that [isTtsInstalled] returns true.
     * If any check fails, cleans up staging and leaves existing files untouched.
     */
    fun importLanguageTts(code: LanguageCode, srcTtsDir: File): Boolean {
        if (!srcTtsDir.exists() || !srcTtsDir.isDirectory) return false
        val spec = ModelFileSpecs.getTtsSpec(code) ?: return false

        // 1. Validate all required files in source are present and non-empty
        for (requiredFile in spec.requiredFiles) {
            val f = File(srcTtsDir, requiredFile)
            if (!f.exists() || f.length() == 0L) {
                return false
            }
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

            // 3. Atomically replace destination
            val finalTtsDir = File(targetPackDir, "tts")
            if (finalTtsDir.exists()) {
                val backupDir = File(targetPackDir, ".tts_backup_${System.nanoTime()}")
                if (finalTtsDir.renameTo(backupDir)) {
                    if (stagingDir.renameTo(finalTtsDir)) {
                        backupDir.deleteRecursively()
                    } else {
                        backupDir.renameTo(finalTtsDir)
                        stagingDir.deleteRecursively()
                        return false
                    }
                } else {
                    finalTtsDir.deleteRecursively()
                    if (!stagingDir.renameTo(finalTtsDir)) {
                        stagingDir.copyRecursively(finalTtsDir, overwrite = true)
                        stagingDir.deleteRecursively()
                    }
                }
            } else {
                if (!stagingDir.renameTo(finalTtsDir)) {
                    stagingDir.copyRecursively(finalTtsDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
            }

            // 4. Verify post-condition
            return isTtsInstalled(code)
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return false
        }
    }

    /**
     * Atomically imports shared STT model files from [srcSttDir].
     */
    fun importSharedStt(srcSttDir: File): Boolean {
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

            if (dstStt.exists()) {
                val backupDir = File(parent, ".stt_backup_${System.nanoTime()}")
                if (dstStt.renameTo(backupDir)) {
                    if (stagingDir.renameTo(dstStt)) {
                        backupDir.deleteRecursively()
                    } else {
                        backupDir.renameTo(dstStt)
                        stagingDir.deleteRecursively()
                        return false
                    }
                } else {
                    dstStt.deleteRecursively()
                    if (!stagingDir.renameTo(dstStt)) {
                        stagingDir.copyRecursively(dstStt, overwrite = true)
                        stagingDir.deleteRecursively()
                    }
                }
            } else {
                if (!stagingDir.renameTo(dstStt)) {
                    stagingDir.copyRecursively(dstStt, overwrite = true)
                    stagingDir.deleteRecursively()
                }
            }

            return isSharedSttInstalled()
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return false
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

            // 3. Translation Models (optional)
            val srcMt = File(sourceDir, "mt")
            if (srcMt.exists()) {
                val dstMt = File(filesDir, "translation_models")
                dstMt.mkdirs()
                srcMt.copyRecursively(dstMt, overwrite = true)
                importedAny = true
            }

            importedAny
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
