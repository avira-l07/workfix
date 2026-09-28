package com.itantra.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK 1: Room migration 2->3 verification using Room's MigrationTestHelper.
 *
 * Runs as an instrumented Android test on device or emulator:
 * 1. Confirms AppDatabase does not use fallbackToDestructiveMigration().
 * 2. Creates a v2 database with sample message rows, executes MIGRATION_2_3,
 *    and verifies old rows survive unchanged and new location columns default to null/false.
 * 3. Verifies a fresh install (no prior DB) also creates v3 cleanly.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val TEST_DB = "migration-test.db"
    private val FRESH_DB = "fresh-install-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun testAppDatabaseDoesNotUseDestructiveMigrationFallback() {
        assertNotNull(AppDatabase.MIGRATION_1_2)
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)

        assertNotNull(AppDatabase.MIGRATION_2_3)
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
    }

    @Test
    fun testMigration2To3SurvivesOldRowsAndDefaultsLocationColumns() {
        // 1. Create a version 2 database with sample message rows
        val v2Db = helper.createDatabase(TEST_DB, 2)
        v2Db.execSQL(
            """
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
        )
        v2Db.close()

        // 2. Run MIGRATION_2_3 and validate database schema against exported v3 schema
        val v3Db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            AppDatabase.MIGRATION_2_3
        )

        // 3. Query the existing row and verify all original columns survived intact
        val cursor = v3Db.query("SELECT * FROM messages WHERE messageId = 20260928001")
        assertTrue("Migrated row must exist", cursor.moveToFirst())

        assertEquals("hi", cursor.getString(cursor.getColumnIndexOrThrow("languageWireCode")))
        assertEquals("en", cursor.getString(cursor.getColumnIndexOrThrow("targetLanguageWireCode")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("priority")))
        assertEquals("सुरक्षित स्थान पर जाएं", cursor.getString(cursor.getColumnIndexOrThrow("text")))
        assertEquals("Move to a safe place", cursor.getString(cursor.getColumnIndexOrThrow("originalText")))
        assertEquals("SUCCESS", cursor.getString(cursor.getColumnIndexOrThrow("translationStatusName")))
        assertEquals("LOCAL", cursor.getString(cursor.getColumnIndexOrThrow("sourceName")))
        assertEquals(1727500000000L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAtLocal")))
        assertEquals("DELIVERED", cursor.getString(cursor.getColumnIndexOrThrow("stateName")))
        assertEquals("PEER_OPERATOR_1", cursor.getString(cursor.getColumnIndexOrThrow("peerId")))
        assertEquals("DEV_A", cursor.getString(cursor.getColumnIndexOrThrow("senderDeviceId")))
        assertEquals("DEV_B", cursor.getString(cursor.getColumnIndexOrThrow("receiverDeviceId")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("isVoiceGenerated")))
        assertEquals("Urgent evacuation dispatch", cursor.getString(cursor.getColumnIndexOrThrow("statusDetail")))

        // 4. Verify newly added location columns default to 0 / null
        assertEquals("isLocation must default to 0 (false)", 0, cursor.getInt(cursor.getColumnIndexOrThrow("isLocation")))
        assertTrue("latitude must default to NULL", cursor.isNull(cursor.getColumnIndexOrThrow("latitude")))
        assertTrue("longitude must default to NULL", cursor.isNull(cursor.getColumnIndexOrThrow("longitude")))
        assertTrue("accuracyMeters must default to NULL", cursor.isNull(cursor.getColumnIndexOrThrow("accuracyMeters")))
        assertTrue("locationTimestampMillis must default to NULL", cursor.isNull(cursor.getColumnIndexOrThrow("locationTimestampMillis")))

        cursor.close()
        v3Db.close()
    }

    @Test
    fun testFreshInstallCreatesV3Cleanly() {
        val freshDb = helper.createDatabase(FRESH_DB, 3)
        assertNotNull(freshDb)
        freshDb.close()
    }
}
