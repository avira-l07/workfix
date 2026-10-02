package com.itantra.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.DriverManager

class PeerMigrationTest {
    @Test
    fun migration4To5PreservesStoredNotesAndAddsPeers() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            val schema = JSONObject(File("schemas/com.itantra.data.db.AppDatabase/4.json").readText())
                .getJSONObject("database").getJSONArray("entities")
            conn.createStatement().use { statement ->
                for (index in 0 until schema.length()) {
                    val entity = schema.getJSONObject(index)
                    statement.execute(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                statement.execute("INSERT INTO voice_notes (transcribedText, languageWireCode, createdAtMillis) VALUES ('saved note', 'hi', 123)")
            }
            val support = Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL") {
                    conn.createStatement().use { it.execute(args[0] as String) }
                }
                null
            } as SupportSQLiteDatabase

            AppDatabase.MIGRATION_4_5.migrate(support)

            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT transcribedText FROM voice_notes").use { result ->
                    assertTrue(result.next())
                    assertEquals("saved note", result.getString(1))
                }
                statement.execute("INSERT INTO peers (deviceId, displayName, transportAddress, transportName, lastSeenMillis) VALUES ('IT-A', 'Aarav', '', 'Direct', 456)")
                statement.executeQuery("SELECT COUNT(*) FROM peers").use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }
}
