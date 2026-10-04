package com.itantra.data.languagepack

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
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
        val payload = "offline model fixture\n".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/resolve/main/tokens.txt") { exchange ->
            exchange.responseHeaders.add("Location", "/cdn/tokens.txt")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn/tokens.txt") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
        val repo = RealLanguagePackRepository(
            context = FakeContext(tempFolder.root),
            storage = FileLanguagePackStorage(tempFolder.root),
            manifestParser = LanguagePackManifestParser
        )

        val targetFile = File(tempFolder.root, "marathi_tokens.txt")
        // Same redirect shape as a model host, served locally so the test is offline.
        val redirectUrl = "http://127.0.0.1:${server.address.port}/resolve/main/tokens.txt"
        val expectedSha256 = "4930d360b4fe17ce00a0dc0db22594918ea0d21fe57242bfe828c1c53d4709fb"

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
        } finally {
            server.stop(0)
        }
    }
}
