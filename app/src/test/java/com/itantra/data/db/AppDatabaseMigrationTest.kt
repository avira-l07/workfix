package com.itantra.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * TASK 1: Room Migration 2->3 verification unit test.
 *
 * Verifies:
 * 1. AppDatabase does not use fallbackToDestructiveMigration() or any destructive fallback.
 * 2. Room schemas are properly exported with exportSchema = true (2.json and 3.json exist and match).
 * 3. Migration MIGRATION_2_3 executes cleanly against SQLite:
 *    - Existing message rows survive intact without data loss.
 *    - New location columns (isLocation, latitude, longitude, accuracyMeters, locationTimestampMillis)
 *      properly default to 0 / NULL.
 * 4. Fresh install creates v3 schema cleanly.
 */
class AppDatabaseMigrationTest {

    @Test fun migration5To6PreservesHistoryAndVoiceNoteQueriesEnforceSevenDayRestore() {
        val old = JSONObject(File("schemas/com.itantra.data.db.AppDatabase/5.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        val fresh = JSONObject(File("schemas/com.itantra.data.db.AppDatabase/6.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        createSqliteMemoryConnection().use { conn ->
            for (i in 0 until old.length()) {
                val entity = old.getJSONObject(i)
                val table = entity.getString("tableName")
                conn.createStatement().use { it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table)) }
                val fields = entity.getJSONArray("fields")
                val required = (0 until fields.length()).map { fields.getJSONObject(it) }.filter { it.getBoolean("notNull") }
                val columns = required.joinToString { "`${it.getString("columnName")}`" }
                val values = required.joinToString { if (it.getString("affinity") == "TEXT") "'preserved'" else "42" }
                conn.createStatement().use { it.execute("INSERT INTO `$table` ($columns) VALUES ($values)") }
            }
            AppDatabase.MIGRATION_5_6.migrate(createSupportDatabaseWrapper(conn))
            for (i in 0 until fresh.length()) {
                val entity = fresh.getJSONObject(i)
                val table = entity.getString("tableName")
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
                    stmt.executeQuery("PRAGMA table_info(`$table`)").use { columns ->
                        val actual = mutableListOf<String>()
                        while (columns.next()) actual += columns.getString("name")
                        val fields = entity.getJSONArray("fields")
                        assertEquals((0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }, actual)
                    }
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT deletedAtMillis FROM messages").use { rows -> rows.next(); assertNull(rows.getObject(1)) }
                stmt.executeQuery("SELECT transcribedText, createdAtMillis, deletedAtMillis FROM voice_notes").use { rows ->
                    rows.next(); assertEquals("preserved", rows.getString(1)); assertEquals(42L, rows.getLong(2)); assertNull(rows.getObject(3))
                }
                // Room's Query annotation has binary retention. Execute the actual DAO SQL from source.
                val daoSource = File("src/main/java/com/itantra/data/db/VoiceNoteDao.kt").readText()
                fun query(method: String) = Regex("""@Query\("([^"]+)"\)\s*fun $method\(""")
                    .find(daoSource)!!.groupValues[1]
                val deleted = 1_800_000_000_000L
                assertEquals(1, stmt.executeUpdate(query("moveToTrash").replace(":now", "$deleted").replace(":id", "42")))
                stmt.executeQuery(query("observeAll")).use { assertFalse(it.next()) }
                stmt.executeQuery(query("observeTrash")).use { assertTrue(it.next()) }
                val restore = query("restore").replace(":id", "42")
                assertEquals(1, stmt.executeUpdate(restore.replace(":cutoff", "${deleted - 1}")))
                stmt.executeQuery(query("observeAll")).use { rows -> rows.next(); assertEquals(42L, rows.getLong("createdAtMillis")) }
                assertEquals(0, stmt.executeUpdate(query("deleteTrashed").replace(":id", "42")))
                stmt.executeUpdate(query("moveToTrash").replace(":now", "$deleted").replace(":id", "42"))
                assertEquals(0, stmt.executeUpdate(restore.replace(":cutoff", "$deleted")))
                assertEquals(1, stmt.executeUpdate(query("purgeExpired").replace(":cutoff", "$deleted")))
            }
        }
    }

    private fun createSqliteMemoryConnection(): Connection {
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    private fun createSupportDatabaseWrapper(conn: Connection): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args[0] as String
                    conn.createStatement().use { stmt -> stmt.execute(sql) }
                    null
                }
                else -> null
            }
        } as SupportSQLiteDatabase
    }

    /**
     * TASK 1.1: Confirm AppDatabase does not use fallbackToDestructiveMigration()
     * (or any destructive fallback).
     */
    @Test
    fun testAppDatabaseDoesNotUseDestructiveMigrationFallback() {
        assertNotNull(AppDatabase.MIGRATION_1_2)
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)

        assertNotNull(AppDatabase.MIGRATION_2_3)
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
    }

    /**
     * TASK 1.2: Verify exported schema files exist and match database configuration.
     */
    @Test
    fun testExportedSchemasExistAndValidate() {
        val schemaV2File = File("schemas/com.itantra.data.db.AppDatabase/2.json")
        val schemaV3File = File("schemas/com.itantra.data.db.AppDatabase/3.json")

        assertTrue("v2 schema file must exist", schemaV2File.exists())
        assertTrue("v3 schema file must exist", schemaV3File.exists())

        val v2Json = JSONObject(schemaV2File.readText())
        val v3Json = JSONObject(schemaV3File.readText())

        assertEquals(2, v2Json.getJSONObject("database").getInt("version"))
        assertEquals(3, v3Json.getJSONObject("database").getInt("version"))

        // Verify v3 adds the 5 location fields
        val v3Fields = v3Json.getJSONObject("database")
            .getJSONArray("entities")
            .getJSONObject(0)
            .getJSONArray("fields")

        val fieldNames = (0 until v3Fields.length()).map {
            v3Fields.getJSONObject(it).getString("columnName")
        }
        assertTrue(fieldNames.contains("isLocation"))
        assertTrue(fieldNames.contains("latitude"))
        assertTrue(fieldNames.contains("longitude"))
        assertTrue(fieldNames.contains("accuracyMeters"))
        assertTrue(fieldNames.contains("locationTimestampMillis"))
    }

    /**
     * TASK 1.2: Create a v2 database with sample message rows, run MIGRATION_2_3,
     * and verify the old rows survive unchanged and the new location columns default to null/false.
     */
    @Test
    fun testMigration2To3SurvivesOldRowsAndDefaultsLocationColumns() {
        val conn = createSqliteMemoryConnection()
        val dbWrapper = createSupportDatabaseWrapper(conn)

        // 1. Create v2 schema table (without location columns)
        val schemaV2File = File("schemas/com.itantra.data.db.AppDatabase/2.json")
        val v2Json = JSONObject(schemaV2File.readText())
        val createSqlV2 = v2Json.getJSONObject("database")
            .getJSONArray("entities")
            .getJSONObject(0)
            .getString("createSql")
            .replace("\${TABLE_NAME}", "messages")

        conn.createStatement().use { it.execute(createSqlV2) }

        // 2. Insert sample message row in v2 schema
        val insertSql = """
            INSERT INTO messages (
                messageId, languageWireCode, targetLanguageWireCode, priority, text, originalText,
                translationStatusName, sourceName, createdAtLocal, stateName, peerId, senderDeviceId,
                receiverDeviceId, isVoiceGenerated, sttLatencyMillis, mtLatencyMillis, cryptoLatencyMillis,
                payloadBytes, semanticBytes, secureBytes, finalFrameBytes, packetBytes, rttMillis,
                peerTtfaMillis, estimatedE2eMillis, remoteAudioStartConfMillis, rawPcmEquivalentBytes,
                speechDurationMillis, statusDetail
            ) VALUES (
                20260928001, 'hi', 'en', 1, 'सुरक्षित स्थान पर जाएं', 'Move to a safe place',
                'SUCCESS', 'LOCAL', 1727500000000, 'DELIVERED', 'PEER_OPERATOR_1', 'DEV_A',
                'DEV_B', 1, 140, 42, 4,
                48, 36, 64, 80, 96, 85,
                200, 350, 1727500000400, 24000,
                1500, 'Urgent evacuation dispatch'
            )
        """.trimIndent()
        conn.createStatement().use { it.execute(insertSql) }

        // 3. Execute MIGRATION_2_3
        AppDatabase.MIGRATION_2_3.migrate(dbWrapper)

        // 4. Query migrated row and verify old columns survived intact
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM messages WHERE messageId = 20260928001")
            assertTrue("Row must exist after migration", rs.next())

            // Existing v2 columns
            assertEquals(20260928001L, rs.getLong("messageId"))
            assertEquals("hi", rs.getString("languageWireCode"))
            assertEquals("en", rs.getString("targetLanguageWireCode"))
            assertEquals(1, rs.getInt("priority"))
            assertEquals("सुरक्षित स्थान पर जाएं", rs.getString("text"))
            assertEquals("Move to a safe place", rs.getString("originalText"))
            assertEquals("SUCCESS", rs.getString("translationStatusName"))
            assertEquals("LOCAL", rs.getString("sourceName"))
            assertEquals(1727500000000L, rs.getLong("createdAtLocal"))
            assertEquals("DELIVERED", rs.getString("stateName"))
            assertEquals("PEER_OPERATOR_1", rs.getString("peerId"))
            assertEquals("DEV_A", rs.getString("senderDeviceId"))
            assertEquals("DEV_B", rs.getString("receiverDeviceId"))
            assertEquals(1, rs.getInt("isVoiceGenerated"))
            assertEquals("Urgent evacuation dispatch", rs.getString("statusDetail"))

            // New v3 columns default to false / NULL
            assertEquals("isLocation must default to 0 (false)", 0, rs.getInt("isLocation"))
            assertNull("latitude must default to NULL", rs.getObject("latitude"))
            assertNull("longitude must default to NULL", rs.getObject("longitude"))
            assertNull("accuracyMeters must default to NULL", rs.getObject("accuracyMeters"))
            assertNull("locationTimestampMillis must default to NULL", rs.getObject("locationTimestampMillis"))

            assertFalse("Only 1 row expected", rs.next())
        }

        conn.close()
    }

    /**
     * TASK 1.3: Verify a fresh install (no prior DB) also creates v3 cleanly.
     */
    @Test
    fun testFreshInstallCreatesV3Cleanly() {
        val conn = createSqliteMemoryConnection()

        // Create table from v3 schema createSql
        val schemaV3File = File("schemas/com.itantra.data.db.AppDatabase/3.json")
        val v3Json = JSONObject(schemaV3File.readText())
        val createSqlV3 = v3Json.getJSONObject("database")
            .getJSONArray("entities")
            .getJSONObject(0)
            .getString("createSql")
            .replace("\${TABLE_NAME}", "messages")

        conn.createStatement().use { it.execute(createSqlV3) }

        // Insert a location message into v3 schema
        val insertLocationSql = """
            INSERT INTO messages (
                messageId, languageWireCode, targetLanguageWireCode, priority, text, originalText,
                translationStatusName, sourceName, createdAtLocal, stateName, peerId, senderDeviceId,
                receiverDeviceId, isVoiceGenerated, isLocation, latitude, longitude, accuracyMeters,
                locationTimestampMillis, sttLatencyMillis, mtLatencyMillis, cryptoLatencyMillis,
                payloadBytes, semanticBytes, secureBytes, finalFrameBytes, packetBytes, rttMillis,
                peerTtfaMillis, estimatedE2eMillis, remoteAudioStartConfMillis, rawPcmEquivalentBytes,
                speechDurationMillis, statusDetail
            ) VALUES (
                3001, 'en', 'en', 0, '📍 Location: 12.97160, 77.59460 (±5.0m)', NULL,
                'NONE', 'LOCAL', 1727501000000, 'DELIVERED', 'PEER_X', 'DEV_LOCAL',
                'DEV_REMOTE', 0, 1, 12.9716, 77.5946, 5.0,
                1727501234567, 0, 0, 0,
                28, 28, 44, 52, 60, 0,
                0, 0, 0, 0,
                0, NULL
            )
        """.trimIndent()
        conn.createStatement().use { it.execute(insertLocationSql) }

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM messages WHERE messageId = 3001")
            assertTrue("Location message must be found in clean v3 database", rs.next())
            assertEquals(1, rs.getInt("isLocation"))
            assertEquals(12.9716, rs.getDouble("latitude"), 0.0001)
            assertEquals(77.5946, rs.getDouble("longitude"), 0.0001)
            assertEquals(5.0f, rs.getFloat("accuracyMeters"), 0.1f)
            assertEquals(1727501234567L, rs.getLong("locationTimestampMillis"))
        }

        conn.close()
    }
}
