package com.itantra.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, VoiceNoteEntity::class, PeerEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun voiceNoteDao(): VoiceNoteDao
    abstract fun peerDao(): PeerDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN peerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderDeviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN receiverDeviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN isVoiceGenerated INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isLocation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN latitude REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN longitude REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN accuracyMeters REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN locationTimestampMillis INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS voice_notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, transcribedText TEXT NOT NULL, languageWireCode TEXT NOT NULL, createdAtMillis INTEGER NOT NULL)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS peers (deviceId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, transportAddress TEXT NOT NULL, transportName TEXT NOT NULL, lastSeenMillis INTEGER NOT NULL)")
            }
        }
    }
}
