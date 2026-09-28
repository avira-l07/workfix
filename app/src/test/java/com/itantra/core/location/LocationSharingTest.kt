package com.itantra.core.location

import android.content.ContextWrapper
import android.location.Location
import android.location.LocationListener
import android.os.Looper
import com.example.itantra.ui.screens.chat.DateUtils
import com.itantra.core.crypto.SecureSessionManager
import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.EngineFactory
import com.itantra.core.inference.SpeechRecognizerEngine
import com.itantra.core.inference.SpeechSynthesizerEngine
import com.itantra.core.metrics.InMemoryMetricsRecorder
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.core.translation.TranslationEngine
import com.itantra.core.translation.TranslationResult
import com.itantra.core.translation.TranslationRouter
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportEngine
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.LocationPayload
import com.itantra.core.transport.packet.PacketDecoder
import com.itantra.core.transport.packet.PacketEncoder
import com.itantra.core.transport.packet.PacketType
import com.itantra.data.db.toEntity
import com.itantra.data.languagepack.MockLanguagePackRepository
import com.itantra.domain.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Day 4 Unit Tests: GPS location sharing (sender-side).
 *
 * Verifies:
 * 1. LocationPayload compact 28-byte binary codec round-trip with byte-for-byte fidelity and bounds checking.
 * 2. PacketEncoder.extractAad() inclusion of PacketType.LOCATION (byte 5 == 15).
 * 3. SecureSessionManager AES-256-GCM encryption/decryption round-trip with authenticated AAD for LOCATION packets.
 * 4. TransceiverCoordinator.sendLocationMessage flow across Success, PermissionDenied, ProviderDisabled, and NoFixAvailable states.
 * 5. DefaultGpsLocationProvider lifecycle leak-free cleanup across all exit paths (success, timeout, external cancellation, mid-flight revocation).
 */
class LocationSharingTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class FakeLocationServiceAdapter : LocationServiceAdapter {
        var isEnabled: Boolean = true
        var lastKnownLocation: Location? = null
        var registeredListener: LocationListener? = null
        var removeUpdatesCallCount = 0
        var requestUpdatesCallCount = 0
        var throwOnRemoveUpdates: Throwable? = null

        override fun isProviderEnabled(provider: String): Boolean = isEnabled
        override fun getLastKnownLocation(provider: String): Location? = lastKnownLocation
        override fun requestLocationUpdates(
            provider: String,
            minTimeMs: Long,
            minDistanceM: Float,
            listener: LocationListener,
            looper: Looper?
        ) {
            requestUpdatesCallCount++
            registeredListener = listener
        }
        override fun removeUpdates(listener: LocationListener) {
            removeUpdatesCallCount++
            registeredListener = null
            throwOnRemoveUpdates?.let { throw it }
        }
    }

    private class FakeLocationProvider(var resultToReturn: LocationResult) : LocationProvider {
        var callCount = 0
        override suspend fun getCurrentLocation(): LocationResult {
            callCount++
            return resultToReturn
        }
    }

    private class FakeTransportEngine : TransportEngine {
        override val isConnected: Boolean = true
        override val isServer: Boolean = true
        val sentPackets = java.util.concurrent.CopyOnWriteArrayList<ItantraPacket>()

        override fun observeConnectionState(): Flow<ConnectionState> = flowOf(ConnectionState.CONNECTED)
        override suspend fun disconnect() {}
        override fun notifyAckReceived(messageId: Long) {}

        override suspend fun send(packet: ItantraPacket): TransmissionMetrics {
            sentPackets.add(packet)
            return TransmissionMetrics(
                packetBytes = Measurement.Measured(packet.payload.size),
                transmissionLatencyMillis = Measurement.Measured(12L)
            )
        }

        override fun receive(): Flow<ItantraPacket> = emptyFlow()
    }

    private class DummyStorage : LanguagePackStorage {
        override fun packDirectory(code: LanguageCode): File = File("dummy/${code.wireCode}")
        override fun isInstalled(code: LanguageCode): Boolean = true
        override suspend fun verifyChecksums(code: LanguageCode, expectedChecksums: Map<String, String>): Boolean = true
        override suspend fun deletePack(code: LanguageCode) {}
        override fun totalInstalledBytes(): Long = 0L
    }

    private class DummyRecognizer(override val languageCode: LanguageCode) : SpeechRecognizerEngine {
        override var isLoaded: Boolean = true
        override suspend fun load() { isLoaded = true }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult =
            SpeechRecognitionResult(
                languageCode = languageCode,
                text = "Dummy",
                confidence = 1.0f,
                isFinal = true,
                timestampMillis = System.currentTimeMillis(),
                pureInferenceMs = 10L
            )
        override suspend fun reset() {}
        override suspend fun unload() { isLoaded = false }
    }

    private class DummyTranslationEngine : TranslationEngine {
        override val isLoaded: Boolean = true
        override val supportedSourceLanguages: Set<LanguageCode> = LanguageCatalog.all.map { it.code }.toSet()
        override val supportedTargetLanguages: Set<LanguageCode> = LanguageCatalog.all.map { it.code }.toSet()
        override fun init(modelsDir: File) {}
        override fun release() {}
        override suspend fun translate(text: String, sourceLang: LanguageCode, targetLang: LanguageCode): TranslationResult =
            TranslationResult(text, text, true, sourceLang, targetLang)
    }

    private fun createDummyContext(hasLocationPermission: Boolean = true): android.content.Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = tempFolder.root
        override fun getContentResolver(): android.content.ContentResolver? = null
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            return if (hasLocationPermission) android.content.pm.PackageManager.PERMISSION_GRANTED
            else android.content.pm.PackageManager.PERMISSION_DENIED
        }
        override fun checkSelfPermission(permission: String): Int {
            return if (hasLocationPermission) android.content.pm.PackageManager.PERMISSION_GRANTED
            else android.content.pm.PackageManager.PERMISSION_DENIED
        }
    }

    private fun createSessionManager(): ActiveLanguageSessionManager {
        val engineFactory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode, autoDetect: Boolean, targetLanguage: LanguageCode?): SpeechRecognizerEngine =
                DummyRecognizer(language)
            override fun createRecognizer(language: LanguageCode, autoDetect: Boolean): SpeechRecognizerEngine =
                DummyRecognizer(language)
            override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine =
                DummyRecognizer(language)
            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine? = null
        }
        val sm = ActiveLanguageSessionManager(engineFactory = engineFactory)
        runBlocking { sm.ensureStt(LanguageCode.ENGLISH) }
        return sm
    }

    private fun setupVerifiedSessionPair(): Pair<SecureSessionManager, SecureSessionManager> {
        val alice = SecureSessionManager()
        val bob = SecureSessionManager()
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        val aliceVerify = alice.confirmSasMatch()
        val bobVerify = bob.confirmSasMatch()
        alice.processSecureVerify(bobVerify)
        bob.processSecureVerify(aliceVerify)
        assertEquals(SecureSessionState.SECURE_VERIFIED, alice.state.value)
        assertEquals(SecureSessionState.SECURE_VERIFIED, bob.state.value)
        return Pair(alice, bob)
    }

    // =========================================================================
    // 1. LocationPayload Codec Tests
    // =========================================================================

    @Test
    fun testLocationPayloadRoundTripPositive() {
        val original = LocationPayload(
            latitude = 12.9715987,
            longitude = 77.5945627,
            accuracyMeters = 4.2f,
            timestampMillis = 1727456789123L
        )

        val bytes = original.toBytes()
        assertEquals(28, bytes.size)
        assertEquals(LocationPayload.PAYLOAD_SIZE, bytes.size)

        val decoded = LocationPayload.fromBytes(bytes)
        assertEquals(original.latitude, decoded.latitude, 0.0000001)
        assertEquals(original.longitude, decoded.longitude, 0.0000001)
        assertEquals(original.accuracyMeters, decoded.accuracyMeters, 0.001f)
        assertEquals(original.timestampMillis, decoded.timestampMillis)
    }

    @Test
    fun testLocationPayloadRoundTripNegativeAndBounds() {
        val original = LocationPayload(
            latitude = -33.8688197,
            longitude = -151.2092955,
            accuracyMeters = 15.8f,
            timestampMillis = 1700000000000L
        )

        val bytes = original.toBytes()
        val decoded = LocationPayload.fromBytes(bytes)
        assertEquals(original.latitude, decoded.latitude, 0.0000001)
        assertEquals(original.longitude, decoded.longitude, 0.0000001)
        assertEquals(original.accuracyMeters, decoded.accuracyMeters, 0.001f)
        assertEquals(original.timestampMillis, decoded.timestampMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testLocationPayloadTooShortThrows() {
        val truncated = ByteArray(27)
        LocationPayload.fromBytes(truncated)
    }

    // =========================================================================
    // 2. PacketEncoder & AAD Extraction Tests
    // =========================================================================

    @Test
    fun testPacketEncoderExtractAadByte5IsLocationId() {
        val payload = LocationPayload(13.0, 77.0, 5.0f, 1000L).toBytes()
        val packet = ItantraPacket(
            type = PacketType.LOCATION,
            flags = MessagePriority.NORMAL.toByte(),
            messageId = 42L,
            languageCode = LanguageCode.ENGLISH,
            payload = payload
        )

        val aad = PacketEncoder.extractAad(packet)
        assertEquals(28, aad.size)

        // Wire format AAD: 0..3 MAGIC, 4 VERSION (2), 5 TYPE
        assertEquals(2.toByte(), aad[4])
        assertEquals(PacketType.LOCATION.id, aad[5])
        assertEquals(15.toByte(), aad[5])
    }

    @Test
    fun testLocationPacketFramingRoundTrip() {
        val locationPayload = LocationPayload(12.34567, 76.54321, 3.5f, 987654321L)
        val packet = ItantraPacket(
            type = PacketType.LOCATION,
            flags = MessagePriority.CRITICAL.toByte(),
            messageId = 99999L,
            languageCode = LanguageCode.ENGLISH,
            payload = locationPayload.toBytes()
        )

        val framedBytes = PacketEncoder.encode(packet)
        val buffer = ByteBuffer.wrap(framedBytes).order(ByteOrder.BIG_ENDIAN)
        val frameLength = buffer.getInt()
        val frameData = ByteArray(frameLength)
        buffer.get(frameData)

        val decoded = PacketDecoder.decode(frameData)
        assertEquals(PacketType.LOCATION, decoded.type)
        assertEquals(MessagePriority.CRITICAL.toByte(), decoded.flags)
        assertEquals(99999L, decoded.messageId)

        val decodedLocation = LocationPayload.fromBytes(decoded.payload)
        assertEquals(locationPayload.latitude, decodedLocation.latitude, 0.000001)
        assertEquals(locationPayload.longitude, decodedLocation.longitude, 0.000001)
        assertEquals(locationPayload.accuracyMeters, decodedLocation.accuracyMeters, 0.01f)
        assertEquals(locationPayload.timestampMillis, decodedLocation.timestampMillis)
    }

    // =========================================================================
    // 3. Cryptographic Authenticated Encryption / Decryption Round-Trip
    // =========================================================================

    @Test
    fun testLocationPacketAesGcmEncryptionDecryptionRoundTrip() = runBlocking {
        val (alice, bob) = setupVerifiedSessionPair()

        val location = LocationPayload(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracyMeters = 8.0f,
            timestampMillis = 1727450000000L
        )

        val plainPacket = ItantraPacket(
            type = PacketType.LOCATION,
            flags = MessagePriority.NORMAL.toByte(),
            messageId = 12345L,
            languageCode = LanguageCode.ENGLISH,
            payload = location.toBytes()
        )

        val encryptedPacket = alice.encrypt(plainPacket)
        assertEquals(1.toByte(), encryptedPacket.securityVersion)
        assertEquals(PacketType.LOCATION, encryptedPacket.type)
        assertFalse(plainPacket.payload.contentEquals(encryptedPacket.payload))
        // Ciphertext includes 16-byte GCM authentication tag
        assertEquals(28 + 16, encryptedPacket.payload.size)

        val decryptedPacket = bob.decrypt(encryptedPacket)
        assertEquals(PacketType.LOCATION, decryptedPacket.type)
        assertEquals(plainPacket.messageId, decryptedPacket.messageId)
        assertArrayEquals(plainPacket.payload, decryptedPacket.payload)

        val recoveredLocation = LocationPayload.fromBytes(decryptedPacket.payload)
        assertEquals(location.latitude, recoveredLocation.latitude, 0.000001)
        assertEquals(location.longitude, recoveredLocation.longitude, 0.000001)
        assertEquals(location.accuracyMeters, recoveredLocation.accuracyMeters, 0.01f)
    }

    // =========================================================================
    // 4. TransceiverCoordinator Location Sharing Flow Tests
    // =========================================================================

    private fun createCoordinator(
        locationProvider: LocationProvider,
        transportEngine: FakeTransportEngine,
        secureSessionManager: SecureSessionManager
    ): TransceiverCoordinator {
        val context = createDummyContext()
        val sessionManager = createSessionManager()
        val repository = MockLanguagePackRepository()
        val metricsRecorder = InMemoryMetricsRecorder()
        val translationEngine = DummyTranslationEngine()
        val translationRouter = TranslationRouter(translationEngine)

        return TransceiverCoordinator(
            context = context,
            sessionManager = sessionManager,
            languagePackRepository = repository,
            transportEngine = transportEngine,
            metricsRecorder = metricsRecorder,
            secureSessionManager = secureSessionManager,
            translationRouter = translationRouter,
            locationProvider = locationProvider
        )
    }

    @Test
    fun testSendLocationMessageSuccess() = runBlocking {
        val (aliceSession, _) = setupVerifiedSessionPair()
        val transportEngine = FakeTransportEngine()
        val locationProvider = FakeLocationProvider(
            LocationResult.Success(
                latitude = 12.9716,
                longitude = 77.5946,
                accuracyMeters = 5.0f,
                timestampMillis = 1727456789000L
            )
        )

        val coordinator = createCoordinator(locationProvider, transportEngine, aliceSession)
        coordinator.sendLocationMessage(targetPeerId = "peer-bob")

        // Allow coroutine to complete
        kotlinx.coroutines.delay(200)

        assertEquals(1, locationProvider.callCount)
        val locationPackets = transportEngine.sentPackets.filter { it.type == PacketType.LOCATION }
        assertEquals(1, locationPackets.size)

        val sent = locationPackets.first()
        assertEquals(PacketType.LOCATION, sent.type)
        assertEquals(1.toByte(), sent.securityVersion) // Encrypted

        val messages = coordinator.messages.value
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertTrue(msg.state == MessageState.DELIVERED || msg.state == MessageState.SENT)
        assertTrue(msg.text.contains("12.97160"))
        assertTrue(msg.text.contains("77.59460"))
        assertTrue(msg.text.contains("5.0m"))
        assertEquals("peer-bob", msg.peerId)
    }

    @Test
    fun testSendLocationMessagePermissionDenied() = runBlocking {
        val (aliceSession, _) = setupVerifiedSessionPair()
        val transportEngine = FakeTransportEngine()
        val locationProvider = FakeLocationProvider(LocationResult.Failure.PermissionDenied)

        val coordinator = createCoordinator(locationProvider, transportEngine, aliceSession)
        coordinator.sendLocationMessage(targetPeerId = "peer-bob")

        kotlinx.coroutines.delay(200)

        assertEquals(1, locationProvider.callCount)
        val locationPackets = transportEngine.sentPackets.filter { it.type == PacketType.LOCATION }
        assertEquals(0, locationPackets.size) // No LOCATION packet sent on permission error

        val messages = coordinator.messages.value
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(MessageState.ERROR, msg.state)
        assertEquals("Location permission denied", msg.statusDetail)
        assertTrue(msg.text.contains("Location permission denied"))
    }

    @Test
    fun testSendLocationMessageGpsDisabled() = runBlocking {
        val (aliceSession, _) = setupVerifiedSessionPair()
        val transportEngine = FakeTransportEngine()
        val locationProvider = FakeLocationProvider(LocationResult.Failure.ProviderDisabled)

        val coordinator = createCoordinator(locationProvider, transportEngine, aliceSession)
        coordinator.sendLocationMessage(targetPeerId = "peer-bob")

        kotlinx.coroutines.delay(200)

        assertEquals(1, locationProvider.callCount)
        val locationPackets = transportEngine.sentPackets.filter { it.type == PacketType.LOCATION }
        assertEquals(0, locationPackets.size)

        val messages = coordinator.messages.value
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(MessageState.ERROR, msg.state)
        assertEquals("GPS is turned off", msg.statusDetail)
        assertTrue(msg.text.contains("GPS is turned off"))
    }

    @Test
    fun testSendLocationMessageNoFixAvailable() = runBlocking {
        val (aliceSession, _) = setupVerifiedSessionPair()
        val transportEngine = FakeTransportEngine()
        val locationProvider = FakeLocationProvider(LocationResult.Failure.NoFixAvailable)

        val coordinator = createCoordinator(locationProvider, transportEngine, aliceSession)
        coordinator.sendLocationMessage(targetPeerId = "peer-bob")

        kotlinx.coroutines.delay(200)

        assertEquals(1, locationProvider.callCount)
        val locationPackets = transportEngine.sentPackets.filter { it.type == PacketType.LOCATION }
        assertEquals(0, locationPackets.size)

        val messages = coordinator.messages.value
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(MessageState.ERROR, msg.state)
        assertEquals("GPS fix unavailable (no satellite lock)", msg.statusDetail)
        assertTrue(msg.text.contains("no satellite lock"))
    }

    // =========================================================================
    // 5. DefaultGpsLocationProvider Leak-Free Lifecycle Tests
    // =========================================================================

    @Test
    fun testDefaultGpsLocationProviderExternalCancellationRemovesUpdates() = runBlocking {
        val fakeService = FakeLocationServiceAdapter()
        val context = createDummyContext()
        val provider = DefaultGpsLocationProvider(
            context = context,
            fixTimeoutMillis = 10_000L,
            serviceAdapterOverride = fakeService,
            permissionChecker = { _, _ -> true }
        )

        val job = launch {
            provider.getCurrentLocation()
        }

        // Wait until requestLocationUpdates is called and listener registered
        var attempts = 0
        while (fakeService.registeredListener == null && attempts < 50) {
            delay(10)
            attempts++
        }

        assertNotNull("Listener should be registered while acquiring fix", fakeService.registeredListener)
        assertEquals(0, fakeService.removeUpdatesCallCount)

        // Simulate external cancellation (e.g. user leaves screen or message cancelled)
        job.cancel()
        job.join()

        // Verify removeUpdates was called on cancellation
        assertEquals(1, fakeService.removeUpdatesCallCount)
        assertNull("Listener reference must be cleaned up", fakeService.registeredListener)
    }

    @Test
    fun testDefaultGpsLocationProviderTimeoutRemovesUpdates() = runBlocking {
        val fakeService = FakeLocationServiceAdapter()
        val context = createDummyContext()
        // 50ms short timeout to exercise timeout path deterministically
        val provider = DefaultGpsLocationProvider(
            context = context,
            fixTimeoutMillis = 50L,
            serviceAdapterOverride = fakeService,
            permissionChecker = { _, _ -> true }
        )

        val result = provider.getCurrentLocation()
        assertTrue(result is LocationResult.Failure.NoFixAvailable)
        assertEquals(1, fakeService.removeUpdatesCallCount)
        assertNull(fakeService.registeredListener)
    }

    @Test
    fun testDefaultGpsLocationProviderSuccessRemovesUpdates() = runBlocking {
        val fakeService = FakeLocationServiceAdapter()
        val context = createDummyContext()
        val provider = DefaultGpsLocationProvider(
            context = context,
            fixTimeoutMillis = 10_000L,
            serviceAdapterOverride = fakeService,
            permissionChecker = { _, _ -> true }
        )

        val deferred = async {
            provider.getCurrentLocation()
        }

        var attempts = 0
        while (fakeService.registeredListener == null && attempts < 50) {
            delay(10)
            attempts++
        }
        assertNotNull("Listener should be registered", fakeService.registeredListener)

        val mockLocation = Location("gps").apply {
            latitude = 19.0760
            longitude = 72.8777
            accuracy = 3.0f
            time = 1727450000000L
        }

        fakeService.registeredListener!!.onLocationChanged(mockLocation)

        val result = deferred.await()
        assertTrue(result is LocationResult.Success)

        // Verify listener was immediately cleaned up on fix
        assertEquals(1, fakeService.removeUpdatesCallCount)
        assertNull(fakeService.registeredListener)
    }

    @Test
    fun testDefaultGpsLocationProviderPermissionRevocationDuringCleanupDoesNotCrash() = runBlocking {
        val fakeService = FakeLocationServiceAdapter().apply {
            throwOnRemoveUpdates = SecurityException("Permission revoked by user")
        }
        val context = createDummyContext()
        val provider = DefaultGpsLocationProvider(
            context = context,
            fixTimeoutMillis = 50L,
            serviceAdapterOverride = fakeService,
            permissionChecker = { _, _ -> true }
        )

        // Should NOT throw SecurityException; safeRemoveUpdates absorbs it gracefully
        val result = provider.getCurrentLocation()
        assertTrue(result is LocationResult.Failure.NoFixAvailable)
        assertEquals(1, fakeService.removeUpdatesCallCount)
    }

    @Test
    fun testDefaultGpsLocationProviderPermissionDeniedFailsEarlyWithoutRegistering() = runBlocking {
        val fakeService = FakeLocationServiceAdapter()
        val context = createDummyContext()
        val provider = DefaultGpsLocationProvider(
            context = context,
            fixTimeoutMillis = 10_000L,
            serviceAdapterOverride = fakeService,
            permissionChecker = { _, _ -> false }
        )

        val result = provider.getCurrentLocation()
        assertTrue(result is LocationResult.Failure.PermissionDenied)
        assertEquals(0, fakeService.requestUpdatesCallCount)
        assertNull(fakeService.registeredListener)
    }

    // =========================================================================
    // 6. Day 5: Receiver-Side Location Decoding, Validation, and Loopback Tests
    // =========================================================================

    @Test
    fun testReceiverDecodesValidLocationPacketAndStoresIt() = runBlocking {
        val (aliceSession, bobSession) = setupVerifiedSessionPair()
        val bobTransport = FakeTransportEngine()
        val bobLocationProvider = FakeLocationProvider(LocationResult.Failure.ProviderDisabled)
        val bobCoordinator = createCoordinator(bobLocationProvider, bobTransport, bobSession)

        val now = System.currentTimeMillis()
        val validPayload = LocationPayload(
            latitude = 13.0827,
            longitude = 80.2707,
            accuracyMeters = 4.2f,
            timestampMillis = now - 5000L
        )

        val plainPacket = ItantraPacket(
            type = PacketType.LOCATION,
            flags = MessagePriority.NORMAL.toByte(),
            messageId = 88881L,
            languageCode = LanguageCode.ENGLISH,
            payload = validPayload.toBytes()
        )

        val encryptedPacket = aliceSession.encrypt(plainPacket)
        bobCoordinator.handleIncomingPacket(encryptedPacket)

        var attempts = 0
        while (bobCoordinator.messages.value.isEmpty() && attempts < 50) {
            delay(10)
            attempts++
        }

        val messages = bobCoordinator.messages.value
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(88881L, msg.messageId)
        assertEquals(MessageSource.REMOTE, msg.source)
        assertTrue(msg.isLocation)
        assertTrue(msg.isLocationMessage)
        assertEquals(13.0827, msg.latitude!!, 0.0001)
        assertEquals(80.2707, msg.longitude!!, 0.0001)
        assertEquals(4.2f, msg.accuracyMeters!!, 0.1f)
        assertEquals(now - 5000L, msg.locationTimestampMillis)
        assertEquals(MessageState.DELIVERED, msg.state)
        assertTrue(msg.text.contains("13.08270"))
        assertTrue(msg.text.contains("80.27070"))
        assertTrue(msg.text.contains("4.2m"))

        // Confirm immediate ACK was transmitted
        var ackAttempts = 0
        while (bobTransport.sentPackets.none { it.type == PacketType.ACK } && ackAttempts < 50) {
            delay(10)
            ackAttempts++
        }
        val ackEncrypted = bobTransport.sentPackets.first { it.type == PacketType.ACK }
        val ackDecrypted = aliceSession.decrypt(ackEncrypted)
        assertEquals(PacketType.ACK, ackDecrypted.type)
        assertEquals(88881L, ackDecrypted.messageId)
    }

    @Test
    fun testMalformedLocationPayloadsAreDroppedWithoutCrashing() = runBlocking {
        val (aliceSession, bobSession) = setupVerifiedSessionPair()
        val bobTransport = FakeTransportEngine()
        val bobLocationProvider = FakeLocationProvider(LocationResult.Failure.ProviderDisabled)
        val bobCoordinator = createCoordinator(bobLocationProvider, bobTransport, bobSession)

        val now = System.currentTimeMillis()

        // Case 1: Short payload (10 bytes)
        val shortPacket = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1001L,
            payload = ByteArray(10)
        ))
        bobCoordinator.handleIncomingPacket(shortPacket)

        // Case 2: Excessively long payload (40 bytes)
        val longPacket = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1002L,
            payload = ByteArray(40)
        ))
        bobCoordinator.handleIncomingPacket(longPacket)

        // Case 3: Out of range latitude > 90
        val latHigh = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1003L,
            payload = LocationPayload(90.1, 77.0, 5f, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(latHigh)

        // Case 4: Out of range latitude < -90
        val latLow = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1004L,
            payload = LocationPayload(-90.1, 77.0, 5f, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(latLow)

        // Case 5: Out of range longitude > 180
        val lonHigh = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1005L,
            payload = LocationPayload(12.0, 180.1, 5f, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(lonHigh)

        // Case 6: Out of range longitude < -180
        val lonLow = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1006L,
            payload = LocationPayload(12.0, -180.1, 5f, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(lonLow)

        // Case 7: Latitude NaN
        val latNaN = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1007L,
            payload = LocationPayload(Double.NaN, 77.0, 5f, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(latNaN)

        // Case 8: Longitude Infinity
        val lonInf = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1008L,
            payload = LocationPayload(12.0, Double.POSITIVE_INFINITY, 5f, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(lonInf)

        // Case 9: Negative accuracy
        val accNeg = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1009L,
            payload = LocationPayload(12.0, 77.0, -1f, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(accNeg)

        // Case 10: Accuracy NaN
        val accNaN = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 1010L,
            payload = LocationPayload(12.0, 77.0, Float.NaN, now).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(accNaN)

        delay(50)
        // All malformed packets dropped without crashing; 0 messages added
        assertTrue(bobCoordinator.messages.value.isEmpty())
    }

    @Test
    fun testStaleOrFutureLocationTimestampsAreStoredWithTimeUnverifiedFlag() = runBlocking {
        val (aliceSession, bobSession) = setupVerifiedSessionPair()
        val bobTransport = FakeTransportEngine()
        val bobLocationProvider = FakeLocationProvider(LocationResult.Failure.ProviderDisabled)
        val bobCoordinator = createCoordinator(bobLocationProvider, bobTransport, bobSession)

        val now = System.currentTimeMillis()

        // 1. Future timestamp (> 5 minutes in future, e.g. +10 min)
        val futureTime = now + 10 * 60 * 1000L
        val futurePkt = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 2001L,
            payload = LocationPayload(12.0, 77.0, 5f, futureTime).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(futurePkt)

        // 2. Negative / zero timestamp
        val zeroPkt = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 2002L,
            payload = LocationPayload(12.0, 77.0, 5f, 0L).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(zeroPkt)

        // 3. Excessively stale timestamp (> 7 days ago, e.g. -10 days)
        val staleTime = now - 10 * 24 * 60 * 60 * 1000L
        val stalePkt = aliceSession.encrypt(ItantraPacket(
            type = PacketType.LOCATION,
            messageId = 2003L,
            payload = LocationPayload(12.0, 77.0, 5f, staleTime).toBytes()
        ))
        bobCoordinator.handleIncomingPacket(stalePkt)

        delay(50)

        // Must NOT be dropped: All 3 valid locations stored with isTimeUnverified = true
        val messages = bobCoordinator.messages.value
        assertEquals("Must store all 3 messages with timestamp issues rather than dropping", 3, messages.size)

        val futureMsg = messages.first { it.messageId == 2001L }
        assertTrue("Message must be flagged as location", futureMsg.isLocation)
        assertTrue("Future timestamp message must have isTimeUnverified=true", futureMsg.isTimeUnverified)
        assertEquals("TIME_UNVERIFIED", futureMsg.statusDetail)
        assertEquals(futureTime, futureMsg.locationTimestampMillis)

        val zeroMsg = messages.first { it.messageId == 2002L }
        assertTrue("Message must be flagged as location", zeroMsg.isLocation)
        assertTrue("Zero timestamp message must have isTimeUnverified=true", zeroMsg.isTimeUnverified)
        assertEquals("TIME_UNVERIFIED", zeroMsg.statusDetail)
        assertEquals(0L, zeroMsg.locationTimestampMillis)

        val staleMsg = messages.first { it.messageId == 2003L }
        assertTrue("Message must be flagged as location", staleMsg.isLocation)
        assertTrue("Stale timestamp message must have isTimeUnverified=true", staleMsg.isTimeUnverified)
        assertEquals("TIME_UNVERIFIED", staleMsg.statusDetail)
        assertEquals(staleTime, staleMsg.locationTimestampMillis)
    }

    @Test
    fun testTimeUnverifiedFlagRenderingOnBubble() {
        val now = System.currentTimeMillis()
        val futureTime = now + 10 * 60 * 1000L
        val staleTime = now - 10 * 24 * 60 * 60 * 1000L

        // When isTimeUnverified = true, formatLocationAgeLabel must return "Time unverified"
        assertEquals("Time unverified", DateUtils.formatLocationAgeLabel(futureTime, isTimeUnverified = true, now))
        assertEquals("Time unverified", DateUtils.formatLocationAgeLabel(0L, isTimeUnverified = true, now))
        assertEquals("Time unverified", DateUtils.formatLocationAgeLabel(staleTime, isTimeUnverified = true, now))

        // When isTimeUnverified = true, formatLocationBubbleTime must visibly display "Time unverified"
        val futureBubbleText = DateUtils.formatLocationBubbleTime(futureTime, isTimeUnverified = true, now)
        assertTrue("Bubble text must contain 'Time unverified'", futureBubbleText.contains("Time unverified"))

        val zeroBubbleText = DateUtils.formatLocationBubbleTime(0L, isTimeUnverified = true, now)
        assertEquals("Time unverified", zeroBubbleText)

        val staleBubbleText = DateUtils.formatLocationBubbleTime(staleTime, isTimeUnverified = true, now)
        assertTrue("Bubble text must contain 'Time unverified'", staleBubbleText.contains("Time unverified"))

        // Verified timestamp must render normal age label (e.g., "Just now" or "2 min ago"), NOT "Time unverified"
        val normalTime = now - 2 * 60 * 1000L
        val verifiedAgeLabel = DateUtils.formatLocationAgeLabel(normalTime, isTimeUnverified = false, now)
        assertEquals("2 min ago", verifiedAgeLabel)
        assertFalse(verifiedAgeLabel.contains("Time unverified"))
    }

    @Test
    fun testEndToEndLoopbackLocationMessageAliceToBob() = runBlocking {
        val (aliceSession, bobSession) = setupVerifiedSessionPair()
        val aliceTransport = FakeTransportEngine()
        val bobTransport = FakeTransportEngine()

        val fixTime = System.currentTimeMillis() - 2000L
        val aliceLocationProvider = FakeLocationProvider(
            LocationResult.Success(
                latitude = 12.9716,
                longitude = 77.5946,
                accuracyMeters = 5.0f,
                timestampMillis = fixTime
            )
        )
        val bobLocationProvider = FakeLocationProvider(LocationResult.Failure.ProviderDisabled)

        val aliceCoordinator = createCoordinator(aliceLocationProvider, aliceTransport, aliceSession)
        val bobCoordinator = createCoordinator(bobLocationProvider, bobTransport, bobSession)

        // 1. Alice sends location message targeted to Bob
        aliceCoordinator.sendLocationMessage(targetPeerId = "BOB_PEER")

        var attempts = 0
        while (aliceTransport.sentPackets.none { it.type == PacketType.LOCATION } && attempts < 50) {
            delay(10)
            attempts++
        }
        val encryptedPacketFromAlice = aliceTransport.sentPackets.first { it.type == PacketType.LOCATION }
        assertEquals(PacketType.LOCATION, encryptedPacketFromAlice.type)

        // 2. Transmit over simulated P2P link to Bob
        bobCoordinator.handleIncomingPacket(encryptedPacketFromAlice)

        var bobAttempts = 0
        while (bobCoordinator.messages.value.isEmpty() && bobAttempts < 50) {
            delay(10)
            bobAttempts++
        }

        // 3. Verify Bob decoded and stored the location message
        val bobMessages = bobCoordinator.messages.value
        assertEquals(1, bobMessages.size)
        val bobMsg = bobMessages.first()
        assertEquals(MessageSource.REMOTE, bobMsg.source)
        assertTrue(bobMsg.isLocation)
        assertTrue(bobMsg.isLocationMessage)
        assertEquals(12.9716, bobMsg.latitude!!, 0.0001)
        assertEquals(77.5946, bobMsg.longitude!!, 0.0001)
        assertEquals(5.0f, bobMsg.accuracyMeters!!, 0.1f)
        assertEquals(fixTime, bobMsg.locationTimestampMillis)
        assertEquals(MessageState.DELIVERED, bobMsg.state)

        // 4. Verify Bob generated an ACK packet
        var ackAttempts = 0
        while (bobTransport.sentPackets.none { it.type == PacketType.ACK } && ackAttempts < 50) {
            delay(10)
            ackAttempts++
        }
        val ackFromBob = bobTransport.sentPackets.first { it.type == PacketType.ACK }

        // 5. Deliver ACK back to Alice
        aliceCoordinator.handleIncomingPacket(ackFromBob)

        var aliceAckAttempts = 0
        while (aliceCoordinator.messages.value.first().state != MessageState.DELIVERED && aliceAckAttempts < 50) {
            delay(10)
            aliceAckAttempts++
        }
        val aliceMsg = aliceCoordinator.messages.value.first()
        assertEquals(MessageState.DELIVERED, aliceMsg.state)
        assertTrue(aliceMsg.isLocationMessage)
    }

    @Test
    fun testMessageEntityLosslessMappingForLocation() {
        val original = TransceiverMessage(
            messageId = 55555L,
            language = LanguageCode.ENGLISH,
            priority = MessagePriority.NORMAL,
            text = "📍 Location: 12.97160, 77.59460 (±5.0m)",
            source = MessageSource.REMOTE,
            createdAtLocal = 1727456789000L,
            state = MessageState.DELIVERED,
            peerId = "PEER_ALICE",
            senderDeviceId = "ALICE_DEV",
            receiverDeviceId = "BOB_DEV",
            isLocation = true,
            latitude = 12.9716,
            longitude = 77.5946,
            accuracyMeters = 5.0f,
            locationTimestampMillis = 1727456780000L
        )

        val entity = original.toEntity()
        assertTrue(entity.isLocation)
        assertEquals(12.9716, entity.latitude!!, 0.0001)
        assertEquals(77.5946, entity.longitude!!, 0.0001)
        assertEquals(5.0f, entity.accuracyMeters!!, 0.01f)
        assertEquals(1727456780000L, entity.locationTimestampMillis)

        val restored = entity.toDomain()
        assertTrue(restored.isLocation)
        assertTrue(restored.isLocationMessage)
        assertEquals(original.messageId, restored.messageId)
        assertEquals(original.latitude!!, restored.latitude!!, 0.0001)
        assertEquals(original.longitude!!, restored.longitude!!, 0.0001)
        assertEquals(original.accuracyMeters!!, restored.accuracyMeters!!, 0.01f)
        assertEquals(original.locationTimestampMillis, restored.locationTimestampMillis)
    }

    @Test
    fun testDateUtilsRelativeAgeFormatting() {
        val now = 1727456789000L
        assertEquals("Just now", com.example.itantra.ui.screens.chat.DateUtils.formatRelativeAge(now - 10_000L, now))
        assertEquals("1 min ago", com.example.itantra.ui.screens.chat.DateUtils.formatRelativeAge(now - 70_000L, now))
        assertEquals("5 min ago", com.example.itantra.ui.screens.chat.DateUtils.formatRelativeAge(now - 300_000L, now))
        assertEquals("1 hr ago", com.example.itantra.ui.screens.chat.DateUtils.formatRelativeAge(now - 3_600_000L, now))
        assertEquals("3 hr ago", com.example.itantra.ui.screens.chat.DateUtils.formatRelativeAge(now - 3 * 3_600_000L, now))
        assertEquals("1 day ago", com.example.itantra.ui.screens.chat.DateUtils.formatRelativeAge(now - 25 * 3_600_000L, now))
        assertEquals("3 days ago", com.example.itantra.ui.screens.chat.DateUtils.formatRelativeAge(now - 3 * 24 * 3_600_000L, now))
    }
}
