package com.itantra.core.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WipeFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun make(path: String): File = File(folder.root, path).apply { parentFile?.mkdirs(); writeText("private") }
    @Test fun wipeRemovesSensitiveFilesAndKeysButPreservesModels() {
        val keys = MemoryKeyProvider()
        val aliases = listOf(DatabasePassphrase.KEY_ALIAS, "itantra.storage.emergency.v1", PrivateContent.KEY_ALIAS)
        aliases.forEach { keys.getOrCreate(it) }
        val privateFiles = listOf("databases/itantra.encrypted.db", "databases/itantra.encrypted.db-wal", "databases/itantra.encrypted.db-shm",
            "databases/itantra.db", "databases/itantra.db-journal", "files/emergency_records.json", "files/emergency_records.json.tmp",
            "files/benchmarks/result.json", "files/datastore/itantra_settings.preferences_pb", "prefs/itantra_device_profile.xml",
            "prefs/lang_prefs.xml", "nobackup/storage_keys/database.key", "cache/audio.tmp", "external/debug_ptt.wav").map(::make)
        val preserved = listOf("files/language_packs/hi/tts/model.onnx", "files/translation_models/model.bin",
            "nobackup/com.google.mlkit.translate.models/hi/model", "cache/silero_vad.onnx").map(::make)
        val wiper = WipeFiles(File(folder.root, "files"), File(folder.root, "databases"), File(folder.root, "prefs"),
            File(folder.root, "nobackup"), listOf(File(folder.root, "cache")), listOf(File(folder.root, "external")), keys)
        wiper.wipe()
        privateFiles.forEach { assertFalse(it.path, it.exists()) }
        aliases.forEach { assertNull(keys.getExisting(it)) }
        preserved.forEach { assertTrue(it.path, it.exists()) }
        wiper.wipe() // interrupted/duplicate requests can safely retry
    }
    @Test fun deletingKeysMakesCapturedCiphertextUnreadable() {
        val keys = MemoryKeyProvider()
        val cipher = StoredDataCipher(keys, "itantra.storage.emergency.v1")
        val saved = cipher.encrypt("private SOS".toByteArray())
        WipeFiles(folder.root, folder.root, folder.root, folder.root, emptyList(), emptyList(), keys).wipe()
        assertThrows(StoredDataUnavailable::class.java) { cipher.decrypt(saved) }
    }
}
