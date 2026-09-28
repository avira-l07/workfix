package com.itantra.core.storage

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class StoredDataUnavailable(cause: Throwable? = null) : Exception("Stored data cannot be decrypted", cause)

class StoredDataCipher(private val keys: KeyProvider, private val alias: String) {
    companion object {
        private val MAGIC = byteArrayOf(73, 84, 69, 78, 67, 1)
        fun isEncrypted(bytes: ByteArray) = bytes.size >= MAGIC.size && bytes.take(MAGIC.size).toByteArray().contentEquals(MAGIC)
    }
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate(alias))
        cipher.updateAAD(MAGIC)
        return MAGIC + cipher.iv + cipher.doFinal(plain)
    }
    fun decrypt(bytes: ByteArray): ByteArray = try {
        require(isEncrypted(bytes) && bytes.size >= MAGIC.size + 12 + 16)
        val key = keys.getExisting(alias) ?: throw StoredDataUnavailable()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(MAGIC.size, MAGIC.size + 12)))
        cipher.updateAAD(MAGIC)
        cipher.doFinal(bytes, MAGIC.size + 12, bytes.size - MAGIC.size - 12)
    } catch (e: Exception) { throw StoredDataUnavailable(e) }
}
