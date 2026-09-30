package com.itantra.data.languagepack

import android.content.Context
import android.os.StatFs
import com.itantra.core.inference.ModelFileSpecs
import com.itantra.core.inference.AdditionalSttModel
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
        com.itantra.domain.model.SpeechInputMode.fromSavedValue(
            context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("speech_input_mode", null)
        )
    )
    private val manualSttLanguage = MutableStateFlow<LanguageCode?>(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE).getString("manual_stt_lang", null)?.let { LanguageCode.fromWireCode(it) }
    )
    private val enabledMicLanguages = MutableStateFlow<Set<LanguageCode>>(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE)
            .getStringSet("enabled_mic_langs", setOf("hi", "en"))
            ?.mapNotNull { LanguageCode.fromWireCode(it) }?.toSet()
            ?.ifEmpty { setOf(LanguageCode.HINDI, LanguageCode.ENGLISH) }
            ?: setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
    )
    private val enabledListenLanguages = MutableStateFlow<Set<LanguageCode>>(
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE)
            .getStringSet("enabled_listen_langs", setOf("hi", "en"))
            ?.mapNotNull { LanguageCode.fromWireCode(it) }?.toSet()
            ?.ifEmpty { setOf(LanguageCode.HINDI, LanguageCode.ENGLISH) }
            ?: setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
    )
    private val downloadProgress = MutableStateFlow<Map<LanguageCode, Int>>(emptyMap())
    private val downloadJobs = mutableMapOf<LanguageCode, Job>()
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()
    private val sharedSttMutex = kotlinx.coroutines.sync.Mutex()

    // Small bundled manifests remain available even when model weights are downloaded later.
    private val expectedSizes by lazy {
        LanguageCatalog.all.associate { lang ->
            val manifest = runCatching {
                context.assets.open("language_packs/${lang.code.wireCode}_dev_manifest.json")
                    .bufferedReader().use { manifestParser.parseOrNull(it.readText()) }
            }.getOrNull()
            lang.code to (manifest?.sttModel?.sizeBytes to manifest?.ttsModel?.sizeBytes)
        }
    }

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
                val additionalModel = AdditionalSttModel.forLanguage(lang.code)
                val additionalReady = additionalModel != null && AdditionalSttModel.isInstalled(
                    requireNotNull(storage.packDirectory(lang.code).parentFile), additionalModel
                )
                val sttState = when {
                    additionalModel != null && additionalReady -> LanguagePackInstallState.INSTALLED
                    additionalModel != null -> when (currentStt[lang.code]) {
                        LanguagePackInstallState.DOWNLOADING, LanguagePackInstallState.ERROR,
                        LanguagePackInstallState.CORRUPTED -> currentStt.getValue(lang.code)
                        else -> LanguagePackInstallState.NOT_INSTALLED
                    }
                    else -> currentStt[lang.code] ?: LanguagePackInstallState.NOT_INSTALLED
                }
                val ttsState = currentTts[lang.code] ?: LanguagePackInstallState.NOT_INSTALLED
                val isFullyInstalled = (sttState == LanguagePackInstallState.INSTALLED && ttsState == LanguagePackInstallState.INSTALLED)

                val availability = when {
                    active == lang.code -> LanguagePackAvailability.ACTIVE
                    isFullyInstalled -> LanguagePackAvailability.DOWNLOADED
                    else -> LanguagePackAvailability.AVAILABLE
                }

                var sttSize = 0L
                if (additionalModel != null) {
                    sttSize = additionalModel.totalBytes
                } else if (sttState == LanguagePackInstallState.INSTALLED) {
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
                    sttSizeBytes = if (sttSize > 0) sttSize else expectedSizes[lang.code]?.first,
                    ttsSizeBytes = if (ttsSize > 0) ttsSize else expectedSizes[lang.code]?.second,
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
        val packsRoot = requireNotNull(storage.packDirectory(code).parentFile)
        val sttReady = AdditionalSttModel.forLanguage(code)?.let { AdditionalSttModel.isInstalled(packsRoot, it) }
            ?: storage.isSharedSttInstalled()
        if (!sttReady) return false
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

    override fun observeEnabledMicLanguages(): Flow<Set<LanguageCode>> = enabledMicLanguages.asStateFlow()
    override fun getEnabledMicLanguages(): Set<LanguageCode> = enabledMicLanguages.value

    override suspend fun setEnabledMicLanguages(languages: Set<LanguageCode>) {
        enabledMicLanguages.value = languages
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("enabled_mic_langs", languages.map { it.wireCode }.toSet())
            .apply()
    }

    override fun observeEnabledListenLanguages(): Flow<Set<LanguageCode>> = enabledListenLanguages.asStateFlow()
    override fun getEnabledListenLanguages(): Set<LanguageCode> = enabledListenLanguages.value

    override suspend fun setEnabledListenLanguages(languages: Set<LanguageCode>) {
        enabledListenLanguages.value = languages
        context.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("enabled_listen_langs", languages.map { it.wireCode }.toSet())
            .apply()
    }

    override suspend fun startDownload(code: LanguageCode) = startDownloadComponents(code, stt = true, tts = true)

    override suspend fun startDownloadComponents(code: LanguageCode, stt: Boolean, tts: Boolean) {
        if (!stt && !tts) return
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
                val additionalModel = AdditionalSttModel.forLanguage(code)
                val needSharedStt = stt && additionalModel == null && !isSharedSttReady()
                val needAdditionalStt = stt && additionalModel != null && !AdditionalSttModel.isInstalled(
                    requireNotNull(storage.packDirectory(code).parentFile), additionalModel
                )
                val needTts = tts && !isTtsReady(code)

                if (needSharedStt) {
                    LanguageCatalog.all.filter { AdditionalSttModel.forLanguage(it.code) == null }
                        .forEach { updateState(it.code, LanguagePackInstallState.DOWNLOADING, true) }
                }
                if (needAdditionalStt) updateState(code, LanguagePackInstallState.DOWNLOADING, true)
                if (needTts) updateState(code, LanguagePackInstallState.DOWNLOADING, false)
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
                        (if (needAdditionalStt) additionalModel!!.totalBytes else 0L) +
                        (if (needTts) manifest.ttsModel.sizeBytes else 0L) + 50_000_000L

                    if (availableBytes < requiredBytes) {
                        if (needSharedStt) {
                            LanguageCatalog.all.filter { AdditionalSttModel.forLanguage(it.code) == null }
                                .forEach { updateState(it.code, LanguagePackInstallState.ERROR, true) }
                        }
                        if (needAdditionalStt) updateState(code, LanguagePackInstallState.ERROR, true)
                        if (needTts) updateState(code, LanguagePackInstallState.ERROR, false)
                        updateProgress(code, null)
                        return@launch
                    }

                    val totalExpectedBytes = (if (needSharedStt) manifest.sttModel.sizeBytes else 0L) +
                        (if (needAdditionalStt) additionalModel!!.totalBytes else 0L) +
                        (if (needTts) manifest.ttsModel.sizeBytes else 0L)
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
                                LanguageCatalog.all.filter { AdditionalSttModel.forLanguage(it.code) == null }
                                    .forEach { updateState(it.code, LanguagePackInstallState.INSTALLED, true) }
                            }
                        }
                    }

                    if (needAdditionalStt) {
                        val model = requireNotNull(additionalModel)
                        val stagedSpeech = File(tmpDir, "${code.wireCode}_stt")
                        check(stagedSpeech.mkdirs()) { "Cannot stage ${code.name} speech download" }
                        for ((name, expected) in model.files) {
                            val targetFile = File(stagedSpeech, name)
                            downloadFile(model.downloadUrlFor(name), targetFile, onBytesRead)
                            check(targetFile.length() == expected.bytes) { "Incomplete ${code.name} speech download: $name" }
                            verifySha256(targetFile, expected.sha256)
                        }
                        AdditionalSttModel.installDownloaded(parentDir, model, stagedSpeech)
                    }

                    // Download TTS only when missing, preserving an existing pack.
                    if (needTts) {
                        val ttsUrlBase = manifest.ttsModel.downloadUrl ?: throw Exception("No TTS URL")
                        val tmpTts = File(tmpDir, "tts")
                        check(tmpTts.mkdirs()) { "Cannot stage TTS download" }
                        for (file in ttsSpec.requiredFiles) {
                            val targetFile = File(tmpTts, file)
                            downloadFile("$ttsUrlBase/$file", targetFile, onBytesRead)
                            val expectedSha = manifest.ttsModel.checksumsSha256[file]
                            if (!expectedSha.isNullOrEmpty()) verifySha256(targetFile, expectedSha)
                        }
                        val ttsDest = File(rootDir, "tts")
                        safeAtomicMove(tmpTts, ttsDest)
                        File(ttsDest, ".verified_v1").writeText(manifest.packVersion)
                    }

                    updateProgress(code, 100)
                    if (needAdditionalStt) updateState(code, LanguagePackInstallState.INSTALLED, true)
                    if (needTts) updateState(code, LanguagePackInstallState.INSTALLED, false)

                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (needSharedStt) {
                        val sttOk = isSharedSttReady()
                        LanguageCatalog.all.filter { AdditionalSttModel.forLanguage(it.code) == null }.forEach {
                            updateState(it.code, if (sttOk) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED, true)
                        }
                    }
                    if (needAdditionalStt) updateState(
                        code,
                        if (AdditionalSttModel.isInstalled(parentDir, requireNotNull(additionalModel)))
                            LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED,
                        true,
                    )
                    if (needTts) updateState(code, if (isTtsReady(code)) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (needSharedStt) {
                        LanguageCatalog.all.filter { AdditionalSttModel.forLanguage(it.code) == null }
                            .forEach { updateState(it.code, LanguagePackInstallState.ERROR, true) }
                    }
                    if (needAdditionalStt) updateState(code, LanguagePackInstallState.ERROR, true)
                    if (needTts) updateState(code, LanguagePackInstallState.ERROR, false)
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

    internal suspend fun downloadFile(
        urlStr: String,
        dest: File,
        onBytesRead: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        var currentUrl = urlStr
        var connection: HttpURLConnection? = null
        var redirects = 0
        val maxRedirects = 10

        try {
            while (true) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                connection = conn

                val responseCode = conn.responseCode
                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    connection = null
                    if (location.isNullOrEmpty()) {
                        throw Exception("HTTP $responseCode redirect without Location header from $currentUrl")
                    }
                    redirects++
                    if (redirects > maxRedirects) {
                        throw Exception("Too many redirects ($redirects) starting from $urlStr")
                    }
                    currentUrl = URL(url, location).toExternalForm()
                    continue
                }

                if (responseCode !in 200..299) {
                    throw Exception("HTTP Error $responseCode for $currentUrl")
                }

                break
            }

            val finalConn = requireNotNull(connection)
            val tempDest = File(dest.absolutePath + ".part")
            finalConn.inputStream.use { input ->
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
                throw Exception("Downloaded file is 0 bytes: $currentUrl")
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

        // Dedicated manual STT lives in shared/ and is outside the language's
        // TTS directory. Remove only this language's checked model directory.
        val packsRoot = requireNotNull(storage.packDirectory(code).parentFile).canonicalFile
        AdditionalSttModel.forLanguage(code)?.let { model ->
            val target = model.directory(packsRoot).canonicalFile
            check(target != packsRoot && target.toPath().startsWith(packsRoot.toPath()))
            if (target.exists()) check(target.deleteRecursively()) { "Could not delete ${code.name} STT pack" }
        }

        // Shared STT remains untouched: verify actual disk state
        val sharedReady = isSharedSttReady()
        val sttState = if (sharedReady) LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED
        LanguageCatalog.all.forEach { language ->
            val dedicated = AdditionalSttModel.forLanguage(language.code)
            val state = if (dedicated != null) {
                if (AdditionalSttModel.isInstalled(packsRoot, dedicated))
                    LanguagePackInstallState.INSTALLED else LanguagePackInstallState.NOT_INSTALLED
            } else sttState
            updateState(language.code, state, true)
        }

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
