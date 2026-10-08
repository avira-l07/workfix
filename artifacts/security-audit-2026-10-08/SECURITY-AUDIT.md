# iTantra security review — 8 October 2026

**Result:** Semgrep Community and MobSF are installed and their local static scans are complete. The reviewed APK is a debug build. Its debug configuration is a confirmed release blocker; an ML Kit logging path also needs release validation. This review did not establish an exploitable message-encryption bypass, SQL injection, or leaked iTantra credential. It does not certify the app as secure.

**Follow-up:** Three controlled host probes subsequently reproduced missing receipt ownership checks and an unlimited pending playback backlog, while confirming rejection of unverified text. These application faults are detailed in [the prioritized fault report](<D:/new itantra/artifacts/security-audit-2026-10-08/SECURITY-FAULTS.md>). They were not detected by the original Semgrep rule set. No app source was changed; the probe results are evidence of existing faults, not fixes.

## What was reviewed

| Input or tool | Evidence |
| --- | --- |
| APK | `iTantra-1.14-functions-audit.apk`, version code 15, 85,682,417 bytes |
| APK SHA-256 | `aae3896c34175ca55807da49c35ddca110b8b93b10c06ca9f9850210cf5ccdf7` |
| Platform | Android package `com.example.itantra`, min SDK 26, target SDK 37, arm64 |
| Semgrep Community 1.179.0 — source | 145 files, 48 applicable rules, 0 findings, 0 parsing errors |
| Semgrep — app classes decompiled from APK | 485 Java files, 124 applicable rules, 4 socket warnings, 16 partial-parsing warnings |
| MobSF 4.5.3 | Manifest, certificate, DEX code, strings, tracker signatures and 8 native libraries |
| Existing unit-test evidence | 83 security-related cases in 16 suites, all passed in the earlier functions audit; not rerun during this scan |
| Integrity verification | 194 source/config/resource inputs unchanged since scan preparation; APK and saved test evidence hashes match |

The scanner installations and Android decompilation data are in `C:/Users/avira/AppData/Local/iTantraTools`. Scan results and reviewed evidence are in [this audit directory](<D:/new itantra/artifacts/security-audit-2026-10-08>). The [input manifest](<D:/new itantra/artifacts/security-audit-2026-10-08/source-input-manifest.json>) pins the dirty working-tree inputs without resetting the user's app changes.

## Findings that need action

| Priority | Finding | Assessment and required action |
| --- | --- | --- |
| High — before public distribution | Debugging enabled, debug signing certificate, debug tooling activities exported | Confirmed in this APK. MobSF separately flags `BuildConfig.DEBUG`, the debuggable manifest and the certificate. The APK also contains `androidx.activity.ComponentActivity` from the debug test manifest and `androidx.compose.ui.tooling.PreviewActivity`. Build and scan the signed release variant, verify debugging is disabled and these debug-only activities are absent, and plan the signing/upgrade path before replacing installed builds. |
| Medium — message integrity | Receipt handling checks only message ID, without original peer ownership | Reproduced: a verified Peer B can mark stored messages addressed to Peer A as delivered or acknowledged. Apply a shared recipient/session ownership check before ACK, HUMAN_ACK and TTS receipt updates. See the follow-up report for conditions and host evidence. |
| Medium — availability | Playback backlog is unlimited while speech/translation consumes it serially | Reproduced: 512 valid encrypted messages retained 4 MiB of payload while translation was deliberately blocked. Add count/byte limits, admission/backpressure and emergency capacity; coordinate queue admission with delivery ACKs. No phone heap exhaustion was attempted. |
| Medium — release privacy check | ML Kit 17.0.3 contains logging of Firebase installation identifiers and tokens | Confirmed code path in the bundled SDK, not a reproduced phone leak. Decompiled `zztz.java` lines 127–129 log the installation ID, refresh token and authentication token; line 231 logs a refreshed authentication token. Validate the release APK's logs during language-pack download and translation setup, and remove sensitive logging through an appropriate supported SDK/build configuration. Merely disabling app-owned debug logs is insufficient evidence that SDK logs are removed. |
| Follow-up — dependency hardening | ONNX Runtime has `$ORIGIN` RUNPATH; three vendor libraries have no detected fortified imports | MobSF marks RUNPATH high and missing FORTIFY symbols warning. All 8 libraries have NX, a stack canary, Full RELRO and stripped symbols. No attacker-writable library search directory or buffer-overflow reproducer was established. Review dependency provenance and test any dependency/build changes before adoption. |

The Gradle configuration declares preview/test tooling under `debugImplementation`; the scanned artifact is intentionally the debug variant. A future release APK has not been built or inspected in this task. [Android's release preparation guidance](https://developer.android.com/studio/publish/preparing) calls for disabling debugging and removing logging before distribution.

The SDK logging evidence is in [decompiled ML Kit code](<C:/Users/avira/AppData/Local/iTantraTools/MobSF/data/uploads/97776519fb619e5cdd558d5f0b0b5f77/java_source/com/google/android/gms/internal/mlkit_translate/zztz.java:127>). These are SDK installation tokens, not iTantra message-encryption keys. Their exposure requires access to the relevant logs and execution of the SDK path; neither was tested on a phone.

`$ORIGIN` resolves to the directory containing the ELF library on Android. Using it for private colocated package libraries can be legitimate; this is a contextual finding, not proof of remote code execution. [Android's native linker documentation](https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md) explains the search behavior. Missing fortified imports are a symbol heuristic and do not by themselves demonstrate an unsafe function call.

## Warnings reviewed against the app

| Scanner warning | Review |
| --- | --- |
| Four unencrypted Wi-Fi socket warnings | They identify the TCP byte transport. [SecureSessionManager](<D:/new itantra/app/src/main/java/com/itantra/core/crypto/SecureSessionManager.kt>) uses AES-GCM with direction-specific keys/nonces and replay protection. [TransceiverCoordinator](<D:/new itantra/app/src/main/java/com/itantra/core/transceiver/TransceiverCoordinator.kt>) requires verified pairing and authenticates application packets before processing ACKs, messages, location or profile data. Public pairing metadata is sent before verification; the protocol does not conceal that metadata. These warnings did not establish plaintext chat exposure. |
| Raw SQL queries | Reviewed app calls in [EncryptedDatabase](<D:/new itantra/app/src/main/java/com/itantra/core/storage/EncryptedDatabase.kt>) bind attachment paths/keys and escape SQLite-derived table identifiers. No user-text SQL interpolation was found in those calls. Library-internal SQL calls are also included in MobSF's warning. |
| Hardcoded password/key | The flagged `SavedSpeechPlayback` string is a generated `toString()` label containing `itemKey`, a playback identifier. It is not a database password or cryptographic key. The source is [SavedSpeechPlayer](<D:/new itantra/app/src/main/java/com/itantra/core/inference/SavedSpeechPlayer.kt>). |
| External-storage access | Reviewed app paths clean up legacy debug WAV files in app-scoped external storage. They do not demonstrate current transcripts being written to shared public storage. [StoragePrivacy](<D:/new itantra/app/src/main/java/com/itantra/core/storage/StoragePrivacy.kt>) and MainActivity contain the relevant cleanup. |
| Exported ProfileInstallReceiver / undefined DUMP permission | `android.permission.DUMP` is defined by Android with `signature|privileged|development` protection. It is not an app-defined permission missing from the manifest or an ordinary third-party grant. [Android platform manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml) supplies the permission definition. This does not resolve the separate exported debug activities above. |
| Minimum SDK 26 | Supporting old Android devices leaves OS patch exposure dependent on the device. This flag is not evidence of a specific app vulnerability. |
| General logging | App diagnostics include counts, language IDs and errors. The SDK token path above is a concrete reason to verify release logs. This review does not guarantee that every dependency exception contains no personal data. |

The reviewed storage uses Android Keystore-protected AES-GCM and SQLCipher with a randomly generated wrapped database passphrase. Packet decoding has frame/payload bounds; model/frontend extraction checks archive paths and expansion limits. These are source-review observations, not penetration-test proofs. Relevant existing tests are recorded in [the test evidence index](<D:/new itantra/artifacts/security-audit-2026-10-08/existing-security-test-evidence.json>).

## Secret and tracker results

MobSF reports **1,146 heuristic secret candidates**, not 1,146 validated exposed secrets. Its known-credential-format pass reported no matches.

- 23 candidates found directly in first-party source are pinned model checksums or public model revision IDs, appearing at 60 locations.
- 1,115 candidates occur in bundled native-library strings. Many are mangled C++ symbols. Their provenance was recorded; each native string was not individually reverse engineered.
- The remaining 8 were traced to a WebSocket protocol constant, two generated Room schema hashes, public ONNX conversion metadata, an R8 generated-class hash, and three Google ML Kit installation-configuration fragments. No iTantra account credential was identified. The SDK-owned Firebase configuration and its service restrictions were not validated online. Firebase explains that its client configuration/API keys are generally public identifiers, while keys used with other Google services need restrictions. [Firebase API-key guidance](https://firebase.google.com/docs/projects/api-keys)

See [first-party locations](<D:/new itantra/artifacts/security-audit-2026-10-08/mobsf-secret-source-locations.json>), [native provenance](<D:/new itantra/artifacts/security-audit-2026-10-08/mobsf-secret-provenance.json>) and [the remaining candidate review](<D:/new itantra/artifacts/security-audit-2026-10-08/mobsf-secret-remaining-review.json>). Candidate values are redacted in these review files.

MobSF detected **0 trackers against 432 bundled signatures**. This is not a guarantee of no SDK network traffic or telemetry. Optional online signature updates were blocked. Its raw **43/100 score** includes debug-build penalties and heuristic warnings; it is not a translation-accuracy figure or a measurement that the app is “43% secure.”

## Scope and next verification

1. Produce and rescan the signed release APK; inspect its merged manifest, certificate, exported components and logging. No app source was changed or release signing key created by this scan.
2. On two phones, exercise Wi-Fi and Bluetooth pairing with matching/mismatching codes, simultaneous acceptance, dropped verification packets, reconnects and transport switching. Verify encrypted message/location delivery and rejection of packets from stale/unverified sessions.
3. Test replayed, tampered and malformed packets, interrupted model downloads, archive-path attacks, and storage/wipe behavior on devices. Review SDK initialization traffic and logs while online, then repeat normal messaging/translation offline.

No phone was attached, so MobSF dynamic analysis, radio interception, runtime log capture and two-device penetration tests were not run. Three additional host probes were run during the follow-up; the earlier full unit suite was not rerun. Host tests do not replace phone tests. Semgrep's 16 decompiled-Java partial parses came from JADX coroutine/Compose syntax; the original Kotlin source scan parsed without errors. Only the applicable generic rules covered C++ source; the optional CTranslate2 JNI library is not included in this APK, and no comprehensive native memory-safety audit was performed. Offline dependency CVE/SDK privacy verification is incomplete.

## Local execution and saved evidence

Semgrep used saved public Kotlin, Java, security-audit and secrets rules, Community/OSS mode, telemetry disabled, no login and no online secret validation. MobSF used an authenticated Django test client in process, with no HTTP server. A Python socket audit hook blocked non-loopback connections; VirusTotal uploads, domain probes and optional remote lookups were disabled or blocked. Nothing from the app or APK was sent to a scanner cloud service during these analyses. Tool/rule downloads used their public distribution endpoints.

MobSF's first Windows attempt needed a multiprocessing main guard and complete settings defaults. The corrected scan completed. A forced-rescan cache mismatch made the separate cached JSON endpoint return 404; the complete `/scan` API response was retained and exported as the report without repeating analysis. These were scanner setup/export issues, not app failures. [API export status](<D:/new itantra/artifacts/security-audit-2026-10-08/mobsf-report-api-status.json>) records the report source.

- [Semgrep source JSON](<D:/new itantra/artifacts/security-audit-2026-10-08/semgrep.json>) and [SARIF](<D:/new itantra/artifacts/security-audit-2026-10-08/semgrep.sarif>)
- [Semgrep decompiled-app Java JSON](<D:/new itantra/artifacts/security-audit-2026-10-08/semgrep-apk-java.json>)
- [Complete MobSF report](<D:/new itantra/artifacts/security-audit-2026-10-08/mobsf-report.json>) and [analysis logs](<D:/new itantra/artifacts/security-audit-2026-10-08/mobsf-scan-logs.json>)
- [Local execution policy](<D:/new itantra/artifacts/security-audit-2026-10-08/mobsf-local-policy.json>), [scan summary](<D:/new itantra/artifacts/security-audit-2026-10-08/scan-summary.json>) and [integrity verification](<D:/new itantra/artifacts/security-audit-2026-10-08/verification.json>)

The scan outputs are preserved unmodified. Reviewed judgments are recorded here; raw scanner severity labels were not suppressed or converted into a claimed security certification.
