package com.example.itantra.data.messages

/**
 * Message Filter classification and generic filtering logic.
 */
enum class MessageFilter(val label: String) {
    ALL("All"),
    SENT("Sent"),
    RECEIVED("Received"),
    FAILED("Failed"),
    EMERGENCY("Emergency"),
}

/**
 * Generic predicate filtering to support UI message lists and data layers.
 */
fun <T> List<T>.applyMessageFilter(
    filter: MessageFilter,
    isSent: (T) -> Boolean,
    isFailed: (T) -> Boolean,
    isEmergency: (T) -> Boolean,
): List<T> = when (filter) {
    MessageFilter.ALL -> this
    MessageFilter.SENT -> filter { isSent(it) }
    MessageFilter.RECEIVED -> filter { !isSent(it) }
    MessageFilter.FAILED -> filter { isFailed(it) }
    MessageFilter.EMERGENCY -> filter { isEmergency(it) }
}
