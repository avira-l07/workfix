package com.itantra.core.storage

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivateContentTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun protectsIdentityAndMigratesOnlyOnce() {
        val content = PrivateContent(MemoryKeyProvider())
        val migrated = content.migrate("Private Operator")
        assertFalse(migrated.contains("Private Operator"))
        assertEquals("Private Operator", content.decode(migrated))
        assertEquals(migrated, content.migrate(migrated))
    }
    @Test fun diagnosticLegacyFileBecomesEncrypted() {
        val content = PrivateContent(MemoryKeyProvider())
        val file = File(folder.root, "benchmark.json")
        file.writeText("private transcript")
        assertEquals("private transcript", content.read(file))
        assertTrue(StoredDataCipher.isEncrypted(file.readBytes()))
        assertEquals("private transcript", content.read(file))
    }
}
