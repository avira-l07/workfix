package com.itantra.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.itantra.data.settings.settingsDataStore
import java.io.File

object StoragePrivacy {
    suspend fun prepare(context: Context) {
        val content = PrivateContent()
        val prefs = context.getSharedPreferences("itantra_device_profile", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        for (name in listOf("device_id", "display_name")) {
            prefs.getString(name, null)?.let { edit.putString(name, content.migrate(it)) }
        }
        check(edit.commit()) { "Could not protect profile" }
        context.settingsDataStore.edit {
            val name = stringPreferencesKey("operator_name")
            it[name]?.let { value -> it[name] = content.migrate(value) }
        }
        File(context.filesDir, "benchmarks").listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { content.read(it) }
        // Legacy diagnostic recordings are not needed for app operation.
        listOfNotNull(File(context.cacheDir, "debug_ptt.wav"), context.getExternalFilesDir(null)?.let { File(it, "debug_ptt.wav") })
            .forEach { check(!it.exists() || it.delete()) { "Could not remove legacy audio recording" } }
    }
}
