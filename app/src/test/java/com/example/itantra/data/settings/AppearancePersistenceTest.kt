package com.example.itantra.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.itantra.core.storage.KeyProvider
import com.itantra.core.storage.PrivateContent
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearancePersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private class Keys : KeyProvider {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun getOrCreate(alias: String): SecretKey = key
        override fun getExisting(alias: String): SecretKey = key
        override fun delete(alias: String) {}
    }
    @Test fun `appearance persists and simultaneous edits preserve other preferences`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = temp.newFolder().resolve("settings.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val content = PrivateContent(Keys())
            val repository = SettingsRepository(store, content)
            coroutineScope {
                launch { repository.update { it.copy(themeMode = ThemeMode.DARK) } }
                launch { repository.update { it.copy(colorPalette = ColorPalette.IRIS) } }
                launch { repository.update { it.copy(vadSensitivity = 4) } }
            }
            val recreated = SettingsRepository(store, content).settings.first()
            assertEquals(ThemeMode.DARK, recreated.themeMode)
            assertEquals(ColorPalette.IRIS, recreated.colorPalette)
            assertEquals(4, recreated.vadSensitivity)
            repository.resetToDefault()
            assertEquals(AppSettings(), repository.settings.first())
        } finally { scope.cancel() }
    }
}
