package com.itantra.core.inference

import java.io.File
import java.util.zip.ZipInputStream

/** Pinned Marathi Piper voice; download-time preparation only, never a runtime API. */
internal object MarathiPiperVoice {
    const val MODEL_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/c10ece1aade47bb51c153c893d14e5bf8e5b7117/mr/mr_IN/google/medium/mr_IN-google-medium.onnx"
    const val SOURCE_SHA256 = "e1200d474a74ebd6d1737be2c7affe56f1f9efc18915d4595d7f5c2b15cf06f4"
    const val FRONTEND_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.zip"
    const val FRONTEND_SHA256 = "bc4525eafe31b4e3f5e43aea495f3169e97dd2544f1bbfe95514ce8a61baee39"
    const val TOKENS_ASSET = "language_packs/mr/piper-tokens.txt"
    const val DOWNLOAD_BYTES = 85_805_199L
    val requiredDataFiles = listOf("phontab", "phonindex", "phondata", "intonations", "mr_dict", "lang/inc/mr")
        .map { "espeak-ng-data/$it" }

    // Official Sherpa conversion metadata, encoded as ONNX ModelProto field 14.
    // Appending these 128 bytes leaves the checksum-verified trained graph/weights intact.
    internal val metadataSuffix: ByteArray get() = (
        "72120a0a6d6f64656c5f7479706512047669747372100a07636f6d6d656e7412057069706572" +
        "72130a086c616e677561676512074d617261746869720b0a05766f69636512026d72" +
        "720f0a0a6861735f65737065616b120131720f0a0a6e5f737065616b657273120139" +
        "72140a0b73616d706c655f7261746512053232303530"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun extractFrontend(archive: File, destination: File) {
        val root = destination.canonicalFile
        val seen = mutableSetOf<String>()
        var extractedBytes = 0L
        var entries = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++entries <= 2048) { "Too many Marathi frontend files" }
                val name = entry.name
                check(name.startsWith("espeak-ng-data/") && '\\' !in name &&
                    name.split('/').none { it == ".." || it == "." }) { "Unsafe Marathi frontend path" }
                val target = File(root, name).canonicalFile
                check(target.toPath().startsWith(root.toPath())) { "Marathi frontend escapes staging" }
                if (!entry.isDirectory) {
                    check(seen.add(name) && !target.exists()) { "Duplicate Marathi frontend file" }
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extractedBytes += count
                            check(extractedBytes <= 25_000_000L) { "Marathi frontend exceeds size limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        check(requiredDataFiles.all { File(root, it).let { f -> f.isFile && f.length() > 0 } }) {
            "Marathi phoneme data is incomplete; download the voice again"
        }
    }
}
