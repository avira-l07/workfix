package com.example.itantra.ui.screens.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.example.itantra.data.settings.AppSettings
import com.example.itantra.data.settings.SettingsRepository
import com.itantra.core.storage.KeyProvider
import com.itantra.core.storage.PrivateContent
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OperatorNameInputTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun setMain() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private class Keys : KeyProvider {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun getOrCreate(alias: String): SecretKey = key
        override fun getExisting(alias: String): SecretKey = key
        override fun delete(alias: String) {}
    }

    @Test fun `rapid edits retain cursor and composition while disk saves catch up`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                temp.newFolder().resolve("settings.preferences_pb")
            }, PrivateContent(Keys()),
        )
        repository.update { it.copy(operatorName = "Existing name") }
        val vm = SettingsViewModel(repository)
        try {
            assertNull(vm.operatorNameInput.value)
            val loaded = vm.operatorNameInput.filterNotNull().first()
            assertEquals("Existing name", loaded.text)
            assertEquals(TextRange(loaded.text.length), loaded.selection)

            vm.setOperatorName(TextFieldValue("Avi ", TextRange(4)))
            val composing = TextFieldValue("Avi ral", TextRange(3), TextRange(0, 3))
            vm.setOperatorName(composing)
            // Main's disk-write jobs have not run yet; the editor must already be current.
            assertEquals(composing, vm.operatorNameInput.value)
            repository.settings.first { it.operatorName == composing.text }
            assertEquals(composing, vm.operatorNameInput.value)

            val cursorMove = composing.copy(selection = TextRange(1), composition = null)
            vm.setOperatorName(cursorMove)
            assertEquals(cursorMove, vm.operatorNameInput.value)
            val inserted = TextFieldValue("AXvi ral", TextRange(2))
            vm.setOperatorName(inserted)
            repository.settings.first { it.operatorName == inserted.text }
            assertEquals(inserted, vm.operatorNameInput.value)
            val recreated = SettingsViewModel(repository)
            try {
                assertEquals(inserted.text, recreated.operatorNameInput.filterNotNull().first().text)
            } finally { recreated.viewModelScope.cancel() }
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    @Test fun `reset clears the editor and stored name after a pending edit`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                temp.newFolder().resolve("settings.preferences_pb")
            }, PrivateContent(Keys()),
        )
        repository.update { it.copy(operatorName = "Previous") }
        val vm = SettingsViewModel(repository)
        try {
            vm.operatorNameInput.filterNotNull().first()
            vm.setOperatorName(TextFieldValue("New name", TextRange(3)))
            vm.resetToDefault()
            assertEquals(TextFieldValue(), vm.operatorNameInput.value)
            repository.settings.first { it == AppSettings() }
            assertEquals(TextFieldValue(), vm.operatorNameInput.value)
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
        }
    }
}
