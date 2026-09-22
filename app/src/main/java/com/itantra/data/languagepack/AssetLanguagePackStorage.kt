package com.itantra.data.languagepack

import android.content.Context
import android.util.Log
import com.itantra.core.inference.ModelFileSpecs
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Storage implementation that extracts bundled STT models from APK assets
 * into app-private storage on startup, enabling out-of-the-box PTT capability.
 */
class AssetLanguagePackStorage(
    private val context: Context,
    private val delegate: FileLanguagePackStorage = FileLanguagePackStorage(context)
) : LanguagePackStorage by delegate {

    companion object {
        private const val TAG = "AssetLangStorage"

        val TRUSTED_STT_SHA256 = mapOf(
            "tiny-encoder.int8.onnx" to "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            "tiny-decoder.int8.onnx" to "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            "tiny-tokens.txt" to "c99891a107067b649a03fdf23c4b475fd0ad981da7f9aae84e72fb9f2d53c785"
        )
    }

    init {
        extractBundledAssetsIfNeeded()
    }

    private fun getExpectedSttSha256(fileName: String): String? {
        return try {
            val json = context.assets.open("language_packs/hi_dev_manifest.json")
                .bufferedReader().use { it.readText() }
            LanguagePackManifestParser.parseOrNull(json)?.sttModel?.checksumsSha256?.get(fileName)
                ?: TRUSTED_STT_SHA256[fileName]
        } catch (_: Exception) {
            TRUSTED_STT_SHA256[fileName]
        }
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
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Extracts bundled STT model files from assets/language_packs/shared/stt/
     * into context.filesDir/language_packs/shared/stt/ if not already present or incomplete,
     * validating SHA-256 integrity against trusted manifest hashes (FIX 033).
     */
    fun extractBundledAssetsIfNeeded() {
        try {
            val spec = ModelFileSpecs.getSttSpec(LanguageCode.HINDI)
            val targetDir = delegate.sharedSttDirectory()
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            for (fileName in spec.requiredFiles) {
                val destFile = File(targetDir, fileName)
                val assetPath = "language_packs/shared/stt/$fileName"

                // Check if asset exists in APK
                val assetExists = try {
                    context.assets.open(assetPath).use { true }
                } catch (e: Exception) {
                    false
                }

                if (assetExists) {
                    val expectedSha = getExpectedSttSha256(fileName)
                    val isExistingValid = if (destFile.exists() && destFile.length() > 0L) {
                        if (expectedSha != null) {
                            sha256(destFile).equals(expectedSha, ignoreCase = true)
                        } else {
                            true
                        }
                    } else {
                        false
                    }

                    if (!isExistingValid) {
                        Log.i(TAG, "Extracting bundled asset: $assetPath -> ${destFile.absolutePath}")
                        copyAssetToFile(assetPath, destFile, expectedSha)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting bundled language pack assets", e)
        }
    }

    /**
     * Copies an APK asset to destFile via a verified staging temp file.
     * Checks renameTo() and falls back to streams with fsync and hash verification (FIX 034).
     */
    private fun copyAssetToFile(assetPath: String, destFile: File, expectedSha: String?) {
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp_${System.nanoTime()}")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            if (expectedSha != null) {
                val actualSha = sha256(tmpFile)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    Log.e(TAG, "Extracted asset SHA-256 mismatch for $assetPath. Expected $expectedSha, got $actualSha")
                    tmpFile.delete()
                    return
                }
            }

            // Attempt renameTo first
            if (destFile.exists()) {
                destFile.delete()
            }
            val renamed = tmpFile.renameTo(destFile)
            if (!renamed) {
                // Fallback: copy with fsync and verify hash
                tmpFile.inputStream().buffered().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (expectedSha != null) {
                    val finalSha = sha256(destFile)
                    if (!finalSha.equals(expectedSha, ignoreCase = true)) {
                        Log.e(TAG, "Fallback copy of $destFile failed hash check")
                        destFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetPath to $destFile", e)
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    override fun isInstalled(code: LanguageCode): Boolean {
        // A language is usable for PTT voice transceiver if shared multilingual STT is installed,
        // or if both STT and TTS are installed.
        return isSharedSttInstalled() || delegate.isInstalled(code)
    }

    override fun isSharedSttInstalled(): Boolean {
        return delegate.isSharedSttInstalled()
    }

    override fun isTtsInstalled(code: LanguageCode): Boolean {
        return delegate.isTtsInstalled(code)
    }

    fun importLanguageTts(code: LanguageCode, srcTtsDir: File): Boolean {
        return delegate.importLanguageTts(code, srcTtsDir)
    }

    fun importSharedStt(srcSttDir: File): Boolean {
        return delegate.importSharedStt(srcSttDir)
    }

    fun importFromDirectory(sourceDir: File): Boolean {
        return delegate.importFromDirectory(sourceDir)
    }
}
