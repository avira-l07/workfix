# iTantra security fixes and manual data controls

8 October 2026 · version 1.15-security, version code 16

**The reproduced application faults are fixed, and all 570 host tests pass.** The final signed APK was scanned locally with MobSF and the final source with Semgrep CE. No real user data was deleted and no source/APK was uploaded to a cloud scanner.

## Install and choose what to delete

Use [the phone test APK](<D:/new itantra/artifacts/security-hardening-2026-10-08/iTantra-1.15-security-test.apk>). It contains the optimized release code with debugging disabled, signed using the existing development certificate. A matching installed certificate allows an in-place upgrade without uninstalling or clearing data. Do not uninstall to work around a signature mismatch if you need the existing history.

Open **Settings → Manage private data → Choose data to delete**. Select any combination of:

- Messages and locations, including their recycle bin and emergency records.
- Saved voice-note transcripts, including their recycle bin.
- Diagnostics and temporary files.
- All private data, which additionally resets operator identity, remembered peers and settings.

Choose **Continue**, review the selection, then **hold to delete**. A regular tap does not confirm deletion. The app disconnects and restarts so active writers cannot recreate removed records. Downloaded language models stay. Individual items can still be deleted and restored through the existing recycle bin.

Selective deletion uses the encrypted database and preserves unselected rows and encryption keys. The shared deletion helper validates table names, enables SQLite secure_delete on the actual writer transaction, deletes selected rows, vacuums, and truncates the WAL. A full reset removes history files, sidecars, private settings and their keys. Interrupted requests use an atomic marker and retry before normal storage is opened. This is application data removal; physical flash erasure is not claimed. See [SQLite's secure_delete documentation](https://www.sqlite.org/pragma.html#pragma_secure_delete).

## Confirmed faults fixed

| Fault | Change and evidence |
| --- | --- |
| A verified peer could forge delivery/human receipts for another peer's history | All five receipt types now require a local outgoing message actually sent in the current session and addressed to the verified peer. Tests cover wrong-peer, unsent-history and replacement-session receipts, plus valid receipt delivery. |
| A verified sender could grow pending speech without a bound | Admission is limited to 64 pending messages and 512 KiB of payload; normal traffic uses at most 60 slots and 448 KiB, reserving space for emergencies. Positive ACKs follow admission. Rejected traffic can retry. The ACK worker is bounded too. Flood and emergency-reservation tests pass. |
| Malformed traffic or colliding IDs could be accepted | Invalid emergency/location packets and local/other-peer message-ID collisions are rejected before positive acknowledgement. Unverified plaintext is still rejected. |
| ALL_CLEAR could silence other peers' emergencies | Emergency records now retain their owner. ALL_CLEAR resolves only that sender's remote alerts, including queued/in-flight alerts; other peers' and local alerts remain. Legacy unowned alerts are not silently cleared. |
| Slow storage retained unlimited intermediate writes | History persistence coalesces notifications and reconciles the latest live/trashed rows. A blocked-storage test verifies final states and permanent deletion. |
| Accepted messages disappeared on disconnect or emergency preemption | Interrupted/queued text is kept under its original sender for later playback and manual deletion. Tests cover active translation, queued messages, preemption and a session change before processing starts. This does not establish durability through an abrupt process kill or device power loss. |
| Debug APK exposure and sensitive SDK Android logging | Release optimization is enabled with JNI keep rules. The actual signed APK has debugging disabled, no exported test preview activity, and zero DEX references to Android Log output methods. The SDK token-log markers are absent. All 123 Sherpa JNI classes and the eight native-library hashes are preserved. |

The original attack evidence is retained in [the earlier fault report](<D:/new itantra/artifacts/security-audit-2026-10-08/SECURITY-FAULTS.md>). Its probes intentionally reproduced vulnerable behavior; use the new regression suite to verify the fixes.

## Verification

- Full test suite: **570 passed, zero failures/errors/skips**. This includes 15 new security regression cases and six selective data-removal cases; the SQL deletion tests exercise the shared helper against real host SQLite.
- Debug and optimized release APK builds passed, including release vital lint. [Build log](<D:/new itantra/artifacts/security-hardening-2026-10-08/complete-verification.log>) and [final test results](<D:/new itantra/artifacts/security-hardening-2026-10-08/complete-test-results>).
- APK Signature Scheme v2/v3 verification passed. Signed APK contents match the verified unsigned release except signing metadata. [Signature](<D:/new itantra/artifacts/security-hardening-2026-10-08/phone-test-signature.txt>), [packaged manifest](<D:/new itantra/artifacts/security-hardening-2026-10-08/phone-test-manifest.txt>) and [binary assertions/hashes](<D:/new itantra/artifacts/security-hardening-2026-10-08/binary-verification.json>).
- Final Semgrep CE source scan: **147 files, 48 applicable rules, zero findings or parser errors**. [JSON](<D:/new itantra/artifacts/security-hardening-2026-10-08/semgrep-complete.json>) and [scan log](<D:/new itantra/artifacts/security-hardening-2026-10-08/semgrep-complete.log>). These rules are not exhaustive and do not prove an absence of vulnerabilities.
- Final local MobSF scan of the signed test APK completed: **zero high findings in manifest/code analysis**, with two manifest warnings, five code-warning categories, two informational categories and one SDK security feature match. The development-certificate high finding and native RUNPATH finding remain explicit release/dependency follow-ups. This is not a clean bill of health. [MobSF report](<D:/new itantra/artifacts/security-hardening-2026-10-08/complete-mobsf/mobsf-report.json>) and [local-only policy](<D:/new itantra/artifacts/security-hardening-2026-10-08/complete-mobsf/mobsf-local-policy.json>). External connections, reputation updates, VirusTotal and uploads were blocked by the scanner runner.
- [Combined verification](<D:/new itantra/artifacts/security-hardening-2026-10-08/complete-verification.json>) binds the final scan to the signed APK SHA-256, signature, binary checks and 570 tests. [The source/build-input snapshot](<D:/new itantra/artifacts/security-hardening-2026-10-08/verified-inputs.json>) records 390 file hashes; none of those inputs changed after the release APK build.

## Remaining limits and release work

The APK uses a **development signing certificate for phone testing**. It is not a public production release. The [unsigned optimized APK](<D:/new itantra/artifacts/security-hardening-2026-10-08/iTantra-1.15-security-release-unsigned.apk>) needs the owner's production key and a verified update/distribution plan before publication. See [Android release preparation guidance](https://developer.android.com/studio/publish/preparing).

The final MobSF scan retains warnings for Android 8 support, a permission-protected AndroidX profile receiver, generic raw-SQL/Random/hardcoded-value matches, clipboard use, app-specific external files and SDK certificate SHA-1 metadata. Review traced the cryptographic random/key paths to SecureRandom and the app encryption to AES-GCM/ECDH/HKDF-SHA256; generic scanner matches do not demonstrate a cryptographic or SQL injection exploit. Random matches are AndroidX scheduling/Kotlin platform RNGs; hardcoded matches are field labels in object toString methods. SQL library queries use fixed SQL/bound arguments, and the app's deletion tables are whitelisted. Clipboard copying remains an explicit user action. Android logging output calls were eliminated, but some system/library/native diagnostics can remain; runtime Logcat during model initialization/downloads still needs a phone check.

The ONNX Runtime RUNPATH flag and three native FORTIFY warnings remain dependency-hardening follow-ups. They were not patched blindly, and no exploit of those binaries was demonstrated. Native components are unchanged and still need vendor/version review and device testing.

No phone was attached. Physical Bluetooth/Wi-Fi Direct penetration tests, Android SQLCipher selective-deletion tests, UI confirmation/recovery tests and on-device STT/TTS after optimization remain unverified. The host suite includes real encrypted session traffic and TCP loopback integration, but does not substitute for physical radios.

On two phones, test both connection initiation/acceptance orders, disconnect during translation/playback, reconnect to a different peer, retry a rejected message, trigger and clear each peer's SOS, and confirm unselected data/models survive each manual deletion option. Verify recycled data is included, and test interrupted deletion recovery using test data before relying on this feature.
