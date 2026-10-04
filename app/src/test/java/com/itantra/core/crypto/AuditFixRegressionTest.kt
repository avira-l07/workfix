package com.itantra.core.crypto

import com.itantra.core.transport.packet.*
import com.itantra.domain.model.LanguageCode
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuditFixRegressionTest {
    private fun pair(): Pair<SecureSessionManager, SecureSessionManager> {
        val a = SecureSessionManager(); val b = SecureSessionManager()
        a.processSecureHello(requireNotNull(b.processSecureHello(a.startHandshake(true))))
        val av = a.confirmSasMatch(); val bv = b.confirmSasMatch()
        a.processSecureVerify(bv); b.processSecureVerify(av)
        assertEquals(SecureSessionState.SECURE_VERIFIED, b.state.value)
        return a to b
    }
    private fun plain(bytes: ByteArray) = ItantraPacket(PacketType.TEXT,
        languageCode = LanguageCode.HINDI, messageId = 123L, payload = bytes)

    @Test fun `eight concurrent decrypts accept each authenticated counter exactly once`() = runBlocking {
        val (a, b) = pair(); val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(50) {
                val packet = a.encrypt(plain(ByteArray(8000) { 7 }))
                val barrier = CyclicBarrier(8)
                val jobs = (1..8).map { pool.submit<Boolean> {
                    barrier.await(10, TimeUnit.SECONDS)
                    try { b.decrypt(packet); true } catch (_: SecurityException) { false }
                } }
                assertEquals("Round $it", 1, jobs.count { it.get(15, TimeUnit.SECONDS) })
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `UTF8 boundary survives encryption framing and receiver decode`() = runBlocking<Unit> {
        val (a, b) = pair()
        val bytes = "न".repeat(PacketEncoder.MAX_PLAINTEXT_BYTES / 3).toByteArray(Charsets.UTF_8)
        assertEquals(PacketEncoder.MAX_PLAINTEXT_BYTES, bytes.size)
        val packet = a.encrypt(plain(bytes))
        assertEquals(PacketDecoder.MAX_PAYLOAD_SIZE, packet.payload.size)
        val decoded = PacketDecoder.decode(PacketEncoder.encode(packet).drop(4).toByteArray())
        assertArrayEquals(bytes, b.decrypt(decoded).payload)
        assertThrows(PayloadTooLargeException::class.java) {
            runBlocking { a.encrypt(plain(bytes + byteArrayOf(1))) }
        }
        val next = a.encrypt(plain("🙂".toByteArray()))
        assertEquals(packet.counter + 1, next.counter)
        assertArrayEquals("🙂".toByteArray(), b.decrypt(next).payload)
        assertThrows(PayloadTooLargeException::class.java) {
            PacketEncoder.encode(plain(ByteArray(PacketDecoder.MAX_PAYLOAD_SIZE + 1)))
        }
    }

    @Test fun `malformed profiles cannot create a peer identity`() {
        val valid = ProfilePayload(deviceId = "IT-7F3A-91C2", displayName = "Responder",
            supportedLanguages = listOf(LanguageCode.HINDI, LanguageCode.ENGLISH))
        assertEquals(valid, ProfilePayload.fromBytes(valid.toBytes()))
        for (bad in listOf("{}", "", "{", 
            String(valid.copy(protocolVersion = 999).toBytes()),
            String(valid.copy(deviceId = "").toBytes()),
            String(valid.copy(deviceId = "unverified-address").toBytes()),
            String(valid.copy(displayName = " ").toBytes()),
            String(valid.copy(displayName = "bad\nname").toBytes()),
            String(valid.copy(displayName = "x".repeat(129)).toBytes()),
            String(valid.copy(supportedLanguages = emptyList()).toBytes()),
            String(valid.copy(supportedLanguages = listOf(LanguageCode.HINDI, LanguageCode.HINDI)).toBytes()),
            String(valid.toBytes()).replace("\"hi\"", "\"xx\""))) {
            assertNull(bad, ProfilePayload.fromBytes(bad.toByteArray()))
        }
        assertNull(ProfilePayload.fromBytes(ByteArray(2049)))
        assertNull(ProfilePayload.fromBytes(String(valid.toBytes()).replace("\"v\":1", "\"v\":1.5").toByteArray()))
        assertNull(ProfilePayload.fromBytes(String(valid.toBytes()).replace("\"v\":1", "\"v\":\"1\"").toByteArray()))
    }
}
