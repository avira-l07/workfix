package com.itantra.domain.model

/** Seven elapsed days from deletion; restoring never changes the recording's date. */
object RecycleBinPolicy {
    const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun canRestore(deletedAtMillis: Long, nowMillis: Long): Boolean =
        deletedAtMillis > 0 && nowMillis < deletedAtMillis + RETENTION_MILLIS

    fun daysRemaining(deletedAtMillis: Long, nowMillis: Long): Int =
        ((deletedAtMillis + RETENTION_MILLIS - nowMillis).coerceAtLeast(0) + 86_399_999L)
            .div(86_400_000L).toInt().coerceIn(0, 7)

    fun canTrash(message: TransceiverMessage): Boolean =
        message.deletedAtMillis == null &&
            (message.priority != MessagePriority.CRITICAL || message.state == MessageState.ACKNOWLEDGED) &&
            message.state in setOf(MessageState.IDLE, MessageState.SENT,
                MessageState.DELIVERED, MessageState.REMOTE_PLAYBACK_CONFIRMED,
                MessageState.ACKNOWLEDGED, MessageState.ERROR)
}
