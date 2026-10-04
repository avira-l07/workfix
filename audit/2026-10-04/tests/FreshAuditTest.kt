package audit

import com.itantra.core.crypto.*
import com.itantra.core.transport.packet.*
import com.itantra.core.storage.*
import com.itantra.core.inference.AdditionalSttModel
import com.itantra.domain.model.LanguageCode
import com.example.itantra.ui.screens.voicenotes.*
import com.itantra.data.db.AppDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.CyclicBarrier
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Fresh audit probes. OBSERVED lines describe actual behavior, including defects; no old tests used. */
class FreshAuditTest {
    private fun body(p: ItantraPacket) = PacketEncoder.encode(p).drop(4).toByteArray()
    private fun plain(payload: ByteArray = "Hello नमस्ते 🙂".toByteArray()) = ItantraPacket(
        PacketType.TEXT, languageCode = LanguageCode.HINDI, sourceLanguage = LanguageCode.HINDI,
        targetLanguage = LanguageCode.ENGLISH, messageId = 1234567L, payload = payload)
    private fun pair(): Pair<SecureSessionManager, SecureSessionManager> {
        val a=SecureSessionManager(); val b=SecureSessionManager()
        a.processSecureHello(requireNotNull(b.processSecureHello(a.startHandshake(true))))
        assertEquals(a.sasCode.value, b.sasCode.value)
        val av=a.confirmSasMatch(); val bv=b.confirmSasMatch()
        a.processSecureVerify(bv); b.processSecureVerify(av)
        assertEquals(SecureSessionState.SECURE_VERIFIED, a.state.value)
        assertEquals(SecureSessionState.SECURE_VERIFIED, b.state.value)
        return a to b
    }

    @Test fun packetRoundTripAndCrcFuzz() {
        for (language in LanguageCode.entries) {
            val p=plain().copy(languageCode=language,sourceLanguage=language)
            assertEquals(p, PacketDecoder.decode(body(p)))
        }
        val data=body(plain())
        for(i in data.indices) {
            val altered=data.copyOf().apply { this[i]=(this[i].toInt() xor 1).toByte() }
            assertThrows(PacketDecodeException::class.java) { PacketDecoder.decode(altered) }
        }
        for(n in 0..32) assertThrows(PacketDecodeException::class.java) { PacketDecoder.decode(ByteArray(n)) }
        println("PASS packet UTF-8/emoji ten-language round-trip and ${data.size+33} malformed/corrupted frames")
    }

    @Test fun sasTamperReplayAndReset() = runBlocking {
        val(a,b)=pair(); val encrypted=a.encrypt(plain())
        assertThrows(SecurityException::class.java) { b.decrypt(encrypted.copy(messageId=999L)) }
        assertArrayEquals(plain().payload, b.decrypt(PacketDecoder.decode(body(encrypted))).payload)
        assertThrows(SecurityException::class.java) { b.decrypt(encrypted) }
        a.resetSession()
        assertThrows(IllegalStateException::class.java) { runBlocking { a.encrypt(plain()) } }
        println("PASS SAS agreement, AAD tampering, replay rejection, session reset")
    }

    @Test fun unauthenticatedSessionCannotEncrypt() = runBlocking {
        val a=SecureSessionManager(); val b=SecureSessionManager()
        a.processSecureHello(requireNotNull(b.processSecureHello(a.startHandshake(true))))
        assertThrows(IllegalStateException::class.java) { runBlocking { a.encrypt(plain()) } }
        a.processSecureVerify(ItantraPacket(PacketType.SECURE_VERIFY,messageId=2,payload=ByteArray(32)))
        assertFalse(a.peerSasConfirmed)
        println("PASS application traffic blocked before mutual SAS, fabricated verify rejected")
    }

    @Test fun concurrentTransmitCountersAreUnique() = runBlocking {
        val(a,b)=pair()
        val packets=(1..200).map { async(Dispatchers.Default) { a.encrypt(plain()) } }.awaitAll()
        assertEquals(200,packets.map{it.counter}.toSet().size)
        packets.sortedBy{it.counter}.forEach { assertArrayEquals(plain().payload,b.decrypt(it).payload) }
        println("PASS 200 concurrent encryption counters unique")
    }

    @Test fun concurrentReplayProbe() = runBlocking {
        val(a,b)=pair(); val pool=Executors.newFixedThreadPool(8)
        var duplicateAcceptances=0
        try {
            repeat(50) {
                val p=a.encrypt(plain(ByteArray(8000){7})); val barrier=CyclicBarrier(8)
                val jobs=(1..8).map { pool.submit<Boolean> { barrier.await(); runCatching { b.decrypt(p) }.isSuccess } }
                duplicateAcceptances += (jobs.count{it.get()}-1).coerceAtLeast(0)
            }
        } finally { pool.shutdownNow() }
        println("OBSERVED concurrent replay extra acceptances=$duplicateAcceptances; direct API probe, production receive dispatch separately reviewed")
    }

    @Test fun encryptedPayloadBoundaryProbe() = runBlocking {
        val(a,b)=pair()
        val oversized=a.encrypt(plain(ByteArray(PacketDecoder.MAX_PAYLOAD_SIZE)))
        assertThrows(PacketDecodeException::class.java) { PacketDecoder.decode(body(oversized)) }
        val fits=a.encrypt(plain(ByteArray(PacketDecoder.MAX_PAYLOAD_SIZE-16)))
        assertEquals(PacketDecoder.MAX_PAYLOAD_SIZE-16,b.decrypt(PacketDecoder.decode(body(fits))).payload.size)
        println("OBSERVED plaintext=16384 bytes -> encrypted=16400 rejected; 16368-byte plaintext passes; outgoing encoder has no size guard")
    }

    @Test fun invalidLocationAndProfileProbe() {
        val invalid=LocationPayload(Double.NaN,181.0f.toDouble(),-1f,-1)
        assertTrue(LocationPayload.fromBytes(invalid.toBytes()).latitude.isNaN())
        assertEquals(181.0,LocationPayload.fromBytes(invalid.toBytes()+byteArrayOf(0)).longitude,0.0)
        val profile=requireNotNull(ProfilePayload.fromBytes("{}".toByteArray()))
        assertEquals("",profile.deviceId)
        assertEquals(999,requireNotNull(ProfilePayload.fromBytes("{\"v\":999,\"id\":\"x\"}".toByteArray())).protocolVersion)
        println("OBSERVED location NaN/out-of-range longitude/negative accuracy/time/trailing byte accepted; blank profile ID and v999 accepted")
    }

    private class Keys : KeyProvider {
        private val values=mutableMapOf<String,SecretKey>()
        override fun getOrCreate(alias:String)=values.getOrPut(alias){SecretKeySpec(ByteArray(32){(it+1).toByte()},"AES")}
        override fun getExisting(alias:String)=values[alias]
        override fun delete(alias:String){values.remove(alias)}
    }
    @Test fun storedCipherAuthenticationAndLostKey() {
        val keys=Keys(); val c=StoredDataCipher(keys,"audit"); val p="private transcript".toByteArray()
        val first=c.encrypt(p); val second=c.encrypt(p)
        assertFalse(first.contentEquals(second)); assertArrayEquals(p,c.decrypt(first))
        assertThrows(StoredDataUnavailable::class.java){c.decrypt(first.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})}
        keys.delete("audit"); assertThrows(StoredDataUnavailable::class.java){c.decrypt(first)}
        println("PASS at-rest JCA round-trip, distinct nonce, tamper and lost-key rejection; Android keystore NOT tested")
    }

    @Test fun pinnedModelRejectsCorruptStaging() {
        val root=File.createTempFile("itantra-audit","").apply{delete();mkdirs()}
        try {
            val model=requireNotNull(AdditionalSttModel.forLanguage(LanguageCode.MALAYALAM))
            val stage=File(root,"stage").apply{mkdirs()}; File(stage,"model.int8.onnx").writeText("bad")
            assertThrows(IllegalStateException::class.java){AdditionalSttModel.installDownloaded(root,model,stage)}
            assertFalse(AdditionalSttModel.isInstalled(root,model))
            println("PASS corrupt staged model fails readiness without promotion")
        } finally { root.deleteRecursively() }
    }

    @Test fun localDateBoundaryProbe() {
        val zone=ZoneId.of("Asia/Kolkata"); val now=LocalDate.of(2026,10,15).atStartOfDay(zone).toInstant().toEpochMilli()
        fun date(days:Long)=LocalDate.of(2026,10,15).minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(VoiceNoteBucket.TODAY,VoiceNoteGrouping.keyFor(date(0),now,zone).bucket)
        assertEquals(VoiceNoteBucket.YESTERDAY,VoiceNoteGrouping.keyFor(date(1),now,zone).bucket)
        assertEquals(VoiceNoteBucket.THIS_WEEK,VoiceNoteGrouping.keyFor(date(6),now,zone).bucket)
        println("OBSERVED seven-days-old note bucket=${VoiceNoteGrouping.keyFor(date(7),now,zone).bucket}; requested rolling seven days normally covers offsets 0..6")
        assertEquals(VoiceNoteBucket.THIS_MONTH,VoiceNoteGrouping.keyFor(date(10),now,zone).bucket)
        assertEquals(VoiceNoteBucket.OLDER,VoiceNoteGrouping.keyFor(date(40),now,zone).bucket)
        println("PASS today/yesterday/6 days/10 days/older local-device-date boundaries")
    }

    @Test fun migrationSqlAndIndependentDelete() {
        val connection=java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            val schema=org.json.JSONObject(File(System.getProperty("itantra.audit.root"),"app/schemas/com.itantra.data.db.AppDatabase/2.json").readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i)
                connection.createStatement().use{it.execute(e.getString("createSql").replace("\u0024{TABLE_NAME}",e.getString("tableName")))}
            }
            val proxy=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) { _,method,args ->
                if(method.name=="execSQL") connection.createStatement().use{it.execute(args!![0] as String)}
                null
            } as SupportSQLiteDatabase
            listOf(AppDatabase.MIGRATION_2_3,AppDatabase.MIGRATION_3_4,AppDatabase.MIGRATION_4_5).forEach{it.migrate(proxy)}
            connection.createStatement().use { s ->
                s.execute("INSERT INTO voice_notes(transcribedText,languageWireCode,createdAtMillis) VALUES ('audit','ml',123)")
                s.execute("INSERT INTO peers VALUES('p','peer','address','Bluetooth',123)")
                s.execute("DELETE FROM voice_notes WHERE id=1")
                s.executeQuery("SELECT COUNT(*) FROM voice_notes").use{assertTrue(it.next());assertEquals(0,it.getInt(1))}
                s.executeQuery("SELECT COUNT(*) FROM peers").use{assertTrue(it.next());assertEquals(1,it.getInt(1))}
            }
            println("PASS actual migration SQL 2->5 executed on fresh SQLite; voice-note delete preserves peer row; v1 fixture missing; Room/SQLCipher Android integration NOT tested")
        } finally {connection.close()}
    }
}
