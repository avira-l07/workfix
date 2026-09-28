package com.itantra.core.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.itantra.data.db.AppDatabase
import com.itantra.data.db.MessageEntity
import com.itantra.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import net.zetetic.database.sqlcipher.SQLiteDatabase

class EncryptedDatabaseTest {
    private lateinit var root: File
    private lateinit var context: Context
    private val keys = object : KeyProvider {
        val entries = mutableMapOf<String, SecretKey>()
        override fun getExisting(alias: String) = entries[alias]
        override fun getOrCreate(alias: String) = entries.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun delete(alias: String) { entries.remove(alias) }
    }
    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "encrypted-db-test-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = if (File(name).isAbsolute) File(name) else File(root, name)
            override fun getNoBackupFilesDir() = File(root, "keys").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun row(id: Long) = MessageEntity.fromDomain(TransceiverMessage(
        messageId = id, language = LanguageCode.HINDI, priority = 2,
        text = "PRIVATE_MARKER_itantra_$id", source = MessageSource.LOCAL,
        createdAtLocal = id, state = MessageState.DELIVERED,
        latitude = 12.123, longitude = 77.456
    ))
    @Test fun freshDatabaseIsEncryptedAndWrongKeyIsRejected() {
        val fresh = EncryptedDatabase.open(context, keys)
        try { fresh.messageDao().insert(row(1)) } finally { fresh.close() }
        val file = context.getDatabasePath(EncryptedDatabase.NAME)
        assertFalse(file.readBytes().decodeToString().contains("PRIVATE_MARKER"))
        var rejected = false
        try {
            SQLiteDatabase.openDatabase(file.path, "wrong".toByteArray(), null, SQLiteDatabase.OPEN_READONLY,
                { _, _ -> throw IllegalStateException("Rejected") }, null).use { it.rawQuery("SELECT * FROM messages", emptyArray<String>()).use { c -> c.moveToFirst() } }
        } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        val reopened = EncryptedDatabase.open(context, keys)
        try { assertEquals(1, reopened.messageDao().getAll().size) } finally { reopened.close() }
    }
    @Test fun migratesExistingRowsAndRemovesPlaintextOnlyAfterVerification() {
        val old = Room.databaseBuilder(context, AppDatabase::class.java, context.getDatabasePath(EncryptedDatabase.LEGACY).path).build()
        try {
            old.messageDao().insertAll((1L..3L).map(::row))
            assertEquals(3, old.messageDao().getAll().size)
        } finally { old.close() }
        val migrated = EncryptedDatabase.open(context, keys)
        try {
            assertEquals((1L..3L).map(::row), migrated.messageDao().getAll())
            assertEquals(3, migrated.openHelper.writableDatabase.version)
        } finally { migrated.close() }
        assertFalse(context.getDatabasePath(EncryptedDatabase.LEGACY).exists())
        assertFalse(context.getDatabasePath(EncryptedDatabase.NAME).readBytes().decodeToString().contains("PRIVATE_MARKER"))
    }
}
