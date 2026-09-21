package com.itantra.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class WordErrorRateCalculatorTest {

    @Test
    fun `test perfect match`() {
        val result = WordErrorRateCalculator.calculate("मुख्य सड़क बंद है", "मुख्य सड़क बंद है")
        assertEquals(4, result.referenceWordCount)
        assertEquals(0, result.substitutions)
        assertEquals(0, result.deletions)
        assertEquals(0, result.insertions)
        assertEquals(0f, result.wer)
    }

    @Test
    fun `test one deletion`() {
        val result = WordErrorRateCalculator.calculate("मुख्य सड़क बंद है", "मुख्य सड़क बंद")
        assertEquals(4, result.referenceWordCount)
        assertEquals(0, result.substitutions)
        assertEquals(1, result.deletions)
        assertEquals(0, result.insertions)
        assertEquals(0.25f, result.wer)
    }

    @Test
    fun `test one insertion`() {
        val result = WordErrorRateCalculator.calculate("मुख्य सड़क बंद है", "मुख्य सड़क बंद है क्या")
        assertEquals(4, result.referenceWordCount)
        assertEquals(0, result.substitutions)
        assertEquals(0, result.deletions)
        assertEquals(1, result.insertions)
        assertEquals(0.25f, result.wer)
    }

    @Test
    fun `test one substitution`() {
        val result = WordErrorRateCalculator.calculate("मुख्य सड़क बंद है", "मुख्य रास्ता बंद है")
        assertEquals(4, result.referenceWordCount)
        assertEquals(1, result.substitutions)
        assertEquals(0, result.deletions)
        assertEquals(0, result.insertions)
        assertEquals(0.25f, result.wer)
    }

    @Test
    fun `test normalization punctuation removal`() {
        // Punctuation should be ignored
        val result = WordErrorRateCalculator.calculate("मुख्य सड़क बंद है।", "मुख्य सड़क बंद है?")
        assertEquals(0f, result.wer)
    }

    @Test
    fun `test code switch lowercase`() {
        val result = WordErrorRateCalculator.calculate("Rescue team भेजो", "rescue team भेजो")
        assertEquals(0f, result.wer)
    }

    @Test
    fun `test completely wrong`() {
        val result = WordErrorRateCalculator.calculate("बाढ़ का पानी", "आग लगी है")
        // बाढ़ (sub) का (sub) पानी (sub)
        // reference: 3 words
        // hypothesis: 3 words
        assertEquals(3, result.referenceWordCount)
        assertEquals(3, result.substitutions)
        assertEquals(1.0f, result.wer)
    }

    @Test
    fun `test character error rate known pair single word`() {
        // "cat" vs "hat"
        // Reference: 3 chars ('c', 'a', 't')
        // Hypothesis: 3 chars ('h', 'a', 't')
        // Substitutions: 1 ('c' -> 'h'), Deletions: 0, Insertions: 0
        // Expected CER: 1 / 3 = 0.33333334f
        // Contrast with WER which is 1.0f (1 / 1)
        val werResult = WordErrorRateCalculator.calculate("cat", "hat")
        val cerResult = WordErrorRateCalculator.calculateCer("cat", "hat")

        assertEquals(1.0f, werResult.wer, 0.001f)
        assertEquals(3, cerResult.referenceCharCount)
        assertEquals(1, cerResult.substitutions)
        assertEquals(0, cerResult.deletions)
        assertEquals(0, cerResult.insertions)
        assertEquals(1.0f / 3.0f, cerResult.cer, 0.0001f)
    }

    @Test
    fun `test character error rate multi word deletions`() {
        // Reference: "hello world" -> 11 characters ('h','e','l','l','o',' ','w','o','r','l','d')
        // Hypothesis: "helo word"   -> 9 characters  ('h','e','l','o',' ','w','o','r','d')
        // Expected: 2 deletions (1 'l' from hello, 1 'l' from world), 0 substitutions, 0 insertions
        // Expected CER: 2 / 11 = 0.18181819f
        val cerResult = WordErrorRateCalculator.calculateCer("hello world", "helo word")
        assertEquals(11, cerResult.referenceCharCount)
        assertEquals(0, cerResult.substitutions)
        assertEquals(2, cerResult.deletions)
        assertEquals(0, cerResult.insertions)
        assertEquals(2.0f / 11.0f, cerResult.cer, 0.0001f)
    }

    @Test
    fun `test character error rate hindi substitution`() {
        // "पानी" (4 code points: प, ान, ी) vs "नानी"
        // Reference: 4 chars
        // Substitutions: 1 ('प' -> 'न')
        // Expected CER: 1 / 4 = 0.25f, while WER is 1 / 1 = 1.0f
        val cerResult = WordErrorRateCalculator.calculateCer("पानी", "नानी")
        assertEquals(4, cerResult.referenceCharCount)
        assertEquals(1, cerResult.substitutions)
        assertEquals(0, cerResult.deletions)
        assertEquals(0, cerResult.insertions)
        assertEquals(0.25f, cerResult.cer, 0.0001f)
    }
}
