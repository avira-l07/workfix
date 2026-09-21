package com.itantra.core.crypto

import kotlinx.coroutines.runBlocking

import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class SecureSessionManagerTest {

    private lateinit var alice: SecureSessionManager
    private lateinit var bob: SecureSessionManager

    @Before
    fun setup() {
        alice = SecureSessionManager()
        bob = SecureSessionManager()
    }

    @Test
    fun testSuccessfulHandshakeAndEncryption() = runBlocking {
        // 1. Alice starts handshake
        val aliceHello = alice.startHandshake(isInitiator = true)
        assertEquals(SecureSessionState.HANDSHAKING, alice.state.value)

        // 2. Bob receives Alice's hello
        val bobHello = bob.processSecureHello(aliceHello)
        assertNotNull(bobHello)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, bob.state.value)

        // 3. Alice receives Bob's hello
        val aliceResponse = alice.processSecureHello(bobHello!!)
        // Alice shouldn't send another hello because she already initiated
        assertEquals(null, aliceResponse)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, alice.state.value)

        // 4. Verify SAS match
        assertEquals(alice.sasCode.value, bob.sasCode.value)
        assertNotNull(alice.sasCode.value)

        // 5. User confirms on both
        val aliceConfirm = alice.confirmSasMatch()
        val bobConfirm = bob.confirmSasMatch()

        alice.processSecureVerify(bobConfirm)
        bob.processSecureVerify(aliceConfirm)

        assertEquals(SecureSessionState.SECURE_VERIFIED, alice.state.value)
        assertEquals(SecureSessionState.SECURE_VERIFIED, bob.state.value)

        // 6. Alice encrypts a packet
        val originalPacket = ItantraPacket(
            type = PacketType.TEXT,
            messageId = 12345L,
            payload = "Hello Secure World".toByteArray()
        )
        val encryptedPacket = alice.encrypt(originalPacket)
        assertEquals(1.toByte(), encryptedPacket.securityVersion)
        assertTrue(encryptedPacket.counter > 0)
        assertFalse(originalPacket.payload.contentEquals(encryptedPacket.payload.sliceArray(0..originalPacket.payload.size - 1)))

        // 7. Bob decrypts the packet
        val decryptedPacket = bob.decrypt(encryptedPacket)
        assertEquals(0.toByte(), decryptedPacket.securityVersion)
        assertArrayEquals(originalPacket.payload, decryptedPacket.payload)
    }

    @Test
    fun testReplayProtection() = runBlocking {
        // Setup secure session
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        bob.processSecureVerify(alice.confirmSasMatch())
        alice.processSecureVerify(bob.confirmSasMatch())

        val packet1 = ItantraPacket(type = PacketType.TEXT, messageId = 1L, payload = "Test".toByteArray())
        val encrypted1 = alice.encrypt(packet1)
        val decrypted1 = bob.decrypt(encrypted1)
        assertNotNull(decrypted1)

        // Attempt replay
        var exceptionThrown = false
        try {
            bob.decrypt(encrypted1)
        } catch (e: SecurityException) {
            exceptionThrown = true
        }
        assertTrue("Replay should throw SecurityException", exceptionThrown)
    }

    @Test
    fun testAadMetadataTampering() = runBlocking {
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        bob.processSecureVerify(alice.confirmSasMatch())
        alice.processSecureVerify(bob.confirmSasMatch())

        val originalPacket = ItantraPacket(type = PacketType.TEXT, messageId = 1L, payload = "Test".toByteArray(), sourceLanguage = com.itantra.domain.model.LanguageCode.HINDI)
        val encryptedPacket = alice.encrypt(originalPacket)

        // Tamper source language byte
        val tamperedPacket = encryptedPacket.copy(sourceLanguage = com.itantra.domain.model.LanguageCode.ENGLISH)

        var exceptionThrown = false
        try {
            bob.decrypt(tamperedPacket)
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assertTrue("Metadata tamper should throw Exception due to AAD mismatch", exceptionThrown)
    }

    @Test
    fun testLocalSasOnlyDoesNotVerify() = runBlocking {
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)

        // Alice confirms locally, Bob has NOT confirmed
        alice.confirmSasMatch()

        assertTrue(alice.localSasConfirmed)
        assertFalse(alice.peerSasConfirmed)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, alice.state.value)

        // App traffic should be rejected
        var encryptFailed = false
        try {
            alice.encrypt(ItantraPacket(type = PacketType.TEXT, messageId = 100L, payload = "Secret".toByteArray()))
        } catch (e: IllegalStateException) {
            encryptFailed = true
        }
        assertTrue("Cannot encrypt when only local SAS confirmed", encryptFailed)
    }

    @Test
    fun testPeerSasOnlyDoesNotVerify() = runBlocking {
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)

        // FIX 014: processSecureVerify rejects a packet without a valid transcript-bound token.
        // An empty/random payload means peerSasConfirmed stays false.
        val fakeVerifyPacket = ItantraPacket(type = PacketType.SECURE_VERIFY, messageId = 200L)
        alice.processSecureVerify(fakeVerifyPacket)

        assertFalse(alice.localSasConfirmed)
        // peerSasConfirmed must remain false because token is invalid
        assertFalse("Fake SECURE_VERIFY must not set peerSasConfirmed", alice.peerSasConfirmed)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, alice.state.value)

        // App traffic should be rejected
        var encryptFailed = false
        try {
            alice.encrypt(ItantraPacket(type = PacketType.TEXT, messageId = 201L, payload = "Secret".toByteArray()))
        } catch (e: IllegalStateException) {
            encryptFailed = true
        }
        assertTrue("Cannot encrypt when only peer SAS confirmed", encryptFailed)
    }

    @Test
    fun testRejectSasTransitionsToFailedAndResets() = runBlocking {
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)

        alice.rejectSas()

        assertEquals(SecureSessionState.FAILED, alice.state.value)
        assertFalse(alice.localSasConfirmed)
        assertFalse(alice.peerSasConfirmed)
        assertEquals(null, alice.sasCode.value)
    }

    @Test
    fun testReplayedSecureVerifyDoesNotCorruptState() = runBlocking {
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)

        val aliceVerify = alice.confirmSasMatch()
        val bobVerify = bob.confirmSasMatch()
        alice.processSecureVerify(bobVerify)
        bob.processSecureVerify(aliceVerify)

        assertEquals(SecureSessionState.SECURE_VERIFIED, alice.state.value)

        // Replay another SECURE_VERIFY
        alice.processSecureVerify(bobVerify)
        assertEquals(SecureSessionState.SECURE_VERIFIED, alice.state.value)
    }

    // ---- FIX 003 regression tests ----

    @Test
    fun testFix003_confirmSasMatch_throwsInNoSession() = runBlocking {
        // Alice hasn't started a handshake; state == NO_SESSION
        var threw = false
        try {
            alice.confirmSasMatch()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("confirmSasMatch must throw IllegalStateException in NO_SESSION", threw)
    }

    @Test
    fun testFix003_confirmSasMatch_throwsWhileHandshaking() = runBlocking {
        // Alice started handshake but hasn't received HELLO back; state == HANDSHAKING
        alice.startHandshake(isInitiator = true)
        assertEquals(SecureSessionState.HANDSHAKING, alice.state.value)
        var threw = false
        try {
            alice.confirmSasMatch()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("confirmSasMatch must throw IllegalStateException while HANDSHAKING", threw)
    }

    @Test
    fun testFix003_confirmSasMatch_succeedsInWaitingVerification() = runBlocking {
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, alice.state.value)

        // Should not throw
        val verifyPacket = alice.confirmSasMatch()
        assertEquals(PacketType.SECURE_VERIFY, verifyPacket.type)
        assertTrue("Token payload must be non-empty", verifyPacket.payload.isNotEmpty())
    }

    // ---- FIX 014 regression tests ----

    @Test
    fun testFix014_processSecureVerify_rejectsTokenFromDifferentSession() = runBlocking {
        // Complete Alice–Bob session
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        val aliceVerify = alice.confirmSasMatch()
        val bobVerify = bob.confirmSasMatch()

        // Create an unrelated "eve" session
        val eve = SecureSessionManager()
        val carolHelper = SecureSessionManager()
        val eveHello = eve.startHandshake(isInitiator = true)
        val carolHello = carolHelper.processSecureHello(eveHello)!!
        eve.processSecureHello(carolHello)
        val eveVerify = eve.confirmSasMatch()

        // Bob receives Alice's real verify — that should work
        bob.processSecureVerify(aliceVerify)
        // Alice receives Bob's real verify — that should work
        alice.processSecureVerify(bobVerify)
        assertEquals(SecureSessionState.SECURE_VERIFIED, alice.state.value)
        assertEquals(SecureSessionState.SECURE_VERIFIED, bob.state.value)

        // Now replay Eve's verify token into an already-verified Alice — should be ignored gracefully
        val alicePrevState = alice.state.value
        alice.processSecureVerify(eveVerify)
        assertEquals("State must not change on foreign verify token", alicePrevState, alice.state.value)
    }

    @Test
    fun testFix014_processSecureVerify_rejectsEmptyPayload() = runBlocking {
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, alice.state.value)

        val emptyVerify = ItantraPacket(type = PacketType.SECURE_VERIFY, messageId = 1L)
        alice.processSecureVerify(emptyVerify)

        // peerSasConfirmed must remain false; state must stay WAITING_USER_VERIFICATION
        assertFalse("Empty token must not set peerSasConfirmed", alice.peerSasConfirmed)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, alice.state.value)
    }

    @Test
    fun testFix014_transcriptBindingPreventsReplay() = runBlocking {
        // Full session: alice initiator, bob responder
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        val aliceVerify = alice.confirmSasMatch()
        val bobVerify = bob.confirmSasMatch()
        alice.processSecureVerify(bobVerify)
        bob.processSecureVerify(aliceVerify)
        assertEquals(SecureSessionState.SECURE_VERIFIED, alice.state.value)
        assertEquals(SecureSessionState.SECURE_VERIFIED, bob.state.value)

        // Reset Alice and replay the old bobVerify from the stale session
        alice.resetSession()
        val aliceHello2 = alice.startHandshake(isInitiator = true)
        val bobHello2 = bob.processSecureHello(aliceHello2) // bob is reset on its own session-level logic
        // Even if we managed to deliver the old verify, it must be rejected
        alice.processSecureVerify(bobVerify)
        assertFalse("Replayed stale verify must not set peerSasConfirmed", alice.peerSasConfirmed)
    }
}
