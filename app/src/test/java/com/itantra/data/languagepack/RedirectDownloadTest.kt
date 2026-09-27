package com.itantra.data.languagepack

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class RedirectDownloadTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { staged[key ?: ""] = value; return this }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor { staged[key ?: ""] = null; return this }
            override fun clear(): SharedPreferences.Editor { target.clear(); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                staged.forEach { (k, v) -> if (v == null) target.remove(k) else target[k] = v }
            }
        }
    }

    private class FakeContext(private val baseDir: File) : ContextWrapper(null) {
        private val prefs = FakeSharedPreferences()
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = baseDir
        override fun getCacheDir(): File = baseDir
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun testDownloadFileFollowsHuggingFaceRedirectAndVerifiesChecksum() = runBlocking {
        val repo = RealLanguagePackRepository(
            context = FakeContext(tempFolder.root),
            storage = FileLanguagePackStorage(tempFolder.root),
            manifestParser = LanguagePackManifestParser
        )

        val targetFile = File(tempFolder.root, "marathi_tokens.txt")
        // HuggingFace /resolve/main/ URL produces a 302 redirect to cdn-lfs host
        val redirectUrl = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/mar/tokens.txt"
        val expectedSha256 = "4d968029d0754b41633cb0871cce6796a5ab3d3bc2b9b91c5721cfdf85156083"

        var totalBytesRead = 0
        repo.downloadFile(redirectUrl, targetFile) { bytesRead ->
            totalBytesRead += bytesRead
        }

        assertTrue("Downloaded file must exist", targetFile.exists())
        assertTrue("Downloaded file must not be empty", targetFile.length() > 0)
        assertEquals("Downloaded bytes counter must match file size", targetFile.length(), totalBytesRead.toLong())

        // Calculate and verify SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val actualSha256 = digest.digest(targetFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("SHA-256 must match the expected manifest checksum after redirect", expectedSha256, actualSha256)
    }
}
