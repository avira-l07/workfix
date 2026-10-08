package com.itantra.core.storage

enum class DataRemovalChoice(val label: String, val detail: String) {
    MESSAGES("Messages and locations", "Includes recycled messages and emergency records; clears their active alerts."),
    VOICE_NOTES("Saved voice-note transcripts", "Includes the voice-note recycle bin."),
    DIAGNOSTICS("Diagnostics and temporary files", "Removes benchmark reports and temporary recordings."),
    ALL_PRIVATE("All private data", "Also resets operator identity, remembered peers and settings.");

    companion object {
        fun encode(choices: Set<DataRemovalChoice>): ByteArray {
            require(choices.isNotEmpty()) { "Choose data to delete" }
            return choices.sortedBy { it.ordinal }.joinToString(",") { it.name }.toByteArray(Charsets.US_ASCII)
        }

        fun decode(bytes: ByteArray): Set<DataRemovalChoice> {
            // Resume a full wipe requested by older app versions.
            if (bytes.contentEquals(byteArrayOf(1))) return setOf(ALL_PRIVATE)
            require(bytes.isNotEmpty() && bytes.size <= 128) { "Invalid data-removal request" }
            return bytes.toString(Charsets.US_ASCII).split(',').map { valueOf(it) }.toSet()
        }
    }
}
