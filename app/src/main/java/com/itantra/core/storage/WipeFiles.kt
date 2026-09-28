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
    private val keys: KeyProvider
) {
    fun wipe() {
        listOf("itantra.db", "itantra.encrypted.db", "itantra.encrypted.db.migrating").forEach { name ->
            listOf("", "-wal", "-shm", "-journal").forEach { remove(File(databases, name + it)) }
        }
        listOf("emergency_records.json", "emergency_records.json.tmp", "benchmarks").forEach { remove(File(files, it)) }
        listOf("itantra_device_profile", "lang_prefs").forEach { name ->
            remove(File(preferences, "$name.xml")); remove(File(preferences, "$name.xml.bak"))
        }
        remove(File(files, "datastore/itantra_settings.preferences_pb"))
        remove(File(files, "datastore/itantra_settings.preferences_pb.tmp"))
        remove(File(noBackup, "storage_keys"))
        for (alias in listOf(DatabasePassphrase.KEY_ALIAS, "itantra.storage.emergency.v1", PrivateContent.KEY_ALIAS)) keys.delete(alias)
        caches.forEach { cache -> cache.listFiles()?.filter { it.name != "silero_vad.onnx" }?.forEach(::remove) }
        externalFiles.forEach { remove(File(it, "debug_ptt.wav")) }
    }
    private fun remove(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        // Files.walk does not follow symbolic links.
        Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
