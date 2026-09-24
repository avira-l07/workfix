package com.itantra.core.translation

/**
 * Provisioning state for offline translation models.
 * Used by MlKitOfflineTranslationEngine to report model readiness
 * and by the UI to show pre-flight status.
 */
enum class TranslationModelState {
    /** Models are downloaded and ready for offline inference. */
    READY,

    /** Models are currently being downloaded. */
    DOWNLOADING,

    /** Model download or initialization failed. */
    FAILED,

    /** Models have not been downloaded yet. */
    NOT_INSTALLED
}
