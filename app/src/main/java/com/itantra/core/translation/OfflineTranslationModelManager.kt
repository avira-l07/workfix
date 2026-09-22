package com.itantra.core.translation

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Verifies local Hindi<->English translation model folders before they are loaded.
 *
 * Expected directories:
 *   <baseDir>/indic-en
 *   <baseDir>/en-indic
 *
 * The actual translation runtime remains CTranslate2TranslationEngine.
 * This class only protects the app from missing/corrupt model files.
 */
class OfflineTranslationModelManager(
    private val baseDir: File
) {
    enum class Direction(val folderName: String, val manifestValue: String) {
        HINDI_TO_ENGLISH("indic-en", "hi-en"),
        ENGLISH_TO_HINDI("en-indic", "en-hi")
    }

    enum class Status {
        MISSING,
        INVALID,
        READY
    }

    data class Inspection(
        val direction: Direction,
        val status: Status,
        val reason: String? = null,
        val modelDir: File
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun modelDir(direction: Direction): File =
        File(baseDir, direction.folderName)

    fun inspect(direction: Direction): Inspection =
        inspectDirectory(modelDir(direction), direction)

    fun inspectDirectory(dir: File, direction: Direction): Inspection {
        if (!dir.exists() || !dir.isDirectory) {
            return Inspection(direction, Status.MISSING, "MODEL_DIRECTORY_MISSING", dir)
        }

        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists() || manifestFile.length() == 0L) {
            return Inspection(direction, Status.INVALID, "MANIFEST_MISSING", dir)
        }

        val manifest = try {
            json.decodeFromString(
                TranslationModelManifest.serializer(),
                manifestFile.readText()
            )
        } catch (_: Exception) {
            return Inspection(direction, Status.INVALID, "MANIFEST_INVALID", dir)
        }

        if (!manifest.direction.equals(direction.manifestValue, ignoreCase = true)) {
            return Inspection(direction, Status.INVALID, "DIRECTION_MISMATCH", dir)
        }

        if (!manifest.runtime.equals("ctranslate2", ignoreCase = true)) {
            return Inspection(direction, Status.INVALID, "UNSUPPORTED_RUNTIME_${manifest.runtime}", dir)
        }

        if (manifest.requiredFiles.isEmpty()) {
            return Inspection(direction, Status.INVALID, "NO_REQUIRED_FILES", dir)
        }

        val canonicalDir = try {
            dir.canonicalFile
        } catch (_: Exception) {
            return Inspection(direction, Status.INVALID, "CANONICAL_PATH_ERROR", dir)
        }
        val canonicalDirPath = canonicalDir.path.trimEnd(File.separatorChar) + File.separator

        val seenPaths = mutableSetOf<String>()
        for (relativePath in manifest.requiredFiles) {
            if (relativePath.isBlank() || relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.contains("..")) {
                return Inspection(direction, Status.INVALID, "UNSAFE_PATH_$relativePath", dir)
            }
            if (File(relativePath).isAbsolute) {
                return Inspection(direction, Status.INVALID, "ABSOLUTE_PATH_$relativePath", dir)
            }
            val normalized = relativePath.replace('\\', '/')
            if (!seenPaths.add(normalized)) {
                return Inspection(direction, Status.INVALID, "DUPLICATE_FILE_$relativePath", dir)
            }
        }

        for (relativePath in manifest.requiredFiles) {
            val file = File(canonicalDir, relativePath)
            val canonicalChild = try {
                file.canonicalFile
            } catch (_: Exception) {
                return Inspection(direction, Status.INVALID, "CANONICAL_PATH_ERROR_$relativePath", dir)
            }

            if (!canonicalChild.path.startsWith(canonicalDirPath)) {
                return Inspection(direction, Status.INVALID, "PATH_ESCAPE_$relativePath", dir)
            }

            if (!canonicalChild.exists() || !canonicalChild.isFile || canonicalChild.length() <= 0L) {
                return Inspection(direction, Status.INVALID, "FILE_MISSING_$relativePath", dir)
            }

            val expected = manifest.sha256[relativePath]
                ?.trim()
                ?.lowercase()
                ?: return Inspection(direction, Status.INVALID, "CHECKSUM_MISSING_$relativePath", dir)

            if (expected.length != 64 || expected.any { it !in "0123456789abcdef" }) {
                return Inspection(direction, Status.INVALID, "CHECKSUM_INVALID_$relativePath", dir)
            }

            val actual = sha256(canonicalChild)
            if (!actual.equals(expected, ignoreCase = true)) {
                return Inspection(direction, Status.INVALID, "CHECKSUM_MISMATCH_$relativePath", dir)
            }
        }

        return Inspection(direction, Status.READY, null, dir)
    }

    fun isReady(direction: Direction): Boolean =
        inspect(direction).status == Status.READY

    fun hindiToEnglishStatus(): Inspection =
        inspect(Direction.HINDI_TO_ENGLISH)

    fun englishToHindiStatus(): Inspection =
        inspect(Direction.ENGLISH_TO_HINDI)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
