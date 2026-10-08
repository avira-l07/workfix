# Confirmed security faults — follow-up review, 8 October 2026

**Two application faults were reproduced with the existing app code.** The debug APK and sensitive SDK logging remain release issues. This follow-up used the saved Semgrep/MobSF results, source review and three controlled host-side probes. No app source was changed.

## 1. Acknowledgements are not restricted to the original peer

**Priority: medium; fix first for message integrity.** A currently verified peer can send a valid encrypted receipt referring to a message that belongs to another peer. The receiver checks session authentication but then updates message state using only `messageId`.

The probe connected authenticated Peer B (`IT-BBBB-0002`) and supplied receipts for stored outgoing messages addressed to Peer A (`IT-AAAA-0001`). Peer B's `ACK` changed A's message to `DELIVERED`; B's `HUMAN_ACK` changed another A message to `ACKNOWLEDGED`. The stored recipient stayed A. This requires a verified peer that knows or guesses the message ID; it is not an unauthenticated encryption bypass.

Locations: [ACK handler](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:984>), [HUMAN_ACK handler](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:1066>) and [message update](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:691>). The TTS receipt handlers at lines 1076–1099 have the same missing ownership check by source review; they were not separately exercised by this probe.

Required fix: validate the receipt against the stored outgoing message, intended verified recipient and allowed session/pending-send context before updating delivery, speech or emergency state. Keep an explicit policy for broadcast SOS acknowledgements. Apply the shared ownership check to ACK, HUMAN_ACK and TTS status receipts.

## 2. Pending playback has no count or byte limit

**Priority: medium; availability risk from a verified peer.** [The pending list](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:240>) grows through [unconditional enqueueing](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:1209>) while [the worker](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:660>) processes translation/playback serially. Individual packet size limits do not bound the total pending list.

With one translation deliberately held busy, the probe delivered 512 further valid AES-GCM-encrypted messages with unique IDs and explicit language metadata. All 512 remained pending, retaining **4,194,304 payload bytes**, plus the in-flight message and object overhead. Source review found no queue admission limit. This demonstrates unbounded backlog; a phone crash or exhausted heap was not reproduced. Continued traffic faster than speech/translation can consume it presents a memory-exhaustion risk.

Required fix: bound pending message count and total bytes, apply per-peer admission/backpressure, and reserve capacity for emergency traffic. Coordinate admission with the [current immediate ACK](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt:1004>) so rejected messages are not silently reported as delivered. The history-write channel is also unlimited by source review and should be included in the backlog policy.

## 3. The APK being tested is a debug build

**Priority: high before public distribution.** The packaged manifest has `android:debuggable="true"` and exports `androidx.activity.ComponentActivity` and `androidx.compose.ui.tooling.PreviewActivity`; MobSF also confirms a debug signing certificate. PreviewActivity accepts a composable name from its launch intent when the app is debuggable. No arbitrary-code or data-exfiltration exploit through that activity was demonstrated.

Evidence: [packaged application manifest](<C:/Users/avira/AppData/Local/iTantraTools/MobSF/data/uploads/97776519fb619e5cdd558d5f0b0b5f77/apktool_out/AndroidManifest.xml:29>) and [debug-only dependencies](<D:/new itantra/app/build.gradle.kts:148>).

Required fix: use the signed release variant for distribution, inspect its actual merged manifest and certificate, and rescan that APK. The existing release variant has not been inspected here; debug findings should not be treated as proof that release is also debuggable. [Android release preparation guidance](https://developer.android.com/studio/publish/preparing) describes disabling debugging and preparing release signing.

## 4. The translation SDK contains sensitive token logging

**Priority: medium; release privacy check.** Bundled ML Kit 17.0.3 logs a Firebase installation refresh token and authentication-token object at [zztz.java lines 128–129](<C:/Users/avira/AppData/Local/iTantraTools/MobSF/data/uploads/97776519fb619e5cdd558d5f0b0b5f77/java_source/com/google/android/gms/internal/mlkit_translate/zztz.java:128>), and a refreshed token at line 231. These are SDK installation tokens, not message-encryption keys.

The release configuration [disables optimization](<D:/new itantra/app/build.gradle.kts:54>); the existing ProGuard file has only Sherpa keep rules and is not referenced by `proguardFiles` in the app build file. A release build name alone does not establish removal of these dependency log calls.

Required fix: remove sensitive SDK logging through a supported dependency/build approach, preserve needed JNI bindings, and inspect release Logcat during SDK initialization/model downloads. This logging path is confirmed statically; token exposure on a phone has not been captured. [Android's logging-risk guidance](https://developer.android.com/privacy-and-security/risks/log-info-disclosure) explains why sensitive values should be excluded from logs.

## Evidence and limits

The three audit probes **passed by reproducing the current behavior**, not by showing these faults were fixed:

| Probe | Observed result |
| --- | --- |
| Unverified traffic control | 64 plaintext messages rejected; no playback queue entries or saved messages |
| Authenticated flood | 512 pending messages / 4,194,304 payload bytes while one translation was blocked |
| Wrong-peer receipts | Peer B changed messages addressed to Peer A to DELIVERED and ACKNOWLEDGED |

[Probe source](<D:/new itantra/artifacts/security-audit-2026-10-08/probes/com/itantra/regression/SecurityFaultProbe.kt>), [JUnit evidence](<D:/new itantra/artifacts/security-audit-2026-10-08/probe-results.xml>) and [final run log](<D:/new itantra/artifacts/security-audit-2026-10-08/probe-run.log>) are preserved. The probes reuse the app's existing fixture and authenticated encryption path; they do not simulate the full physical Bluetooth/Wi-Fi stack. A separate init script includes them without changing app source or build configuration. Run:

```powershell
# From D:\new itantra; outputs reuse the existing build-directory junction on C:.
.\gradlew.bat -I artifacts\security-audit-2026-10-08\probe.init.gradle :app:testDebugUnitTest --tests com.itantra.regression.SecurityFaultProbe --offline --no-daemon -Pkotlin.incremental=false
```

The earlier full unit suite was not rerun because app code was unchanged. The scanned APK is still the same artifact. Phone penetration tests, runtime token-log capture and a release scan remain pending.

The native RUNPATH/FORTIFY warnings remain dependency-hardening follow-ups, with no confirmed exploit. The socket, raw-SQL and hardcoded-key scanner warnings did not establish the corresponding vulnerabilities after review; see [the full scanner audit](<D:/new itantra/artifacts/security-audit-2026-10-08/SECURITY-AUDIT.md>) for their evidence and limitations.
