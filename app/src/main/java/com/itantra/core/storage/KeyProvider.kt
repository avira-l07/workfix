package com.itantra.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface KeyProvider {
    fun getOrCreate(alias: String): SecretKey
    fun getExisting(alias: String): SecretKey?
    fun delete(alias: String)
}

class AndroidKeyProvider : KeyProvider {
    companion object { private val creationLock = Any() }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun getExisting(alias: String) = store().getKey(alias, null) as? SecretKey
    override fun getOrCreate(alias: String): SecretKey = synchronized(creationLock) { getExisting(alias)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
            generateKey()
        }
    }
    override fun delete(alias: String) = store().deleteEntry(alias)
}
