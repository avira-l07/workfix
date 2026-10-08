package com.itantra.core.storage

import java.io.File
import java.nio.file.Files

/** Called only after the main process exits, so queued writers cannot recreate data. */
class WipeFiles(
    private val files: File,
    private val databases: File,
    private val preferences: File,
    private val noBackup: File,
    private val caches: List<File>,
    private val externalFiles: List<File>,
    private val keys: KeyProvider,
    private val clearHistory: (Set<String>) -> Unit = { error("History deletion requires an isolated database writer") }
) {
    fun wipe(choices: Set<DataRemovalChoice> = setOf(DataRemovalChoice.ALL_PRIVATE)) {
        require(choices.isNotEmpty())
        val all = DataRemovalChoice.ALL_PRIVATE in choices
        if (all) {
            listOf("itantra.db", "itantra.encrypted.db", "itantra.encrypted.db.migrating").forEach { name ->
                listOf("", "-wal", "-shm", "-journal").forEach { remove(File(databases, name + it)) }
            }
        } else {
            val tables = buildSet {
                if (DataRemovalChoice.MESSAGES in choices) add("messages")
                if (DataRemovalChoice.VOICE_NOTES in choices) add("voice_notes")
            }
            if (tables.isNotEmpty()) clearHistory(tables)
        }
        if (all || DataRemovalChoice.MESSAGES in choices) {
            listOf("emergency_records.json", "emergency_records.json.tmp").forEach { remove(File(files, it)) }
            keys.delete("itantra.storage.emergency.v1")
        }
        if (all) {
            listOf("itantra_device_profile", "lang_prefs").forEach { name ->
                remove(File(preferences, "$name.xml")); remove(File(preferences, "$name.xml.bak"))
            }
            remove(File(files, "datastore/itantra_settings.preferences_pb"))
            remove(File(files, "datastore/itantra_settings.preferences_pb.tmp"))
            remove(File(noBackup, "storage_keys"))
            for (alias in listOf(DatabasePassphrase.KEY_ALIAS, PrivateContent.KEY_ALIAS)) keys.delete(alias)
        }
        if (all || DataRemovalChoice.DIAGNOSTICS in choices) {
            remove(File(files, "benchmarks"))
            caches.forEach { cache -> cache.listFiles()?.filter { it.name != "silero_vad.onnx" }?.forEach(::remove) }
            externalFiles.forEach { remove(File(it, "debug_ptt.wav")) }
        }
    }
    private fun remove(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        // Files.walk does not follow symbolic links.
        Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
