package com.itantra.core.inference

/**
 * Fallback transliterator for Romanized Hindi text into Devanagari script.
 *
 * When Whisper-tiny transcribes Hindi speech into Latin characters (e.g. "me kya karau"),
 * this engine converts it into Devanagari ("मैं क्या करूँ").
 *
 * Design:
 * 1. Checks [DICTIONARY] of common conversational and tactical comms words first.
 * 2. Uses a generic phonetic engine ([CONSONANTS], [VOWEL_MATRAS], [VOWEL_INDEPENDENT])
 *    for any words not matched in the dictionary.
 */
object HindiTransliterator {

    val DICTIONARY: Map<String, String> = mapOf(
        // Common pronouns & subjects
        "me" to "मैं",
        "mai" to "मैं",
        "main" to "मैं",
        "mujhe" to "मुझे",
        "mera" to "मेरा",
        "meri" to "मेरी",
        "mere" to "मेरे",
        "aap" to "आप",
        "apka" to "आपका",
        "apki" to "आपकी",
        "apke" to "आपके",
        "tum" to "तुम",
        "tumhara" to "तुम्हारा",
        "tumhari" to "तुम्हारी",
        "tumhare" to "तुम्हारे",
        "tumhe" to "तुम्हें",
        "hum" to "हम",
        "ham" to "हम",
        "hamara" to "हमारा",
        "hamari" to "हमारी",
        "hamare" to "हमारे",
        "hume" to "हमें",
        "humen" to "हमें",
        "yeh" to "यह",
        "ye" to "ये",
        "woh" to "वह",
        "wo" to "वो",
        "ve" to "वे",
        "inka" to "इनका",
        "unka" to "उनका",
        "iska" to "इसका",
        "uska" to "उसका",
        "isse" to "इससे",
        "usse" to "उससे",

        // Questions / Interrogatives
        "kya" to "क्या",
        "kyun" to "क्यों",
        "kyu" to "क्यों",
        "kaha" to "कहाँ",
        "kahan" to "कहाँ",
        "kab" to "कब",
        "kaise" to "कैसे",
        "kaun" to "कौन",
        "kitna" to "कितना",
        "kitne" to "कितने",
        "kitni" to "कितनी",
        "kisko" to "किसको",
        "kiska" to "किसका",

        // Common verbs & actions
        "karau" to "करूँ",
        "karu" to "करूँ",
        "karoon" to "करूँ",
        "karo" to "करो",
        "kare" to "करे",
        "karen" to "करें",
        "karna" to "करना",
        "kar" to "कर",
        "kiya" to "किया",
        "kiye" to "किए",
        "ki" to "की",
        "raha" to "रहा",
        "rahi" to "रही",
        "rahe" to "रहे",
        "hai" to "है",
        "hain" to "हैं",
        "ho" to "हो",
        "hoon" to "हूँ",
        "hun" to "हूँ",
        "tha" to "था",
        "thi" to "थी",
        "the" to "थे",
        "hoga" to "होगा",
        "hogi" to "होगी",
        "honge" to "होंगे",
        "aao" to "आओ",
        "aana" to "आना",
        "aaya" to "आया",
        "aaye" to "आए",
        "aayi" to "आई",
        "jao" to "जाओ",
        "jana" to "जाना",
        "gaya" to "गया",
        "gaye" to "गए",
        "gayi" to "गई",
        "bolo" to "बोलो",
        "bolna" to "बोलना",
        "bola" to "बोला",
        "suno" to "सुनो",
        "sunna" to "सुनना",
        "suna" to "सुना",
        "dekho" to "देखो",
        "dekhna" to "देखना",
        "dekha" to "देखा",
        "samjho" to "समझो",
        "samajh" to "समझ",
        "samajhna" to "समझना",
        "batao" to "बताओ",
        "batana" to "बताना",
        "bataya" to "बताया",
        "ruko" to "रुको",
        "rukna" to "रुकना",
        "chalo" to "चलो",
        "chalna" to "चलना",

        // Particles, adverbs & qualifiers
        "nahi" to "नहीं",
        "nahin" to "नहीं",
        "na" to "ना",
        "haan" to "हाँ",
        "han" to "हाँ",
        "theek" to "ठीक",
        "thik" to "ठीक",
        "accha" to "अच्छा",
        "achha" to "अच्छा",
        "achhi" to "अच्छी",
        "bura" to "बुरा",
        "bahut" to "बहुत",
        "bohot" to "बहुत",
        "thoda" to "थोड़ा",
        "kam" to "कम",
        "zyada" to "ज़्यादा",
        "jyada" to "ज़्यादा",
        "jaldi" to "जल्दी",
        "dhire" to "धीरे",
        "turant" to "तुरंत",
        "abhi" to "अभी",
        "baad" to "बाद",
        "pehle" to "पहले",
        "saath" to "साथ",
        "bhi" to "भी",
        "aur" to "और",
        "ya" to "या",
        "lekin" to "लेकिन",
        "magar" to "मगर",
        "par" to "पर",
        "se" to "से",
        "ko" to "को",
        "ka" to "का",
        "ke" to "के",
        "ki" to "की",
        "mein" to "में",
        "men" to "में",

        // Conversational & etiquette
        "namaste" to "नमस्ते",
        "namaskar" to "नमस्कार",
        "pranam" to "प्रणाम",
        "shukriya" to "शुक्रिया",
        "dhanyavaad" to "धन्यवाद",
        "dhanyawad" to "धन्यवाद",
        "kripya" to "कृपया",
        "kripaya" to "कृपया",

        // Tactical, emergency & disaster relief
        "madad" to "मदद",
        "sahayata" to "सहायता",
        "bachao" to "बचाओ",
        "khatra" to "ख़तरा",
        "suraksha" to "सुरक्षा",
        "surakshit" to "सुरक्षित",
        "stithi" to "स्थिति",
        "sandesh" to "संदेश",
        "sampark" to "संपर्क",
        "sthan" to "स्थान",
        "dal" to "दल",
        "adhikari" to "अधिकारी",
        "aadesh" to "आदेश",
        "sena" to "सेना",
        "police" to "पुलिस",
        "doctor" to "डॉक्टर",
        "dawa" to "दवा",
        "dawain" to "दवाएं",
        "aspatal" to "अस्पताल",
        "ghayal" to "घायल",
        "chot" to "चोट",
        "aag" to "आग",
        "paani" to "पानी",
        "pani" to "पानी",
        "khana" to "खाना",
        "bhojan" to "भोजन",
        "rasta" to "रास्ता",
        "marg" to "मार्ग",
        "band" to "बंद",
        "khula" to "खुला",
        "over" to "ओवर",
        "out" to "आउट",
        "roger" to "रोजर",
        "copy" to "कॉपी",
        "sos" to "एसओएस",
        "emergency" to "इमरजेंसी"
    )

    val CONSONANTS: List<Pair<String, String>> = listOf(
        "chh" to "छ",
        "ch" to "च",
        "kh" to "ख",
        "gh" to "घ",
        "jh" to "झ",
        "th" to "थ",
        "dh" to "ध",
        "ph" to "फ",
        "bh" to "भ",
        "shh" to "ष",
        "sh" to "श",
        "ng" to "ङ",
        "ny" to "ञ",
        "k" to "क",
        "g" to "ग",
        "c" to "क",
        "j" to "ज",
        "z" to "ज़",
        "t" to "त",
        "d" to "द",
        "n" to "न",
        "p" to "प",
        "f" to "फ़",
        "b" to "ब",
        "m" to "म",
        "y" to "य",
        "r" to "र",
        "l" to "ल",
        "v" to "व",
        "w" to "व",
        "s" to "स",
        "h" to "ह",
        "x" to "क्स",
        "q" to "क"
    )

    val VOWEL_MATRAS: List<Pair<String, String>> = listOf(
        "aa" to "ा",
        "ee" to "ी",
        "ii" to "ी",
        "oo" to "ू",
        "uu" to "ू",
        "ai" to "ै",
        "au" to "ौ",
        "ou" to "ौ",
        "ae" to "े",
        "a" to "",
        "i" to "ि",
        "u" to "ु",
        "e" to "े",
        "o" to "ो"
    )

    val VOWEL_INDEPENDENT: List<Pair<String, String>> = listOf(
        "aa" to "आ",
        "ee" to "ई",
        "ii" to "ई",
        "oo" to "ऊ",
        "uu" to "ऊ",
        "ai" to "ऐ",
        "au" to "औ",
        "ou" to "औ",
        "ae" to "ए",
        "a" to "अ",
        "i" to "इ",
        "u" to "उ",
        "e" to "ए",
        "o" to "ओ"
    )

    /**
     * Transliterates a single romanized word to Devanagari.
     */
    fun transliterateWord(word: String): String {
        if (word.isBlank()) return word

        // Separate leading and trailing non-alphanumeric punctuation
        var startIdx = 0
        while (startIdx < word.length && !word[startIdx].isLetterOrDigit()) {
            startIdx++
        }
        var endIdx = word.length
        while (endIdx > startIdx && !word[endIdx - 1].isLetterOrDigit()) {
            endIdx--
        }

        if (startIdx >= endIdx) return word

        val prefix = word.substring(0, startIdx)
        val core = word.substring(startIdx, endIdx)
        val suffix = word.substring(endIdx)

        val lowerCore = core.lowercase()

        // 1. Direct dictionary lookup
        DICTIONARY[lowerCore]?.let { return prefix + it + suffix }

        // If core is purely non-Latin or already has Devanagari, return as-is
        if (core.any { it.code in 0x0900..0x097F } || !core.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            return word
        }

        // 2. Generic phonetic parsing
        val sb = StringBuilder()
        var i = 0
        var prevWasConsonant = false

        while (i < lowerCore.length) {
            // Check independent vowel (at start or after another vowel)
            if (!prevWasConsonant) {
                var matchedVowel: Pair<String, String>? = null
                for (v in VOWEL_INDEPENDENT) {
                    if (lowerCore.startsWith(v.first, i)) {
                        matchedVowel = v
                        break
                    }
                }
                if (matchedVowel != null) {
                    sb.append(matchedVowel.second)
                    i += matchedVowel.first.length
                    prevWasConsonant = false
                    continue
                }
            } else {
                // Following a consonant: check for dependent matra
                var matchedMatra: Pair<String, String>? = null
                for (vm in VOWEL_MATRAS) {
                    if (lowerCore.startsWith(vm.first, i)) {
                        matchedMatra = vm
                        break
                    }
                }
                if (matchedMatra != null) {
                    sb.append(matchedMatra.second)
                    i += matchedMatra.first.length
                    prevWasConsonant = false
                    continue
                }
            }

            // Check for consonant
            var matchedConsonant: Pair<String, String>? = null
            for (c in CONSONANTS) {
                if (lowerCore.startsWith(c.first, i)) {
                    matchedConsonant = c
                    break
                }
            }

            if (matchedConsonant != null) {
                if (prevWasConsonant) {
                    // Halant between adjacent consonants without intervening vowel
                    sb.append("\u094D")
                }
                sb.append(matchedConsonant.second)
                i += matchedConsonant.first.length
                prevWasConsonant = true
            } else {
                // Unrecognized character: append as-is
                sb.append(lowerCore[i])
                i++
                prevWasConsonant = false
            }
        }

        return prefix + sb.toString() + suffix
    }

    /**
     * Transliterates a full string (sentence or multiple words) into Devanagari.
     */
    fun transliterate(text: String): String {
        if (text.isBlank()) return text

        val result = StringBuilder()
        var wordStart = -1

        for (i in text.indices) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                if (wordStart != -1) {
                    result.append(transliterateWord(text.substring(wordStart, i)))
                    wordStart = -1
                }
                result.append(ch)
            } else {
                if (wordStart == -1) {
                    wordStart = i
                }
            }
        }

        if (wordStart != -1) {
            result.append(transliterateWord(text.substring(wordStart)))
        }

        return result.toString()
    }
}
