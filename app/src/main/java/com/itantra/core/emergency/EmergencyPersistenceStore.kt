package com.itantra.core.emergency

import com.itantra.domain.model.EmergencyRecord
import com.itantra.domain.model.EmergencyRetryStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.itantra.core.storage.AndroidKeyProvider
import com.itantra.core.storage.KeyProvider
import com.itantra.core.storage.StoredDataCipher

/**
 * Durable persistence store for emergency SOS messages.
 * Ensures unresolved emergency state survives activity recreation, process restart,
 * and temporary transport disconnects.
 *
 * Strict protocol rule: Transport ACK only confirms wire reception.
 * Only HUMAN_ACK or explicit operator action clears the unresolved emergency state.
 */
class EmergencyPersistenceStore(
    private val storageDir: File,
    keys: KeyProvider = AndroidKeyProvider()
) {
    companion object { const val KEY_ALIAS = "itantra.storage.emergency.v1" }
    private val cipher = StoredDataCipher(keys, KEY_ALIAS)
    var unreadableData: Boolean = false
        private set
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val storeFile = File(storageDir, "emergency_records.json")
    private val records = mutableMapOf<Long, EmergencyRecord>()
    private val lock = Any()

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        loadFromDisk()
    }

    fun saveRecord(record: EmergencyRecord) {
        synchronized(lock) {
            records[record.messageId] = record
            flushToDisk()
        }
    }

    fun getRecord(messageId: Long): EmergencyRecord? {
        synchronized(lock) {
            return records[messageId]
        }
    }

    fun getAllRecords(): List<EmergencyRecord> {
        synchronized(lock) {
            return records.values.toList()
        }
    }

    fun clearMemoryForWipe() = synchronized(lock) { records.clear() }

    fun getUnresolvedRecords(): List<EmergencyRecord> {
        synchronized(lock) {
            return records.values.filter { it.isUnresolved }
        }
    }

    /**
     * Records machine/transport wire ACK.
     * Crucially: ackStatus is set to true and retryStatus becomes TRANSPORT_ACKED,
     * but humanAckStatus remains false, so the record remains UNRESOLVED.
     */
    fun recordTransportAck(messageId: Long): EmergencyRecord? {
        synchronized(lock) {
            val existing = records[messageId] ?: return null
            val updated = existing.copy(
                ackStatus = true,
                retryStatus = if (existing.humanAckStatus) EmergencyRetryStatus.HUMAN_ACKED else EmergencyRetryStatus.TRANSPORT_ACKED
            )
            records[messageId] = updated
            flushToDisk()
            return updated
        }
    }

    /**
     * Records explicit human acknowledgement from an operator.
     * Sets humanAckStatus to true and retryStatus to HUMAN_ACKED.
     * This is the ONLY automatic protocol transition that resolves the emergency state.
     */
    fun recordHumanAck(messageId: Long): EmergencyRecord? {
        synchronized(lock) {
            val existing = records[messageId] ?: return null
            val updated = existing.copy(
                humanAckStatus = true,
                retryStatus = EmergencyRetryStatus.HUMAN_ACKED
            )
            records[messageId] = updated
            flushToDisk()
            return updated
        }
    }

    /**
     * Resolves all active unresolved emergency records (e.g. upon ALL_CLEAR reception).
     */
    fun resolveAllEmergencies(): List<EmergencyRecord> {
        synchronized(lock) {
            val resolvedList = mutableListOf<EmergencyRecord>()
            records.forEach { (id, record) ->
                if (record.isUnresolved) {
                    val updated = record.copy(
                        humanAckStatus = true,
                        retryStatus = EmergencyRetryStatus.HUMAN_ACKED
                    )
                    records[id] = updated
                    resolvedList.add(updated)
                }
            }
            if (resolvedList.isNotEmpty()) {
                flushToDisk()
            }
            return resolvedList
        }
    }

    /** Network ALL_CLEAR applies only to alerts originating from that verified peer. */
    fun resolveRemoteEmergencies(peerId: String): List<EmergencyRecord> = synchronized(lock) {
        if (peerId.isBlank()) return@synchronized emptyList()
        val resolved = records.values.filter { it.isUnresolved && it.source == "REMOTE" && it.peerId == peerId }
            .map { it.copy(humanAckStatus = true, retryStatus = EmergencyRetryStatus.HUMAN_ACKED) }
        resolved.forEach { records[it.messageId] = it }
        if (resolved.isNotEmpty()) flushToDisk()
        resolved
    }

    /**
     * Bounded retry accounting. Increments retry count and sets last attempt timestamp.
     */
    fun recordRetryAttempt(messageId: Long, success: Boolean, timestamp: Long): EmergencyRecord? {
        synchronized(lock) {
            val existing = records[messageId] ?: return null
            val nextCount = existing.retryCount + 1
            val newStatus = when {
                success -> EmergencyRetryStatus.SENT
                nextCount >= existing.maxRetries -> EmergencyRetryStatus.FAILED
                else -> existing.retryStatus
            }
            val updated = existing.copy(
                retryCount = nextCount,
                retryStatus = newStatus,
                lastAttempt = timestamp
            )
            records[messageId] = updated
            flushToDisk()
            return updated
        }
    }

    private fun flushToDisk() {
        try {
            val serialized = json.encodeToString(records.values.toList())
            val tempFile = File(storageDir, "emergency_records.json.tmp")
            val encrypted = cipher.encrypt(serialized.toByteArray(Charsets.UTF_8))
            FileOutputStream(tempFile).use { it.write(encrypted); it.fd.sync() }
            Files.move(tempFile.toPath(), storeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            android.util.Log.e("EmergencyStore", "Emergency storage write failed; previous file retained")
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            records.clear()
            val tempFile = File(storageDir, "emergency_records.json.tmp")
            for (targetFile in listOf(storeFile, tempFile)) {
                if (!targetFile.exists() || targetFile.length() == 0L) continue
                try {
                    val bytes = targetFile.readBytes()
                    val encrypted = StoredDataCipher.isEncrypted(bytes)
                    val content = if (encrypted) cipher.decrypt(bytes).decodeToString() else {
                        // Legacy JSON is an array. A damaged binary envelope is never treated as JSON.
                        require(bytes.toString(Charsets.UTF_8).trimStart().startsWith("["))
                        bytes.toString(Charsets.UTF_8)
                    }
                    val list = json.decodeFromString<List<EmergencyRecord>>(content)
                    list.forEach { records[it.messageId] = it }
                    unreadableData = false
                    // Promote the valid recovery copy before reusing the temporary filename.
                    // Otherwise a crash during legacy re-encryption could truncate the only copy.
                    if (targetFile == tempFile) Files.move(tempFile.toPath(), storeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    if (!encrypted) flushToDisk()
                    else if (tempFile.exists()) {
                        // The committed main file wins. Remove stale legacy/temp content.
                        if (!tempFile.delete()) android.util.Log.w("EmergencyStore", "Stale recovery file could not be removed")
                    }
                    return
                } catch (e: Exception) {
                    unreadableData = true
                    android.util.Log.e("EmergencyStore", "Emergency record file unreadable; trying recovery copy")
                }
            }
        }
    }
}
