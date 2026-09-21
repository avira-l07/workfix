package com.itantra.data.languagepack

import android.content.Context
import android.util.Log
import com.itantra.core.inference.ModelFileSpecs
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import java.io.File
import java.io.FileOutputStream

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
    }

    init {
        extractBundledAssetsIfNeeded()
    }

    /**
     * Extracts bundled STT model files from assets/language_packs/shared/stt/
     * into context.filesDir/language_packs/shared/stt/ if not already present or incomplete.
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
                    if (!destFile.exists() || destFile.length() == 0L) {
                        Log.i(TAG, "Extracting bundled asset: $assetPath -> ${destFile.absolutePath}")
                        copyAssetToFile(assetPath, destFile)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting bundled language pack assets", e)
        }
    }

    private fun copyAssetToFile(assetPath: String, destFile: File) {
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tmpFile).use { output ->
                input.copyTo(output)
            }
        }
        if (destFile.exists()) {
            destFile.delete()
        }
        tmpFile.renameTo(destFile)
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
