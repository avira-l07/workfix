# Bug audit — 28 September 2026

This is a source review with a fresh run of the existing unit tests. Findings below are code-path defects; they have not all been reproduced on Android hardware. No application source has been changed. An exhaustive guarantee that every bug has been found is not possible from this review.

## Validation

- `:app:testDebugUnitTest --rerun`: completed; 361 tests, zero failures/errors/skips across 60 suites.
- `:app:lintDebug`: FAILED with 4 errors, 71 warnings, and 1 hint. Full output: `app/build/reports/lint-results-debug.html` and `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.
- `adb devices`: no attached devices. Visual layout, real microphone/speaker behavior, permission dialogs, background operation, and Bluetooth/Wi-Fi Direct interoperability remain unverified.
- Existing `app/proguard-rules.pro` was untracked before the audit and was left untouched.

## Findings

P1 = high impact; P2 = normal priority. References are repository-relative with one-based source line numbers.

### 1. P1 — Messages received outside the chat can disappear from that chat

**References:** `app/src/main/java/com/example/itantra/MainActivity.kt:886`, `:905`; `app/src/main/java/com/example/itantra/ui/screens/chat/DedicatedChatScreen.kt:80`; `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:976`, `:869`.

Chat navigation uses the Bluetooth/Wi-Fi MAC address as `peerId`. Incoming messages use the open conversation ID if present, otherwise the profile's stable device ID. The chat filter compares its MAC address against message peer/sender/receiver IDs, without resolving the stable ID. Receive a message while on the hub, then open the chat: the message was stored under a different identifier and is filtered out. The same peer's history also splits across transports.

**Correction:** use a canonical stable device ID for conversation storage and filtering, with an explicit transport-address mapping.

### 2. P1 — Message IDs collide for sends in the same millisecond

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:82`; `app/src/main/java/com/itantra/data/db/MessageEntity.kt:13`; `app/src/main/java/com/itantra/data/db/MessageDao.kt:13`.

`nextMessageId()` combines the wall-clock millisecond with a fixed per-device salt; there is no incrementing sequence. Two calls on the same device in one millisecond return the same ID. Concurrent sends can overwrite Room rows, share ACK waiters, update multiple UI messages together, and be discarded as duplicates by the receiver. Clock rollback can also reuse IDs.

**Correction:** use an atomic monotonic allocator with suitable cross-device uniqueness.

### 3. P1 — Concurrent message mutations can lose messages or status changes

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:72`, `:476`, `:493`, `:170`, `:1629`.

The coordinator runs work on `Dispatchers.Default`, but list mutations use `_messages.value = _messages.value + msg` and read/map/write assignments. StateFlow's individual accessors do not make these compound operations atomic. Two concurrent additions can both read the old list and the last assignment drops the other message. ACK, playback, and recording updates can similarly overwrite each other.

**Correction:** serialize message mutations or use atomic StateFlow updates, keeping persistence side effects outside retryable update lambdas.

### 4. P2 — Older database snapshots can overwrite newer message states

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:479`, `:503`.

Each add/update launches an independent IO coroutine carrying a captured message snapshot. The mutex prevents simultaneous writes but does not preserve the order in which those coroutines were launched. An old TRANSMITTING snapshot can acquire the mutex after a newer DELIVERED snapshot and replace it. After restart, the app can show an already delivered message as interrupted or in an older state.

**Correction:** persist through an ordered writer, or version updates so stale snapshots cannot replace newer ones.

### 5. P1 — Receiver translates text using its original language instead of its actual payload language

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:916`, `:939`, `:954`.

For sender-translated packets, `sourceLanguage` retains the original language while `targetLanguage` identifies the language actually in the payload. Receiver-side translation calls `routeAndTranslate(text, srcLang, localLanguage)`. Example: Hindi text translated to English at the sender, with Marathi selected at the receiver, is incorrectly processed as Hindi-to-Marathi even though the payload is English. With automatic receive language, the router can bypass translation because source equals target, then label English text as Hindi and select Hindi TTS.

**Correction:** translate from the payload language and define automatic receive behavior consistently for already-translated packets.

### 6. P1 — ALL_CLEAR can immediately restart reminders for the emergency it resolved

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:711`, `:731`, `:745`, `:475`, `:515`.

ALL_CLEAR cancels the alert job and resolves the emergency store, but does not mark the old remote critical chat messages acknowledged/resolved. It then calls `addMessage(allClearMsg)`, which calls `updateAlertJob()`. That method still sees the old critical message as unacknowledged and starts another reminder job. Receive an SOS without acknowledging it, then receive ALL_CLEAR: the persistent service alarm is stopped, but the separate reminder path can speak the old emergency again after ten seconds.

**Correction:** atomically resolve the corresponding message states and the durable records before reevaluating reminders.

### 7. P1 — Human acknowledgement can be overwritten by playback completion

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:2388`, `:1187`, `:754`, `:777`.

Acknowledging a critical message during its playback sets ACKNOWLEDGED. When playback finishes, `processIncomingMessagePacket()` unconditionally sets REMOTE_PLAYBACK_CONFIRMED. Reminder selection treats every critical remote state except ACKNOWLEDGED as unresolved, so reminders can resume after the user acknowledged the message. The sender also loses its acknowledged UI state when a later TTS_COMPLETED packet arrives.

**Correction:** represent acknowledgement separately from playback/delivery status, or preserve terminal acknowledgement during later updates.

### 8. P2 — Outgoing SOS messages have no conversation identity

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:2289`; `app/src/main/java/com/example/itantra/ui/screens/chat/DedicatedChatScreen.kt:80`.

`sendEmergencyCode()` constructs messages without peerId, senderDeviceId, or receiverDeviceId, leaving them empty. The chat filter rejects those messages for every nonempty peer ID. Operators cannot see the outgoing SOS or its delivery failure in the corresponding chat.

**Correction:** attach the intended peer and local identity, and define how broadcast emergencies appear in conversation history.

### 9. P1 — Outgoing GPS bubbles can display and open incorrect coordinates

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:1983`, `:1997`; `app/src/main/java/com/example/itantra/ui/screens/chat/DedicatedChatScreen.kt:749`, `:925`.

The sender updates only the formatted text and payload byte count; it does not store the structured location fields or the GPS fix timestamp. The bubble reparses text with a dot-decimal regex, although the text was formatted using the device locale. A comma-decimal locale produces `📍 Location: 12,34567, 77,12345 (±4,5m)`. A local reproduction of the same regex extracts latitude `12` and longitude `34567`. The Maps action uses these incorrect values. Even on a dot-decimal locale, the sender loses coordinate precision and substitutes message creation time for the actual fix time.

**Correction:** populate isLocation, latitude, longitude, accuracyMeters, and locationTimestampMillis from the acquired fix; never derive map coordinates from display text.

### 10. P2 — Production transport does not wait for GPS delivery acknowledgement

**Reference:** `app/src/main/java/com/itantra/core/transport/TransportCoordinator.kt:205`; `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:795`, `:2007`.

The receiver sends ACK for LOCATION, but the transport registers ACK waiters only for TEXT and EMERGENCY_CODE. LOCATION sends therefore always return unmeasured latency. The location sender cannot calculate its RTT, and a fast ACK can set DELIVERED before the send continuation overwrites it with SENT. Mock transport tests do not establish that this production ACK path works.

**Correction:** include LOCATION in ACK tracking and prevent send completion from regressing a state already advanced by an ACK.

### 11. P2 — Received voice-message badges are inferred from priority

**Reference:** `app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:993`, `:2078`; `app/src/main/java/com/example/itantra/ui/screens/chat/DedicatedChatScreen.kt:639`.

The receiver sets isVoiceGenerated when priority flags are nonzero. Normal voice messages have priority zero, so they lose their voice badge; high/critical typed messages gain one. The current packet construction carries priority but no independent voice-origin marker.

**Correction:** encode voice origin independently from priority, or omit a badge that the wire protocol cannot determine.

### 12. P2 — Diagnostics bypasses the speech model lifecycle and shares active recognition state

**Reference:** `app/src/main/java/com/example/itantra/MainActivity.kt:1003`, `:1012`, `:1030`; `app/src/main/java/com/itantra/core/inference/SherpaOnnxSpeechRecognizer.kt:253`.

When the current recognizer is not English, the diagnostics action creates and loads another recognizer without unloading it in a finally block, while retaining the active model. Repeated runs can overlap and increase model memory use. When English is already active, diagnostics directly resets/feeds/finalizes the same recognizer used by live speech; a continuous-listening session can therefore mix benchmark audio with microphone input or have its buffers reset.

**Correction:** serialize diagnostics, pause live speech, and manage the benchmark recognizer through an explicit lifecycle with guaranteed cleanup.

## Lint blockers and other diagnostics

These are separate from the 12 behavioral findings above. The four errors reproducibly fail the lint task; they are not four independently demonstrated crashes.

- **Three MissingPermission errors:** `app/src/main/java/com/itantra/core/location/GpsLocationProvider.kt:63`, `:73`, `:75`. The low-level adapter invokes permission-protected APIs without an explicit permission contract/check visible to lint. The higher-level GPS provider does check permissions and catch SecurityException, so this report does not infer a runtime crash from those warnings. Make the adapter contract explicit or provide correctly scoped checks/annotations.
- **One UnspecifiedRegisterReceiverFlag error:** `app/src/main/java/com/example/itantra/MainActivity.kt:491`. The legacy debug-receiver registration omits export flags. There is an API-33 guard using RECEIVER_NOT_EXPORTED on newer Android versions; therefore this is a confirmed lint failure, not evidence of an Android-14 registration crash. Use the compatible registration API with explicit flags.
- The 71 warnings include a microphone foreground-service constant used in an API-29 branch, a wake lock acquired without timeout, missing x86_64 support, drawable density placement, dependency updates, unused resources, and style suggestions. The complete lint report preserves all diagnostics. Dependency/style warnings are not counted as behavioral bugs.

## Follow-up verification

Add focused regressions for concurrent sends/persistence, actual MAC-to-device-ID navigation, acknowledgement during playback, ALL_CLEAR reminder suppression, translated-payload routing, and locale-dependent outgoing GPS. Then test two physical Android devices for connection/reconnection, transport switching, permissions, lock-screen/background audio, SOS acknowledgement, GPS sharing, and language switching during recording/playback. Existing passing tests do not cover all of these production integrations.
