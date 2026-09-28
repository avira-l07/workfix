package com.itantra.core.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Storage adapter for operator identity and diagnostic transcripts, never model assets. */
class PrivateContent(keys: KeyProvider = AndroidKeyProvider()) {
    companion object { const val KEY_ALIAS = "itantra.storage.content.v1"; private const val PREFIX = "itantra:encrypted:v1:" }
    private val cipher = StoredDataCipher(keys, KEY_ALIAS)
    fun encode(value: String): String = PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray()))
    fun decode(value: String): String = if (value.startsWith(PREFIX))
        cipher.decrypt(Base64.getDecoder().decode(value.removePrefix(PREFIX))).decodeToString() else value
    fun migrate(value: String): String = if (value.startsWith(PREFIX)) { decode(value); value } else encode(value)
    fun write(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        FileOutputStream(temp).use { it.write(cipher.encrypt(value.toByteArray())); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    fun read(file: File): String {
        val bytes = file.readBytes()
        return if (StoredDataCipher.isEncrypted(bytes)) cipher.decrypt(bytes).decodeToString()
        else bytes.decodeToString().also { write(file, it) }
    }
}
