package com.itantra.regression

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.itantra.core.crypto.SecureSessionManager
import com.itantra.core.inference.*
import com.itantra.core.location.*
import com.itantra.core.metrics.InMemoryMetricsRecorder
import com.itantra.core.profile.DeviceProfileManager
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.core.translation.*
import com.itantra.core.transport.*
import com.itantra.core.transport.packet.*
import com.itantra.data.db.*
import com.itantra.data.languagepack.MockLanguagePackRepository
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class BugfixFixture(
    val dao: MessageDao? = null,
    transportOverride: TransportEngine? = null,
    deviceId: String = "IT-LOCAL-0001",
    recognizerFactory: (LanguageCode) -> SpeechRecognizerEngine? = { null },
    microphoneFrames: Flow<FloatArray>? = null,
) : Closeable {
    val dir = kotlin.io.path.createTempDirectory("itantra-bugfix").toFile()
    private val preferenceValues = ConcurrentHashMap<String, String>()
    private val preferenceEditor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putString" -> { preferenceValues[args!![0] as String] = args[1] as String; proxy }
            "apply" -> null
            "commit" -> true
            else -> proxy
        }
    } as SharedPreferences.Editor
    private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString" -> when (args!![0]) {
                "device_id" -> deviceId
                else -> preferenceValues[args[0] as String] ?: args[1]
            }
            "edit" -> preferenceEditor
            else -> null
        }
    } as SharedPreferences
    val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
        override fun getContentResolver(): android.content.ContentResolver? = null
        override fun getSharedPreferences(name: String, mode: Int) = prefs
        override fun getSystemService(name: String): Any? = null
        override fun startService(intent: android.content.Intent): android.content.ComponentName? = null
    }
    val repository = MockLanguagePackRepository()
    val sent = CopyOnWriteArrayList<ItantraPacket>()
    var sendAction: suspend (ItantraPacket) -> TransmissionMetrics = {
        TransmissionMetrics(transmissionLatencyMillis = Measurement.Measured(12L))
    }
    val transport = transportOverride ?: object : TransportEngine {
        override val isConnected = true
        override val isServer = true
        override fun observeConnectionState(): Flow<ConnectionState> = emptyFlow()
        override suspend fun disconnect() {}
        override fun notifyAckReceived(messageId: Long) {}
        override fun receive(): Flow<ItantraPacket> = emptyFlow()
        override suspend fun send(packet: ItantraPacket): TransmissionMetrics {
            sent.add(packet)
            return sendAction(packet)
        }
    }
    val routes = CopyOnWriteArrayList<Pair<LanguageCode, LanguageCode>>()
    var translationAction: suspend (String, LanguageCode, LanguageCode) -> TranslationResult = { text, source, target ->
        TranslationResult(text, "translated", true, source, target)
    }
    val translation = object : TranslationEngine {
        override val isLoaded = true
        override val supportedSourceLanguages = LanguageCode.entries.toSet()
        override val supportedTargetLanguages = LanguageCode.entries.toSet()
        override fun init(modelsDir: File) {}
        override fun release() {}
        override suspend fun translate(text: String, sourceLang: LanguageCode, targetLang: LanguageCode): TranslationResult {
            routes.add(sourceLang to targetLang)
            return translationAction(text, sourceLang, targetLang)
        }
    }
    val spoken = CopyOnWriteArrayList<LanguageCode>()
    val speechRequests = CopyOnWriteArrayList<SpeechSynthesisRequest>()
    var ttsInstalled: (LanguageCode) -> Boolean = { true }
    val synthCalls = AtomicInteger()
    val session = ActiveLanguageSessionManager(object : EngineFactory {
        override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine? = recognizerFactory(language)
        override fun createSynthesizer(language: LanguageCode) = object : SpeechSynthesizerEngine {
            override val languageCode = language
            override var isLoaded = false
            override suspend fun load() { isLoaded = true }
            override suspend fun unload() { isLoaded = false }
            override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult {
                spoken.add(request.languageCode)
                speechRequests.add(request)
                synthCalls.incrementAndGet()
                // Exercise routing, without using Android AudioTrack in a host-side test.
                return SpeechSynthesisResult(request.correlationId, floatArrayOf(), 16000, 1, 0)
            }
        }
    })
    val secure = SecureSessionManager()
    val remote = SecureSessionManager()
    var location = LocationResult.Success(12.3456789123, 77.1234567891, 4.25f, 1700000000123L)
    var locationAction: suspend () -> LocationResult = { location }
    val coordinator = TransceiverCoordinator(context, session, repository, transport,
        InMemoryMetricsRecorder(), secure, TranslationRouter(translation), dao,
        DeviceProfileManager(context, com.itantra.core.storage.MemoryKeyProvider()), TtsCapabilityProvider { ttsInstalled(it) },
        object : LocationProvider { override suspend fun getCurrentLocation() = locationAction() }, microphoneFrames = microphoneFrames)

    fun verify() {
        val hello = secure.startHandshake(true)
        val response = remote.processSecureHello(hello)!!
        secure.processSecureHello(response)
        val a = secure.confirmSasMatch()
        val b = remote.confirmSasMatch()
        secure.processSecureVerify(b)
        remote.processSecureVerify(a)
    }
    suspend fun receive(packet: ItantraPacket) = coordinator.handleIncomingPacket(remote.encrypt(packet))
    fun add(message: TransceiverMessage) {
        coordinator.javaClass.getDeclaredMethod("addMessage", TransceiverMessage::class.java)
            .apply { isAccessible = true }.invoke(coordinator, message)
    }
    fun update(id: Long, transform: (TransceiverMessage) -> TransceiverMessage) {
        coordinator.javaClass.getDeclaredMethod("updateMessage", Long::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }.invoke(coordinator, id, transform)
    }
    fun nextId(): Long = coordinator.javaClass.getDeclaredMethod("nextMessageId")
        .apply { isAccessible = true }.invoke(coordinator) as Long
    fun field(name: String): Any? = coordinator.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(coordinator)
    suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(5) }
    }
    override fun close() { coordinator.shutdown(); dir.deleteRecursively() }
}

internal fun sampleMessage(id: Long, state: MessageState = MessageState.TRANSMITTING) =
    TransceiverMessage(id, LanguageCode.ENGLISH, priority = MessagePriority.NORMAL,
        text = "message $id", source = MessageSource.LOCAL, createdAtLocal = id, state = state)

internal class RecordingMessageDao : MessageDao {
    val rows = ConcurrentHashMap<Long, MessageEntity>()
    val writes = AtomicInteger()
    override fun insert(message: MessageEntity): Long {
        rows[message.messageId] = message
        writes.incrementAndGet()
        return message.messageId
    }
    override fun insertAll(messages: List<MessageEntity>) = messages.map { insert(it) }
    override fun getAll() = rows.values.filter { it.deletedAtMillis == null }
    override fun getAllIncludingTrash() = rows.values.toList()
    override fun observeAll(): Flow<List<MessageEntity>> = emptyFlow()
    override fun observeByPeerId(peerId: String): Flow<List<MessageEntity>> = emptyFlow()
    override fun getByPeerId(peerId: String) = rows.values.filter { it.peerId == peerId }
    override fun observeAllPeerIds(): Flow<List<String>> = emptyFlow()
    override fun delete(messageId: Long): Int = if (rows.remove(messageId) == null) 0 else 1
    override fun clear(): Int { val size = rows.size; rows.clear(); return size }
}
