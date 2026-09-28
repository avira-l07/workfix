package com.itantra.core.storage

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class MemoryKeyProvider : KeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()
    override fun getExisting(alias: String) = keys[alias]
    override fun getOrCreate(alias: String) = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    override fun delete(alias: String) { keys.remove(alias) }
}

class DatabasePassphraseTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun persistsWrappedRandomPassphrase() {
        val keys = MemoryKeyProvider()
        val store = DatabasePassphrase(temp.root, keys)
        val pass = store.loadOrCreate(false)
        assertEquals(32, pass.size)
        assertArrayEquals(pass, DatabasePassphrase(temp.root, keys).loadOrCreate(true))
        assertFalse(java.io.File(temp.root, "database.key").readBytes().contentEquals(pass))
    }
    @Test fun lostKeyDoesNotReplaceExistingData() {
        val keys = MemoryKeyProvider()
        val store = DatabasePassphrase(temp.root, keys)
        store.loadOrCreate(false)
        keys.delete(DatabasePassphrase.KEY_ALIAS)
        assertThrows(StoredDataUnavailable::class.java) { store.loadOrCreate(true) }
        assertNull(keys.getExisting(DatabasePassphrase.KEY_ALIAS))
    }
    @Test fun missingWrapperWithEncryptedDataFails() {
        assertThrows(StoredDataUnavailable::class.java) { DatabasePassphrase(temp.root, MemoryKeyProvider()).loadOrCreate(true) }
    }
    @Test fun authenticatedEncryptionRejectsTamperingAndUsesFreshIv() {
        val cipher = StoredDataCipher(MemoryKeyProvider(), "test")
        val a = cipher.encrypt("secret".toByteArray())
        assertFalse(a.contentEquals(cipher.encrypt("secret".toByteArray())))
        assertEquals("secret", cipher.decrypt(a).decodeToString())
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        assertThrows(StoredDataUnavailable::class.java) { cipher.decrypt(a) }
    }
}
