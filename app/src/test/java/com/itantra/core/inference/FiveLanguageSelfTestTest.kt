package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiveLanguageSelfTestTest {
    @Test fun `ten scripts are accepted and romanized output is rejected`() {
        val native = mapOf(
            LanguageCode.HINDI to "मदद चाहिए",
            LanguageCode.ENGLISH to "We need help",
            LanguageCode.TAMIL to "உதவி தேவை",
            LanguageCode.TELUGU to "సహాయం కావాలి",
            LanguageCode.ODIA to "ସାହାଯ୍ୟ ଆବଶ୍ୟକ",
            LanguageCode.BENGALI to "সাহায্য দরকার",
            LanguageCode.GUJARATI to "મદદ જોઈએ છે",
            LanguageCode.MARATHI to "मदत हवी आहे",
            LanguageCode.MALAYALAM to "സഹായം വേണം",
            LanguageCode.KANNADA to "ಸಹಾಯ ಬೇಕು",
        )
        assertEquals(FiveLanguageSelfTest.languages.toSet(), native.keys)
        native.forEach { (code, text) ->
            assertEquals(code.name, "Native script", FiveLanguageSelfTest.scriptStatus(code, text))
            if (code != LanguageCode.ENGLISH) {
                assertEquals(code.name, "Wrong or romanized script",
                    FiveLanguageSelfTest.scriptStatus(code, "We need help"))
            }
            assertEquals(code.name, "Empty", FiveLanguageSelfTest.scriptStatus(code, ""))
        }
    }

    @Test fun `ten bundled recordings match their source hashes`() {
        val root = File("src/main/assets/benchmark/five_self_test")
        val rows = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonArray
        assertEquals(10, rows.size)
        assertEquals(setOf("hi", "en", "ta", "te", "or", "bn", "gu", "mr", "ml", "kn"), rows.map {
            it.jsonObject.getValue("language").jsonPrimitive.content
        }.toSet())
        rows.forEach { row ->
            val code = row.jsonObject.getValue("language").jsonPrimitive.content
            val expected = row.jsonObject.getValue("sha256").jsonPrimitive.content
            val audio = File(root, "$code.wav")
            assertTrue(audio.isFile && audio.length() > 44)
            val actual = MessageDigest.getInstance("SHA-256").digest(audio.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(code, expected, actual)
        }
    }
}
