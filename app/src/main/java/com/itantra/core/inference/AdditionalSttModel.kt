package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.io.File
import java.security.MessageDigest

/** Pinned, independently downloadable CTC recognizers selected in manual mic mode. */
object AdditionalSttModel {
    private const val baseUrl = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/9721eb71eea141fae0982cfcdb9dd2e3d4953c4a"

    data class FileCheck(val bytes: Long, val sha256: String)

    data class Model(
        val language: LanguageCode,
        val relativePath: String,
        val displayName: String,
        val remoteFolder: String,
        val files: Map<String, FileCheck>,
    ) {
        val totalBytes: Long get() = files.values.sumOf { it.bytes }
        fun directory(packsRoot: File): File = File(packsRoot, relativePath)
        fun downloadUrlFor(name: String): String {
            val remote = if (name == "tokens.txt" && language != LanguageCode.ENGLISH) name
                else "$remoteFolder/$name"
            return "$baseUrl/$remote"
        }
        fun spec() = ModelFileSpec(
            type = ModelFileSpec.EngineType.STT,
            languageCode = language,
            requiredFiles = files.keys.toList(),
            mainModelFile = "model.int8.onnx",
            tokensFile = "tokens.txt",
            isShared = true,
            sharedPath = relativePath,
        )
    }

    private val indicTokens = FileCheck(67605, "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2")
    private val models = mapOf(
        LanguageCode.HINDI to Model(
            LanguageCode.HINDI, "shared/stt-hi-ctc-v2", "IndicConformer Hindi INT8", "hi",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595593, "915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.ENGLISH to Model(
            LanguageCode.ENGLISH, "shared/stt-en-ctc-v2", "NeMo FastConformer English INT8", "en",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(174610057, "28b9261a53028a7c99ff0799f44fb53f19c78b68cc4cf40637ac9c16cb1fbc6f"),
                "tokens.txt" to FileCheck(11433, "89c165b98df7af718ec0e872177279bfad4ade51331f1be92753c9583a1ef30d"),
            ),
        ),
        LanguageCode.TAMIL to Model(
            LanguageCode.TAMIL, "shared/stt-ta-ctc-v2", "IndicConformer Tamil INT8", "ta",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595513, "abb7b59d706b8d27ba3fb5e5e3db7671c9e1a09bf7bc6122de507c60030e65fb"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.TELUGU to Model(
            LanguageCode.TELUGU, "shared/stt-te-ctc-v2", "IndicConformer Telugu INT8", "te",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595693, "710f176e33080f1be84b4f38bb5f33be3f522dcf4f191bf4d5cc89f7a85924f3"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.ODIA to Model(
            LanguageCode.ODIA, "shared/stt-or-ctc-v2", "IndicConformer Odia INT8 · device unverified", "or",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197584928, "31730e06bd186bca5c3214003c2a0b5eeb3234d079b5b81aa72547adbe1c9be7"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.BENGALI to Model(
            LanguageCode.BENGALI, "shared/stt-bn-ctc-v2", "IndicConformer Bengali INT8 · device unverified", "bn",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595578, "e9120a534f69df065314be468bf15579f1b92a4cd8c07ad119b80b69244718a8"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.GUJARATI to Model(
            LanguageCode.GUJARATI, "shared/stt-gu-ctc-v2", "IndicConformer Gujarati INT8 · device unverified", "gu",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595461, "822ed7f0b809bbd479275bf91c913d05564b88c0d082bbcba2f37999b88cb598"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.MARATHI to Model(
            LanguageCode.MARATHI, "shared/stt-mr-ctc-v2", "IndicConformer Marathi INT8 · device unverified", "mr",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595593, "1ea81e55c4b9b12624c9d02a5b9c1b6f7c871c78a55ff52d333f81cb5136eaf2"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.MALAYALAM to Model(
            LanguageCode.MALAYALAM, "shared/stt-ml-ctc-v2", "IndicConformer Malayalam INT8 · experimental · device unverified", "ml",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595555, "dcbdfa9f773db910508b40b703cb76c5974e8d4c6f123ea81265b40853c3f0c2"),
                "tokens.txt" to indicTokens,
            ),
        ),
        LanguageCode.KANNADA to Model(
            LanguageCode.KANNADA, "shared/stt-kn-ctc-v2", "IndicConformer Kannada INT8 · device unverified", "kn",
            linkedMapOf(
                "model.int8.onnx" to FileCheck(197595728, "b226ce7e4ea35b0dd66991964bd00e011b6b14b0fcdf4f7d1cccd777781c94dc"),
                "tokens.txt" to indicTokens,
            ),
        ),
    )

    fun forLanguage(language: LanguageCode, autoDetect: Boolean = false): Model? =
        if (autoDetect) null else models[language]

    fun isInstalled(packsRoot: File, model: Model): Boolean = runCatching {
        val directory = model.directory(packsRoot)
        File(directory, marker(model)).readText() == signature(model) &&
            model.files.all { (name, expected) -> File(directory, name).length() == expected.bytes }
    }.getOrDefault(false)

    /** Validate every file, then promote the complete directory; preserve an older install on failure. */
    @Synchronized
    fun installDownloaded(packsRoot: File, model: Model, downloaded: File) {
        if (isInstalled(packsRoot, model)) return
        require(downloaded.isDirectory) { "${model.language} speech download is missing" }
        model.files.forEach { (name, expected) ->
            val file = File(downloaded, name)
            check(file.isFile && file.length() == expected.bytes && sha256(file) == expected.sha256) {
                "${model.language} speech download failed integrity check: $name"
            }
        }
        val target = model.directory(packsRoot)
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot prepare speech model storage" }
        File(downloaded, marker(model)).writeText(signature(model))
        val backup = File(parent, ".${model.language.wireCode}_backup_${System.nanoTime()}")
        val backedUp = target.exists()
        if (backedUp) check(target.renameTo(backup)) { "Cannot preserve previous speech model" }
        if (!downloaded.renameTo(target)) {
            if (backedUp) check(backup.renameTo(target)) { "Cannot restore previous speech model" }
            throw IllegalStateException("Cannot activate ${model.language} speech model")
        }
        if (backedUp) backup.deleteRecursively()
    }

    private fun marker(model: Model) = ".verified_${model.language.wireCode}_ctc_v2"
    private fun signature(model: Model) =
        model.files.entries.joinToString("\n") { "${it.key}:${it.value.bytes}:${it.value.sha256}" }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
