package com.itantra.core.storage

import com.itantra.core.emergency.EmergencyPersistenceStore
import com.itantra.domain.model.EmergencyRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class EmergencyEncryptionTest {
    @get:Rule val folder = TemporaryFolder()
    private val keys = MemoryKeyProvider()
    private val record = EmergencyRecord(messageId = 7, emergencyCode = "HELP_REQUIRED", source = "LOCAL", createdAt = 1, resolvedPhrase = "PRIVATE SOS MARKER")
    private val file get() = File(folder.root, "emergency_records.json")
    @Test fun encryptedRoundTrip() {
        EmergencyPersistenceStore(folder.root, keys).saveRecord(record)
        assertTrue(StoredDataCipher.isEncrypted(file.readBytes()))
        assertFalse(file.readBytes().decodeToString().contains(record.resolvedPhrase))
        assertEquals(record, EmergencyPersistenceStore(folder.root, keys).getRecord(7))
    }
    @Test fun migratesLegacyJson() {
        file.writeText(Json.encodeToString(listOf(record)))
        assertEquals(record, EmergencyPersistenceStore(folder.root, keys).getRecord(7))
        assertTrue(StoredDataCipher.isEncrypted(file.readBytes()))
    }
    @Test fun tamperedFileRejected() {
        EmergencyPersistenceStore(folder.root, keys).saveRecord(record)
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        val reopened = EmergencyPersistenceStore(folder.root, keys)
        assertTrue(reopened.getAllRecords().isEmpty())
        assertTrue(reopened.unreadableData)
    }
    @Test fun validEncryptedTempRecoversCorruptMain() {
        EmergencyPersistenceStore(folder.root, keys).saveRecord(record)
        file.copyTo(File(folder.root, "emergency_records.json.tmp"))
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(record, EmergencyPersistenceStore(folder.root, keys).getRecord(7))
        assertTrue(StoredDataCipher.isEncrypted(file.readBytes()))
    }
    @Test fun encryptedTempSurvivesInterruptedFirstWrite() {
        EmergencyPersistenceStore(folder.root, keys).saveRecord(record)
        assertTrue(file.renameTo(File(folder.root, "emergency_records.json.tmp")))
        assertEquals(record, EmergencyPersistenceStore(folder.root, keys).getRecord(7))
        assertTrue(file.exists())
    }
    @Test fun legacyRecoveryCopySurvivesKeystoreWriteFailure() {
        val temp = File(folder.root, "emergency_records.json.tmp")
        temp.writeText(Json.encodeToString(listOf(record)))
        val unavailable = object : KeyProvider {
            override fun getExisting(alias: String): javax.crypto.SecretKey? = null
            override fun getOrCreate(alias: String): javax.crypto.SecretKey = throw IllegalStateException("Keystore unavailable")
            override fun delete(alias: String) = Unit
        }
        assertEquals(record, EmergencyPersistenceStore(folder.root, unavailable).getRecord(7))
        assertEquals(listOf(record), Json.decodeFromString<List<EmergencyRecord>>(file.readText()))
        assertEquals(record, EmergencyPersistenceStore(folder.root, keys).getRecord(7))
        assertTrue(StoredDataCipher.isEncrypted(file.readBytes()))
    }
}
