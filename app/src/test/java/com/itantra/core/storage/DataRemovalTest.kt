package com.itantra.core.storage

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

class DataRemovalTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `request round trips exactly and invalid requests never mean delete everything`() {
        val choices = setOf(DataRemovalChoice.MESSAGES, DataRemovalChoice.DIAGNOSTICS)
        assertEquals(choices, DataRemovalChoice.decode(DataRemovalChoice.encode(choices)))
        assertEquals(setOf(DataRemovalChoice.ALL_PRIVATE), DataRemovalChoice.decode(byteArrayOf(1)))
        listOf(byteArrayOf(), "unknown".toByteArray(), "MESSAGES,".toByteArray(), ByteArray(129)).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { DataRemovalChoice.decode(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { DataRemovalChoice.encode(emptySet()) }
    }

    @Test fun `selected SQL deletion removes live and recycled rows while preserving other tables`() {
        DriverManager.getConnection("jdbc:sqlite:${File(folder.root, "history.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                listOf("messages", "voice_notes", "peers").forEach { table ->
                    statement.execute("CREATE TABLE $table (id INTEGER PRIMARY KEY, text TEXT, deletedAtMillis INTEGER)")
                    statement.execute("INSERT INTO $table VALUES (1, 'live', NULL), (2, 'recycled', 1)")
                }
            }
            val database = wrapper(connection)
            HistoryDataRemoval.clear(database, setOf("messages"))
            assertEquals(0, count(connection, "messages"))
            assertEquals(2, count(connection, "voice_notes"))
            assertEquals(2, count(connection, "peers"))
            HistoryDataRemoval.clear(database, setOf("voice_notes"))
            assertEquals(0, count(connection, "voice_notes"))
            assertEquals(2, count(connection, "peers"))
            assertEquals(0L, File(folder.root, "history.db-wal").length())
            assertThrows(IllegalArgumentException::class.java) { HistoryDataRemoval.clear(database, setOf("peers")) }
            assertThrows(IllegalArgumentException::class.java) { HistoryDataRemoval.clear(database, setOf("messages; DROP TABLE peers")) }
            assertEquals(2, count(connection, "peers"))
        }
    }

    @Test fun `note deletion preserves messages emergencies keys profile and models`() {
        val keys = MemoryKeyProvider()
        val aliases = listOf(DatabasePassphrase.KEY_ALIAS, PrivateContent.KEY_ALIAS, "itantra.storage.emergency.v1")
        aliases.forEach { keys.getOrCreate(it) }
        val preserved = listOf("databases/itantra.encrypted.db", "files/emergency_records.json",
            "files/benchmarks/report.json", "prefs/itantra_device_profile.xml", "nobackup/storage_keys/database.key",
            "files/language_packs/hi/tts/model.onnx").map(::make)
        val calls = mutableListOf<Set<String>>()
        wiper(keys) { calls.add(it) }.wipe(setOf(DataRemovalChoice.VOICE_NOTES))
        assertEquals(listOf(setOf("voice_notes")), calls)
        preserved.forEach { assertTrue(it.path, it.exists()) }
        aliases.forEach { assertNotNull(keys.getExisting(it)) }
    }

    @Test fun `message deletion removes emergency copies but retains other private data`() {
        val keys = MemoryKeyProvider()
        keys.getOrCreate("itantra.storage.emergency.v1"); keys.getOrCreate(DatabasePassphrase.KEY_ALIAS)
        val removed = listOf("files/emergency_records.json", "files/emergency_records.json.tmp").map(::make)
        val retained = listOf("databases/itantra.encrypted.db", "prefs/itantra_device_profile.xml", "files/benchmarks/report.json").map(::make)
        val calls = mutableListOf<Set<String>>()
        wiper(keys) { calls.add(it) }.wipe(setOf(DataRemovalChoice.MESSAGES))
        assertEquals(listOf(setOf("messages")), calls)
        removed.forEach { assertFalse(it.exists()) }; retained.forEach { assertTrue(it.exists()) }
        assertNull(keys.getExisting("itantra.storage.emergency.v1"))
        assertNotNull(keys.getExisting(DatabasePassphrase.KEY_ALIAS))
    }

    @Test fun `diagnostics deletion is isolated and repeatable`() {
        val removed = listOf("files/benchmarks/report.json", "cache/debug_ptt.wav", "external/debug_ptt.wav").map(::make)
        val retained = listOf("databases/itantra.encrypted.db", "files/emergency_records.json", "prefs/itantra_device_profile.xml",
            "files/language_packs/hi/stt/model.onnx", "cache/silero_vad.onnx").map(::make)
        val wiper = wiper(MemoryKeyProvider()) { error("Diagnostics must not open history") }
        repeat(2) { wiper.wipe(setOf(DataRemovalChoice.DIAGNOSTICS)) }
        removed.forEach { assertFalse(it.exists()) }; retained.forEach { assertTrue(it.exists()) }
    }

    @Test fun `failed selective database cleanup does not erase unrelated files or encryption keys`() {
        val keys = MemoryKeyProvider().apply { getOrCreate("itantra.storage.emergency.v1") }
        val saved = make("files/emergency_records.json")
        assertThrows(IllegalStateException::class.java) {
            wiper(keys) { error("Database locked") }.wipe(setOf(DataRemovalChoice.MESSAGES))
        }
        assertTrue(saved.exists()); assertNotNull(keys.getExisting("itantra.storage.emergency.v1"))
    }

    private fun make(path: String) = File(folder.root, path).apply { parentFile?.mkdirs(); writeText("private") }
    private fun wiper(keys: KeyProvider, clear: (Set<String>) -> Unit) = WipeFiles(
        File(folder.root, "files"), File(folder.root, "databases"), File(folder.root, "prefs"), File(folder.root, "nobackup"),
        listOf(File(folder.root, "cache")), listOf(File(folder.root, "external")), keys, clear)
    private fun count(connection: Connection, table: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) }
    }
    private fun wrapper(connection: Connection): SupportSQLiteDatabase {
        var successful = false
        return Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            when (method.name) {
                "beginTransaction" -> { successful = false; connection.autoCommit = false; null }
                "setTransactionSuccessful" -> { successful = true; null }
                "endTransaction" -> { if (successful) connection.commit() else connection.rollback(); connection.autoCommit = true; null }
                "execSQL" -> { connection.createStatement().use { it.execute(args!![0] as String) }; null }
                "query" -> {
                    val statement = connection.createStatement()
                    val rows = statement.executeQuery(args!![0] as String)
                    Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, cursorMethod, cursorArgs ->
                        when (cursorMethod.name) {
                            "moveToFirst" -> rows.next()
                            "getInt" -> rows.getInt((cursorArgs!![0] as Int) + 1)
                            "close" -> { rows.close(); statement.close(); null }
                            else -> error("Unexpected cursor method ${cursorMethod.name}")
                        }
                    }
                }
                else -> error("Unexpected database method ${method.name}")
            }
        } as SupportSQLiteDatabase
    }
}
