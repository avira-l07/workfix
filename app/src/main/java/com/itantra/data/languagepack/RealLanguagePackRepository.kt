package com.itantra.data.languagepack

import android.content.Context
import android.os.StatFs
import com.itantra.core.inference.ModelFileSpecs
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCatalog
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.LanguagePackAvailability
import com.itantra.domain.model.LanguagePackInstallState
import com.itantra.domain.model.LanguagePackManifest
import com.itantra.domain.model.LanguagePackSummary
import com.itantra.domain.repository.LanguagePackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class RealLanguagePackRepository(
    private val context: Context,
    private val storage: LanguagePackStorage,
    private val manifestParser: LanguagePackManifestParser
) : LanguagePackRepository {

    private val sttStates = MutableStateFlow(buildInitialSttStates())
    private val ttsStates = MutableStateFlow(buildInitialTtsStates())
    private val activeLanguage = MutableStateFlow<LanguageCode?>(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("source_lang", null)?.let { LanguageCode.fromWireCode(it) }
    )
    private val targetLanguage = MutableStateFlow<LanguageCode?>(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("target_lang", null)?.let { LanguageCode.fromWireCode(it) }
    )
    private val receiveLanguage = MutableStateFlow<LanguageCode?>(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("receive_lang", null)?.let { LanguageCode.fromWireCode(it) }
    )
    private val speechInputMode = MutableStateFlow(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("speech_input_mode", "AUTO")?.let {
            try { com.itantra.domain.model.SpeechInputMode.valueOf(it) } catch (_: Exception) { com.itantra.domain.model.SpeechInputMode.AUTO }
        } ?: com.itantra.domain.model.SpeechInputMode.AUTO
    )
    private val manualSttLanguage = MutableStateFlow<LanguageCode?>(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("manual_stt_lang", null)?.let { LanguageCode.fromWireCode(it) }
    )
    private val downloadProgress = MutableStateFlow<Map<LanguageCode, Int>>(emptyMap())
    private val downloadJobs = mutableMapOf<LanguageCode, Job>()
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()
    private val sharedSttMutex = kotlinx.coroutines.sync.Mutex()

    internal fun getActiveJob(code: LanguageCode): Job? = downloadJobs[code]

    private fun isSharedSttReady(): Boolean {
        val spec = ModelFileSpecs.getSttSpec(LanguageCode.HINDI)
        val sharedDir = File(storage.packDirectory(LanguageCode.HINDI).parentFile, "shared/stt")
        if (!sharedDir.exists()) return false
        val filesPresent = spec.requiredFiles.all {
            val f = File(sharedDir, it)
            f.exists() && f.length() > 0L
        }
        if (!filesPresent) {
            val marker = File(sharedDir, ".verified_v1")
            if (marker.exists()) marker.delete()
            return false
        }
        return true
    }

    private fun isTtsReady(code: LanguageCode): Boolean {
        val spec = ModelFileSpecs.getTtsSpec(code) ?: return false
        val dir = File(storage.packDirectory(code), "tts")
        if (!dir.exists()) return false
        val filesPresent = spec.requiredFiles.all {
            val f = File(dir, it)
            f.exists() && f.length() > 0L
        }
        if (!filesPresent) {
            val marker = File(dir, ".verified_v1")
            if (marker.exists()) marker.delete()
            return false
        }
        return true
    }

    private fun buildInitialSttStates(): Map<LanguageCode, LanguagePackInstallState> {
        val sharedReady = isSharedSttReady()
        val state = if (sharedReady) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED
        return LanguageCatalog.all.associate { lang ->
            lang.code to state
        }
    }

    private fun buildInitialTtsStates(): Map<LanguageCode, LanguagePackInstallState> {
        return LanguageCatalog.all.associate { lang ->
            val ready = isTtsReady(lang.code)
            lang.code to if (ready) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED
        }
    }

    override fun observePackSummaries(): Flow<List<LanguagePackSummary>> {
        return combine(sttStates, ttsStates, activeLanguage, downloadProgress) { currentStt, currentTts, active, progressMap ->
            LanguageCatalog.all.map { lang ->
                val sttState = currentStt[lang.code] ?: LanguagePackInstallState.NOT_INSTALLED
                val ttsState = currentTts[lang.code] ?: LanguagePackInstallState.NOT_INSTALLED
                val isFullyInstalled = (sttState == LanguagePackInstallState.INSTALLED && ttsState == LanguagePackInstallState.INSTALLED)

                val availability = when {
                    active == lang.code -> LanguagePackAvailability.ACTIVE
                    isFullyInstalled -> LanguagePackAvailability.DOWNLOADED
                    else -> LanguagePackAvailability.AVAILABLE
                }

                var sttSize = 0L
                if (sttState == LanguagePackInstallState.INSTALLED) {
                    val sharedDir = File(storage.packDirectory(lang.code).parentFile, "shared/stt")
                    ModelFileSpecs.getSttSpec(lang.code).requiredFiles.forEach { f ->
                        val file = File(sharedDir, f)
                        if (file.exists()) sttSize += file.length()
                    }
                }

                var ttsSize = 0L
                if (ttsState == LanguagePackInstallState.INSTALLED) {
                    val dir = File(storage.packDirectory(lang.code), "tts")
                    ModelFileSpecs.getTtsSpec(lang.code)?.requiredFiles?.forEach { f ->
                        val file = File(dir, f)
                        if (file.exists()) ttsSize += file.length()
                    }
                }

                LanguagePackSummary(
                    language = lang,
                    sttInstallState = sttState,
                    ttsInstallState = ttsState,
                    availability = availability,
                    sttSizeBytes = if (sttSize > 0) sttSize else null,
                    ttsSizeBytes = if (ttsSize > 0) ttsSize else null,
                    downloadProgressPercent = progressMap[lang.code]
                )
            }
        }
    }

    override fun observeActiveLanguage(): Flow<LanguageCode?> = activeLanguage

    override fun observeTargetLanguage(): Flow<LanguageCode?> = targetLanguage

    override fun observeReceiveLanguage(): Flow<LanguageCode?> = receiveLanguage.asStateFlow()

    override fun observeSpeechInputMode(): Flow<com.itantra.domain.model.SpeechInputMode> = speechInputMode.asStateFlow()

    override fun observeManualSttLanguage(): Flow<LanguageCode?> = manualSttLanguage.asStateFlow()

    override suspend fun getManifest(code: LanguageCode): LanguagePackManifest? = withContext(Dispatchers.IO) {
        try {
            val json = context.assets.open("language_packs/${code.wireCode}_dev_manifest.json")
                .bufferedReader().use { it.readText() }
            manifestParser.parseOrNull(json)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun setActiveLanguage(code: LanguageCode): Boolean {
        if (!storage.isSharedSttInstalled() && !storage.isInstalled(code)) {
            return false
        }
        activeLanguage.value = code
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).edit().putString("source_lang", code.wireCode).apply()
        return true
    }

    override suspend fun setTargetLanguage(code: LanguageCode?): Boolean {
        targetLanguage.value = code
        val prefs = context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).edit()
        if (code == null) {
            prefs.remove("target_lang")
        } else {
            prefs.putString("target_lang", code.wireCode)
        }
        prefs.apply()
        return true
    }

    override suspend fun setReceiveLanguage(code: LanguageCode?): Boolean {
        receiveLanguage.value = code
        val prefs = context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).edit()
        if (code == null) {
            prefs.remove("receive_lang")
        } else {
            prefs.putString("receive_lang", code.wireCode)
        }
        prefs.apply()
        return true
    }

    override suspend fun setSpeechInputMode(mode: com.itantra.domain.model.SpeechInputMode) {
        speechInputMode.value = mode
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("speech_input_mode", mode.name)
            .apply()
    }

    override suspend fun setManualSttLanguage(code: LanguageCode?): Boolean {
        manualSttLanguage.value = code
        val prefs = context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).edit()
        if (code == null) {
            prefs.remove("manual_stt_lang")
        } else {
            prefs.putString("manual_stt_lang", code.wireCode)
        }
        prefs.apply()
        return true
    }

    override suspend fun startDownload(code: LanguageCode) {
        val ttsSpec = ModelFileSpecs.getTtsSpec(code) ?: return
        val sttSpec = ModelFileSpecs.getSttSpec(code)
        val manifest = getManifest(code) ?: return

        val jobToStart: Job? = downloadMutex.withLock {
            val existing = downloadJobs[code]
            if (existing != null && !existing.isCompleted) {
                return@withLock null
            }

            lateinit var createdJob: Job
            createdJob = CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
                val needSharedStt = !isSharedSttReady()

                if (needSharedStt) {
                    LanguageCatalog.all.forEach { updateState(it.code, LanguagePackInstallState.DOWNLOADING, true) }
                }
                updateState(code, LanguagePackInstallState.DOWNLOADING, false)
                updateProgress(code, 0)

                val rootDir = storage.packDirectory(code)
                val parentDir = requireNotNull(rootDir.parentFile) { "Pack parent directory must exist" }
                val sharedDir = File(parentDir, "shared/stt")
                val tmpDir = File(rootDir, ".install_tmp")
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()

                try {
                    val statFs = StatFs(parentDir.absolutePath)
                    val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
                    val requiredBytes = (if (needSharedStt) manifest.sttModel.sizeBytes else 0L) +
                        manifest.ttsModel.sizeBytes + 50_000_000L

                    if (availableBytes < requiredBytes) {
                        if (needSharedStt) {
                            LanguageCatalog.all.forEach { updateState(it.code, LanguagePackInstallState.ERROR, true) }
                        }
                        updateState(code, LanguagePackInstallState.ERROR, false)
                        updateProgress(code, null)
                        return@launch
                    }

                    val totalExpectedBytes = (if (needSharedStt) manifest.sttModel.sizeBytes else 0L) + manifest.ttsModel.sizeBytes
                    var cumulativeBytesDownloaded = 0L

                    val onBytesRead: (Int) -> Unit = { count ->
                        cumulativeBytesDownloaded += count
                        if (totalExpectedBytes > 0) {
                            val pct = ((cumulativeBytesDownloaded * 100) / totalExpectedBytes).toInt().coerceIn(0, 99)
                            updateProgress(code, pct)
                        }
                    }

                    // 1. Download shared multilingual STT if missing, single-flight protected (FIX 040)
                    if (needSharedStt) {
                        sharedSttMutex.withLock {
                            if (!isSharedSttReady()) {
                                val sttUrlBase = manifest.sttModel.downloadUrl ?: throw Exception("No STT URL")
                                val tmpStt = File(tmpDir, "shared_stt")
                                tmpStt.mkdirs()

                                for (file in sttSpec.requiredFiles) {
                                    val targetFile = File(tmpStt, file)
                                    downloadFile("$sttUrlBase/$file", targetFile, onBytesRead)
                                    val expectedSha = manifest.sttModel.checksumsSha256[file]
                                    if (!expectedSha.isNullOrEmpty()) {
                                        verifySha256(targetFile, expectedSha)
                                    }
                                }

                                // Safe atomic move shared STT (FIX 041)
                                sharedDir.mkdirs()
                                for (file in sttSpec.requiredFiles) {
                                    val destFile = File(sharedDir, file)
                                    safeAtomicMove(File(tmpStt, file), destFile)
                                }
                                File(sharedDir, ".verified_v1").writeText(manifest.packVersion)
                                LanguageCatalog.all.forEach { updateState(it.code, LanguagePackInstallState.INSTALLED, true) }
                            }
                        }
                    }

                    // 2. Download per-language TTS
                    val ttsUrlBase = manifest.ttsModel.downloadUrl ?: throw Exception("No TTS URL")
                    val tmpTts = File(tmpDir, "tts")
                    tmpTts.mkdirs()

                    for (file in ttsSpec.requiredFiles) {
                        val targetFile = File(tmpTts, file)
                        downloadFile("$ttsUrlBase/$file", targetFile, onBytesRead)
                        val expectedSha = manifest.ttsModel.checksumsSha256[file]
                        if (!expectedSha.isNullOrEmpty()) {
                            verifySha256(targetFile, expectedSha)
                        }
                    }

                    // 3. Atomic Move per-language TTS with fallback (FIX 041)
                    val ttsDest = File(rootDir, "tts")
                    safeAtomicMove(tmpTts, ttsDest)
                    File(ttsDest, ".verified_v1").writeText(manifest.packVersion)

                    updateProgress(code, 100)
                    updateState(code, LanguagePackInstallState.INSTALLED, false)

                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (needSharedStt) {
                        val sttOk = isSharedSttReady()
                        LanguageCatalog.all.forEach {
                            updateState(it.code, if (sttOk) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED, true)
                        }
                    }
                    updateState(code, if (isTtsReady(code)) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (needSharedStt) {
                        LanguageCatalog.all.forEach { updateState(it.code, LanguagePackInstallState.ERROR, true) }
                    }
                    updateState(code, LanguagePackInstallState.ERROR, false)
                } finally {
                    tmpDir.deleteRecursively()
                    updateProgress(code, null)
                    downloadMutex.withLock {
                        if (downloadJobs[code] === createdJob) {
                            downloadJobs.remove(code)
                        }
                    }
                }
            }
            downloadJobs[code] = createdJob
            createdJob
        }

        jobToStart?.start()
    }

    private fun safeAtomicMove(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Fallback for filesystems without atomic move support (FIX 041)
            val parent = destination.parentFile ?: source.parentFile
            val backup = File(parent, ".backup_${System.nanoTime()}")
            var hadBackup = false
            if (destination.exists()) {
                try {
                    Files.move(destination.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    hadBackup = true
                } catch (_: Exception) {}
            }
            try {
                if (source.isDirectory) {
                    source.copyRecursively(destination, overwrite = true)
                    source.deleteRecursively()
                } else {
                    source.inputStream().buffered().use { input ->
                        destination.outputStream().buffered().use { output ->
                            input.copyTo(output)
                        }
                    }
                    source.delete()
                }
                if (hadBackup) backup.deleteRecursively()
            } catch (copyEx: Exception) {
                if (hadBackup) {
                    if (destination.exists()) destination.deleteRecursively()
                    Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                throw copyEx
            }
        }
    }

    private fun verifySha256(file: File, expectedSha: String) {
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException("File missing or zero-byte: ${file.name}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                digest.update(buf, 0, r)
            }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw IllegalStateException("Checksum mismatch for ${file.name}: expected $expectedSha, got $actualSha")
        }
    }

    private suspend fun downloadFile(
        urlStr: String,
        dest: File,
        onBytesRead: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000

            if (connection.responseCode !in 200..299) {
                throw Exception("HTTP Error ${connection.responseCode} for $urlStr")
            }

            val tempDest = File(dest.absolutePath + ".part")
            connection.inputStream.use { input ->
                FileOutputStream(tempDest).use { output ->
                    val data = ByteArray(8192)
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        ensureActive()
                        output.write(data, 0, count)
                        onBytesRead(count)
                    }
                }
            }
            if (tempDest.length() == 0L) {
                throw Exception("Downloaded file is 0 bytes: $urlStr")
            }
            safeAtomicMove(tempDest, dest)
        } finally {
            // Explicitly disconnect connection in finally (FIX 068)
            connection?.disconnect()
        }
    }

    override suspend fun cancelDownload(code: LanguageCode) {
        val jobToCancel = downloadMutex.withLock {
            downloadJobs.remove(code)
        }
        jobToCancel?.cancelAndJoin()

        val tmpDir = File(storage.packDirectory(code), ".install_tmp")
        tmpDir.deleteRecursively()

        updateProgress(code, null)
    }

    override suspend fun deleteInstalledPack(code: LanguageCode) {
        storage.deletePack(code)
        updateState(code, LanguagePackInstallState.NOT_INSTALLED, false)

        // Shared STT remains untouched: verify actual disk state
        val sharedReady = isSharedSttReady()
        val sttState = if (sharedReady) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED
        LanguageCatalog.all.forEach { updateState(it.code, sttState, true) }

        if (activeLanguage.value == code) {
            activeLanguage.value = null
            // Clear saved source_lang preference (FIX 042)
            context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE)
                .edit().remove("source_lang").apply()
        }
    }

    private fun updateState(code: LanguageCode, state: LanguagePackInstallState, isStt: Boolean) {
        if (isStt) {
            sttStates.update { it.toMutableMap().apply { put(code, state) } }
        } else {
            ttsStates.update { it.toMutableMap().apply { put(code, state) } }
        }
    }

    private fun updateProgress(code: LanguageCode, progress: Int?) {
        downloadProgress.update {
            val newMap = it.toMutableMap()
            if (progress != null) newMap[code] = progress else newMap.remove(code)
            newMap
        }
    }

    override fun refreshStates() {
        sttStates.value = buildInitialSttStates()
        ttsStates.value = buildInitialTtsStates()
    }
}
