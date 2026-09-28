package com.itantra.core.storage

import org.junit.Assert.*
import org.junit.Test

class AndroidKeyProviderTest {
    @Test fun realKeystoreRoundTrip() {
        val provider = AndroidKeyProvider()
        val alias = "itantra.test.storage"
        try {
            assertNull(provider.getOrCreate(alias).encoded)
            val cipher = StoredDataCipher(provider, alias)
            assertEquals("secret", cipher.decrypt(cipher.encrypt("secret".toByteArray())).decodeToString())
        } finally { provider.delete(alias) }
    }
}
