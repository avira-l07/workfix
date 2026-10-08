package com.itantra.core.storage

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.itantra.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Isolated app-owned process: main process memory and all writers are gone before deletion. */
class WipeActivity : ComponentActivity() {
    companion object {
        fun marker(context: Context) = File(context.noBackupFilesDir, "wipe.pending")
        fun hasPendingWipe(context: Context) = marker(context).exists() || File(marker(context).path + ".bak").exists()
        fun writeRequest(context: Context, choices: Set<DataRemovalChoice>) {
            val request = DataRemovalChoice.encode(choices)
            val file = android.util.AtomicFile(marker(context))
            val stream = file.startWrite()
            try { stream.write(request); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPendingWipe(this)) { finish(); return }
        runWipe()
    }
    private fun runWipe() {
        setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("Wiping private data…") } } }
        lifecycleScope.launch {
            try {
                val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                // Main exits voluntarily after launching this activity. Never delete under a live writer.
                withContext(Dispatchers.IO) {
                    repeat(100) {
                        if (manager.runningAppProcesses.orEmpty().none { it.uid == Process.myUid() && it.processName == packageName }) return@withContext
                        delay(50)
                    }
                    error("Main process is still running")
                }
                stopService(Intent(this@WipeActivity, com.itantra.core.service.OperationalForegroundService::class.java))
                withContext(Dispatchers.IO) {
                    WipeFiles(filesDir, getDatabasePath(EncryptedDatabase.NAME).parentFile!!,
                        File(applicationInfo.dataDir, "shared_prefs"), noBackupFilesDir,
                        listOf(cacheDir, codeCacheDir) + externalCacheDirs.filterNotNull(),
                        getExternalFilesDirs(null).filterNotNull(), AndroidKeyProvider(), clearHistory = { tables ->
                            val database = EncryptedDatabase.open(this@WipeActivity, AndroidKeyProvider())
                            try {
                                HistoryDataRemoval.clear(database.openHelper.writableDatabase, tables)
                            } finally { database.close() }
                        }).wipe(android.util.AtomicFile(marker(this@WipeActivity)).openRead().use { DataRemovalChoice.decode(it.readBytes()) })
                    android.util.AtomicFile(marker(this@WipeActivity)).delete()
                    check(!hasPendingWipe(this@WipeActivity))
                }
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll()
                startActivity(Intent(this@WipeActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (_: Exception) {
                setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Wipe could not finish. Private data remains locked until the wipe completes.")
                    Button(onClick = { runWipe() }) { Text("Retry wipe") }
                    com.example.itantra.ui.screens.settings.WipeDataAction { choices ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { writeRequest(this@WipeActivity, choices) }
                            runWipe()
                        }
                    }
                    TextButton(onClick = { finish() }) { Text("Close") }
                } } }
            }
        }
    }
}
