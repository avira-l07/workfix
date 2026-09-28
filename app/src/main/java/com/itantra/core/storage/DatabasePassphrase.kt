package com.itantra.core.storage

import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DatabasePassphrase(private val directory: File, keys: KeyProvider) {
    companion object { const val KEY_ALIAS = "itantra.storage.database.v1" }
    private val cipher = StoredDataCipher(keys, KEY_ALIAS)
    private val file = File(directory, "database.key")
    private val temp = File(directory, "database.key.tmp")

    @Synchronized fun loadOrCreate(encryptedDataExists: Boolean): ByteArray {
        try {
            if (file.exists()) return cipher.decrypt(file.readBytes()).also { require(it.size == 32) }
            if (temp.exists()) {
                val value = cipher.decrypt(temp.readBytes()).also { require(it.size == 32) }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return value
            }
            if (encryptedDataExists) throw StoredDataUnavailable()
            directory.mkdirs()
            val value = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                FileOutputStream(temp).use { it.write(cipher.encrypt(value)); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return value.copyOf()
            } finally { value.fill(0) }
        } catch (e: Exception) { throw StoredDataUnavailable(e) }
    }
}
