package com.itantra.core.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/** Used only by the isolated wipe process, after all normal app writers have exited. */
object HistoryDataRemoval {
    fun clear(database: SupportSQLiteDatabase, tables: Set<String>) {
        require(tables.isNotEmpty() && tables.all { it == "messages" || it == "voice_notes" })
        database.beginTransaction()
        try {
            database.query("PRAGMA secure_delete=ON").use {
                check(it.moveToFirst() && it.getInt(0) == 1) { "Secure history deletion is unavailable" }
            }
            tables.forEach { database.execSQL("DELETE FROM $it") }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        database.execSQL("VACUUM")
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "History cleanup is still busy" }
        }
    }
}
