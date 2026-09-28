package com.itantra.core.storage

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.itantra.data.db.AppDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File migration runs before any coordinator or Room writer is started. */
object EncryptedDatabase {
    const val NAME = "itantra.encrypted.db"
    const val LEGACY = "itantra.db"
    private fun open(file: File, password: ByteArray) = SQLiteDatabase.openDatabase(
        file.absolutePath, password, null, SQLiteDatabase.OPEN_READWRITE,
        { _, _ -> throw IllegalStateException("Stored database failed integrity validation") }, null
    )

    private fun counts(db: SQLiteDatabase): Map<String, Long> {
        val tables = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", emptyArray<String>()).use {
            while (it.moveToNext()) tables.add(it.getString(0))
        }
        return tables.associateWith { table ->
            db.rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"", emptyArray<String>()).use {
                check(it.moveToFirst()); it.getLong(0)
            }
        }
    }

    fun room(context: Context, name: String, password: ByteArray): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory { config ->
                val original = config.callback
                val safeCallback = object : SupportSQLiteOpenHelper.Callback(original.version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = original.onUpgrade(db, old, new)
                    override fun onDowngrade(db: SupportSQLiteDatabase, old: Int, new: Int) = original.onDowngrade(db, old, new)
                    override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
                    override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
                    override fun onCorruption(db: SupportSQLiteDatabase) {
                        throw IllegalStateException("Stored database failed integrity validation; file retained")
                    }
                }
                SupportOpenHelperFactory(password).create(SupportSQLiteOpenHelper.Configuration.builder(config.context)
                    .name(config.name).callback(safeCallback).build())
            }
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

    fun open(context: Context, keys: KeyProvider): AppDatabase {
        System.loadLibrary("sqlcipher")
        val target = context.getDatabasePath(NAME)
        val legacy = context.getDatabasePath(LEGACY)
        val staging = context.getDatabasePath("$NAME.migrating")
        val raw = DatabasePassphrase(File(context.noBackupFilesDir, "storage_keys"), keys)
            .loadOrCreate(target.exists())
        // A printable representation avoids binary ATTACH KEY coercion; it is never persisted.
        val password = raw.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        raw.fill(0)
        try {
            if (!target.exists() && legacy.exists()) {
                deleteFiles(staging)
                target.parentFile?.mkdirs()
                val before: Map<String, Long>
                open(legacy, byteArrayOf()).use { source ->
                    before = counts(source)
                    val version = source.version
                    source.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(staging.absolutePath, password.decodeToString()))
                    try {
                        source.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { check(it.moveToFirst()) }
                        source.execSQL("PRAGMA encrypted.user_version=$version")
                    } finally { source.execSQL("DETACH DATABASE encrypted") }
                }
                open(staging, password).use { copy ->
                    check(counts(copy) == before) { "Migration row counts differ" }
                    copy.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { check(it.moveToFirst() && it.getString(0) == "ok") }
                }
                // Also validate the unchanged Room schema/migrations before removing any source.
                val validation = room(context, staging.absolutePath, password)
                try {
                    validation.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { check(it.moveToFirst() && it.getInt(0) == 0) }
                } finally { validation.close() }
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            // Handles interruption after rename but before plaintext cleanup.
            if (target.exists() && legacy.exists()) {
                val before = open(legacy, byteArrayOf()).use { counts(it) }
                open(target, password).use { check(counts(it) == before) { "Migration row counts differ" } }
            }
            if (target.exists()) open(target, password).close()
            val result = room(context, target.absolutePath, password)
            try {
                result.openHelper.writableDatabase
                if (legacy.exists()) deleteFiles(legacy)
                deleteFiles(staging)
                return result
            } catch (e: Exception) { result.close(); throw e }
        } catch (e: Exception) {
            android.util.Log.e("EncryptedDatabase", "Stored database unavailable; source files retained for recovery")
            throw StoredDataUnavailable(e)
        }
        // Room's factory retains the password for reopen; it is released when the process ends.
    }

    fun deleteFiles(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val candidate = File(file.path + suffix)
            check(!candidate.exists() || candidate.delete()) { "Could not remove storage file" }
        }
    }
}
