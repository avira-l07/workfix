"""Summarize executed evidence. Refuse to report a successful unfinished build."""
from pathlib import Path
import hashlib, json, shutil, zipfile, xml.etree.ElementTree as ET

here = Path(__file__).resolve().parent
root = here.parents[2]
build = here.parent / 'build/app'
log = (here / 'final-main-verification.log').read_text(encoding='utf-8', errors='replace')
assert 'BUILD SUCCESSFUL' in log, 'Final Gradle verification has not passed'
tests = [ET.parse(f).getroot() for f in (build / 'test-results/testDebugUnitTest').glob('TEST-*.xml')]
totals = {key: sum(int(t.get(key, 0)) for t in tests) for key in ('tests', 'failures', 'errors', 'skipped')}
assert totals['tests'] > 420 and totals['failures'] == totals['errors'] == 0, totals
lint_path = build / 'reports/lint-results-debug.xml'
lint = ET.parse(lint_path).getroot()
severity = {}
for issue in lint.findall('issue'):
    severity[issue.get('severity')] = severity.get(issue.get('severity'), 0) + 1
assert not severity.get('Error') and not severity.get('Fatal'), severity
security = json.loads((here / 'dependency-security.json').read_text())
assert isinstance(security['osv'], list) and not security.get('osv_error')
packages = json.loads((here / 'packages.json').read_text())
apks = [p for p in packages if p['path'].endswith('.apk')]
for package in apks:
    assert not package['onnx_weight_assets']
    assert not any('libitantra_mt_jni' in lib['path'] for lib in package['native_libraries'])
    assert hashlib.sha256(Path(package['path']).read_bytes()).hexdigest() == package['sha256']
debug = next(p for p in apks if p['path'].endswith('app-debug.apk'))
delivery = root / 'artifacts/audit-fixes-2026-10-04/app-debug.apk'
delivery.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(debug['path'], delivery)
assert hashlib.sha256(delivery.read_bytes()).hexdigest() == debug['sha256']
with zipfile.ZipFile(delivery) as archive:
    dex = b''.join(archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
    for marker in ('isDigitalSilence', 'requireEncryptablePayload', 'reportContinuousFailure', 'RUN TEN-LANGUAGE SELF-TEST'):
        assert marker.encode() in dex, f'New code marker absent from delivered APK: {marker}'
before = json.loads((here / 'before-hashes.json').read_text())
changed = [name for name, digest in before.items()
    if (root / name).is_file() and hashlib.sha256((root / name).read_bytes()).hexdigest() != digest]
additional = [
    'app/src/main/java/com/itantra/core/inference/TtsTextCoverage.kt',
    'app/src/main/assets/language_packs/kn_dev_manifest.json',
    'app/src/main/assets/benchmark/five_self_test/kn.wav',
    'app/src/main/assets/benchmark/five_self_test/manifest.json',
    'app/src/test/java/com/itantra/core/crypto/AuditFixRegressionTest.kt',
    'app/src/test/java/com/itantra/core/inference/TtsTextCoverageTest.kt',
    'app/src/test/java/com/itantra/core/inference/DigitalSilenceTest.kt',
    'app/src/test/java/com/itantra/core/service/ForegroundServiceCompatibilityTest.kt',
    'app/src/test/java/com/example/itantra/ui/theme/AppearanceRegressionTest.kt',
    'app/src/test/java/com/example/itantra/data/settings/AppearancePersistenceTest.kt',
    'app/src/test/java/com/example/itantra/ui/screens/voicenotes/VoiceNoteGroupingTest.kt',
    'app/src/test/java/com/itantra/core/inference/AdditionalSttModelTest.kt',
    'app/src/test/java/com/itantra/core/inference/FiveLanguageSelfTestTest.kt',
    'app/src/test/java/com/itantra/core/inference/MultilingualTtsIntegrationTest.kt',
    'app/src/test/java/com/itantra/core/transceiver/PipelineGeneralizationTest.kt',
    'docs/FRESH_AUDIT_2026-10-04.txt', 'docs/QUICK_STAT_SUMMARY.md', 'docs/LANGUAGE_BENCHMARKS.md',
    'docs/AUDIT_FIXES_2026-10-04.txt',
]
kn = json.loads((here / 'five-language-kn-30.json').read_text(encoding='utf-8'))['summary']
old = json.loads((here / 'kn-tiny-30.json').read_text(encoding='utf-8'))
findings = [
('F01', 'FIXED IN DEPENDENCY GRAPH', 'ML Kit resolved vulnerable OkHttp 3.0.0.',
 'Constrained the transitive runtime to OkHttp 4.12.0. ML Kit remains the translation engine.',
 '169 resolved runtime Maven modules checked with OSV: no advisories returned. Native/AAR/tool/test libraries are outside this scan; this is not a security certification.'),
('F02', 'MANUAL KANNADA PATH FIXED; QUALITY LIMITS REMAIN', 'Tiny produced Latin/wrong-language text and hallucinated on a direct digital-silence probe.',
 'Manual Kannada now downloads and uses the pinned dedicated CTC model. Added exact all-zero/empty waveform guards in both normal finalization and direct decode. No quiet-speech threshold was introduced. Kannada added to the in-app self-test.',
 'Same 30 clean desktop WAVs: WER 169.80% -> 17.23%, native script 0/30 -> 30/30. Raw CTC still emits a character on 1/2-second zeros; the new app guard prevents native decoding for digital silence, verified by unit checks. Real background noise, Android audio capture and all two-phone outcomes: Not verified. Auto-detect still uses Tiny and is explicitly labelled unreliable for Kannada; select manual Kannada.'),
('F03', 'FIXED IN SHARED SENDER', '16,384 plaintext bytes became 16,400 encrypted bytes and exceeded the receiver limit.',
 'A shared guard reserves the 16-byte GCM tag: plaintext maximum 16,368 UTF-8 bytes, ciphertext maximum 16,384. Encoder bounds checked too. Rejected sends retain the message with a readable error; oversize retries stop. The chat input disables oversize sends.',
 'Regression covers the exact Hindi UTF-8 boundary through encrypt/frame/decode/decrypt, the first oversize byte, unchanged next counter, and emoji round-trip. Final translated payloads are guarded too.'),
('F04', 'MITIGATED; REPLACEMENT MARATHI VOICE STILL NEEDED', 'The MMS Marathi voice silently skipped U+0949 (ॉ) in the doctor phrase.',
 'Load uses a native Marathi startup phrase. Synthesis checks actual voice vocabulary before native generation. Unsupported Marathi letters/marks produce an explicit voice error; original text is preserved. Unannounced Android TTS fallback is blocked for Marathi and Kannada as well as the original strict languages.',
 'Unit checks cover the native startup phrase, डॉक्टरांना बोलवा, and foreign letters. Published facebook/mms-tts-mar vocabulary was fetched and also lacks this vowel. Reinstalling that voice cannot repair it. This fixes silent loss, not pronunciation. A broader Sherpa-compatible voice or retraining, plus listener assessment, remains required.'),
('F05', 'FIXED IN SOURCE; DEVICE UI NOT VERIFIED', 'Static TAC-RELIEF-04 and cohort/channel labels looked like live operational data.',
 'Hub and Connect use verified peer identity and actual direct connection state; absent identity is labelled unavailable/pending. Removed the invented channel/cohort labels.',
 'Source checks confirm those strings are removed. Real radio/satellite/multi-hop integration is not created or claimed.'),
('F06', 'NATIVE APPEARANCE IMPLEMENTED; VISUAL DEVICE CHECK PENDING', 'Preview appearance options were absent in native UI and fixed light colors broke dark mode.',
 'Native Compose theme supports System/Light/Dark and Ocean/Forest/Iris/Ember. Settings persist atomically in DataStore. MainActivity applies the saved theme. Existing screens use semantic surface/text/status colors; drawing colors are captured in composable scope. Color changes animate over 180 ms.',
 'All eight scheme combinations pass tested 4.5:1 text contrast pairs. Persistence/concurrent preference updates pass. These numeric token checks do not certify every rendered screen, transition, font size, TalkBack order or sunlight readability. Full HTML-preview workflow parity is not claimed.'),
('F07', 'FIXED AT PROFILE PARSER', 'Empty IDs, unsupported protocol versions and malformed profiles could replace active peer identity.',
 'Reject unsupported/non-integer versions, malformed IT-XXXX-XXXX IDs, blank/control/oversize names, missing/unknown/duplicate languages and oversize payloads. Rejected profiles never reach the existing identity-update branch.',
 'Regression covers valid round-trip plus malformed JSON, blank IDs, v999/v1.5/string versions, control text, duplicate/unknown languages, and byte/name limits. Existing blank-row DAO guard remains.'),
('F08', 'FIXED IN SOURCE', 'Unconditional profile logs exposed peer IDs and names in release logs.',
 'Profile sent/received logs now describe validation state without identity values.',
 'Source check performed. Installed release log capture and external privacy certification: Not verified.'),
('F09', 'FIXED AND STRESS-TESTED', 'Concurrent decrypt calls could authenticate the same counter before replay state was committed.',
 'After AEAD authentication, require the existing synchronized replay-window accept/commit to succeed before returning plaintext.',
 '50 rounds x 8 concurrent decrypt calls: exactly one acceptance per authenticated packet in the passing regression. Existing tamper/session tests also pass. No remote exploit or phone attack was claimed.'),
('F10', 'FIXED AND TESTED', 'Native notes grouped offsets 2..7 as seven days, while preview used 2..6.',
 'Native rolling seven-date interval now covers today through six days ago, with Today/Yesterday kept separate.',
 'Fixed-date tests cover today, yesterday, 6/7/10 days ago, last month and 13 months ago, plus local midnight and a Europe/Berlin daylight-saving boundary. Note deletion isolation tests remain green.'),
('F11', 'REMOVED FROM DEFAULT APK', 'Unused Nearby dependency and experimental CTranslate2 JNI increased the shipped runtime.',
 'Removed unused Nearby. CMake/CTranslate2 build is explicitly opt-in with -PitantraExperimentalTranslation=true; default APK uses existing ML Kit translation.',
 'Fresh debug/release APK inventories contain 8 arm64 native libraries, no libitantra_mt_jni, and no ONNX weight assets. Selective downloads are preserved. JNI load-alignment metadata is >=16 KiB; this is not an Android device-load test.'),
('F12', 'FIXED IN SOURCE', 'Settings displayed an ANDROID_ID-derived hash/fake fallback rather than the peer protocol ID.',
 'Settings reads the persistent DeviceProfileManager IT-XXXX-XXXX identity used by the handshake. Missing identity is labelled unavailable.',
 'Source wiring checked and builds pass. Pairing-screen comparison on phones: Not verified. An unrelated internal node-ID hash is still used by the coordinator and is not presented as this Settings ID.'),
('F13', 'COMPATIBILITY AND CLEANUP FIXED; DEVICE REPRODUCTION PENDING', 'API29 used the API30 microphone foreground-service type; startup failures left operation flags/wakelocks running.',
 'API26-29 use the two-argument foreground call; API30+ use microphone type. Acquire a bounded renewable wakelock only after foreground startup succeeds. Clear readiness on stop/failure, report errors to the hub, wait for service readiness before capture, and stop capture on failure/timeout. Preserve an already-active emergency when continuous startup fails.',
 'API type compatibility unit check passes. Android permission denial, service lifecycle, screen-off capture and wakelock behavior were not exercised on a phone.'),
]
lines = [
 'iTANTRA - AUTHORIZED AUDIT FIXES AND VERIFICATION',
 'Date: 4 October 2026 (Asia/Kolkata)', f'Repository: {root}',
 f'Original pre-fix audit: {root / "docs/FRESH_AUDIT_2026-10-04.txt"}',
 '', 'RESULT', '======',
 'The audited code defects have fixes or explicit safeguards. This is not a claim that all product quality requirements are met.',
 'Marathi pronunciation remains incomplete. Kannada manual recognition improved but still misses the accuracy target; auto-detect remains unreliable.',
 'Phone execution, field noise and live two-phone validation remain Not verified. No new trained model was fabricated or claimed.',
 f'Final full regression: {totals["tests"]} tests; {totals["failures"]} failures; {totals["errors"]} errors; {totals["skipped"]} skipped.',
 f'Final production lint issue counts: {severity}. Warnings/hints remain. Unit/Android-test source static lint was excluded after repeated stalls; full unit execution was not filtered.',
 f'Final build command: gradlew.bat -I audit/2026-10-04/fixes/verify.init.gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:fixRuntimeInventory -x :app:lintAnalyzeDebugUnitTest -x :app:lintAnalyzeDebugAndroidTest -Pkotlin.incremental=false --no-daemon --offline',
 'The full unit suite was executed successfully on both the last JDK25 run and the temporary JDK21 run. The final build uses the original repository daemon pin and existing Gradle cache. Unit sources were not replaced or filtered.',
 '', 'FINDING-BY-FINDING RECORD', '========================',
]
for ident, state, problem, fix, evidence in findings:
    lines += [f'{ident} - {state}', f'Problem: {problem}', f'Change: {fix}', f'Verification / boundary: {evidence}', '']
lines += ['FRESH KANNADA BEFORE/AFTER', '==========================',
 f'Tiny: WER {old["wer"]:.2%}; mean {old["mean_ms"]:.1f} ms; native {old["native_count"]}/30.',
 f'CTC: WER {kn["wer"]:.2%}; CER {kn["cer"]:.2%}; mean {kn["decode_ms_mean"]:.1f} ms; p95 {kn["decode_ms_p95_nearest_rank"]:.1f} ms; RTF {kn["rtf"]:.4f}; native {kn["native_script_count"]}/30.',
 f'CTC peak desktop working set: {kn["peak_desktop_working_set_bytes"]:,} bytes. Model + tokens: 197,663,333 bytes; full Kannada speech pack: 311,709,264 bytes.',
 'These are 30 clean FLEURS desktop recordings with checked WAV hashes, not an independent noisy field corpus. Timings exclude model load and were taken while other development work ran.',
 'Hindi/English model files, decoding flags and translation engine were not changed. Exact digital-zero gating affects only empty/all-zero audio for every language; nonzero quiet samples retain the old inference path.',
 'No fresh 30-utterance TTS benchmark was run during these fixes; previously reported TTS numbers stay explicitly desktop references.',
 '', 'APKS', '====', f'Deliverable debug APK: {delivery}',
]
for package in apks:
    lines += [f'Build path: {package["path"]}', f'Bytes: {package["bytes"]:,}; SHA-256: {package["sha256"]}',
        f'Native libraries: {len(package["native_libraries"])}; bundled ONNX weights: {len(package["onnx_weight_assets"])}.']
lines += ['Debug is installable for compatible arm64 Android devices. Release is unsigned and is not ready for installation/distribution.',
 'Delivered APK DEX was checked for the new digital-silence, payload-limit, service-failure and ten-language self-test code markers.',
 'The build directory is a junction to temporary C: storage because D: had limited free space. The deliverable debug copy above is stored in the repository artifacts folder and its hash was checked.',
 '', 'FILES TOUCHED IN THIS FIX PASS', '=============================',
 'Pre-existing work was present. The main-source list is compared against SHA-256 snapshots taken at this fix pass start; this is not the full historical git diff.']
lines += sorted(set(changed + additional))
lines += ['audit/2026-10-04/fixes/: new benchmark/verification/report scripts, logs and evidence files.',
 '', 'VERIFICATION EVIDENCE', '=====================',
 f'Evidence directory: {here}',
 'final-main-verification.log: completed Gradle verification with unit/Android-test source lint exclusions; earlier attempt logs are retained separately.',
 'test-results-summary.json / test-results/: copied final XML evidence and totals.',
 'lint-results-debug.xml / lint-results-debug.txt: final issues, including retained warnings/hints.',
 'dependencies.json / dependency-security.json: resolved runtime Maven versions and OSV results.',
 'packages.json / package.log: APK hashes, weight inventory, native libraries and ELF alignment metadata.',
 'kn-tiny-30.json / five-language-kn-30.json: fresh same-corpus results.',
 'kannada-silence.json: raw model zero-PCM behavior motivating the app guard.',
 'marathi-published-vocabulary.json / marathi-vocab.json: upstream vocabulary check.',
 '', 'BUILD/TEST ISSUES ENCOUNTERED AND RESOLVED', '=======================================',
 'Initial sandbox Gradle attempts could not write the installed cache; authorized elevated execution reused that cache.',
 'Stale incremental Kotlin metadata caused unresolved top-level symbols. Full Kotlin compilation resolved this; a few real composable color/coroutine import errors were corrected.',
 'The first regression run had a JUnit test method return-type mistake and an obsolete Kannada Tiny expectation. Both were corrected; the full suite was rerun.',
 'Unit-source static lint stalled during file traversal under JDK25 and JDK21; Android-test source analysis also stalled. The repository daemon pin overrode the initial JAVA_HOME=JDK21 retry; it was temporarily changed for the JDK21 run and then restored. Only this audit daemon was stopped after checking its process command. Final lint excludes both stalled test-source analyses and stale scratch results were removed. Production lint remains enabled. Full test-source static lint is Not verified; the root cause of the stall is not established.',
 'A desktop probe console hit Windows cp1252 Unicode output; its JSON was saved correctly. Console output was made ASCII-safe and the probe rerun.',
 '', 'REMAINING LIMITS / NEXT PHONE CHECKS', '===================================',
 '1. Install the deliverable APK. Select manual Kannada and download the new dedicated STT pack; an old Tiny download is insufficient. Run Diagnostics ten-language self-test.',
 '2. Marathi: supported native text can synthesize; unsupported letters now show an error. A voice supporting the doctor vowel is still required before calling Marathi fully ready.',
 '3. Verify all ten voices with native listeners; record microphone transcripts, audibility, latency, RAM and real two-phone outcomes. Do not mark a language validated from a desktop run or the one-clip self-test.',
 '4. Confirm Android foreground-service permission rejection and screen-off behavior, native theme screens, real Settings/peer ID consistency, and release logs on devices.',
 '5. Tamil, Telugu, Odia, Marathi, Kannada and Malayalam still miss at least the current accuracy target. These audit fixes did not train models or produce new WER claims for the other languages.',
 '6. General Malayalam/Odia translation and radio/satellite/mesh capability remain as previously documented. No new gateway integration is claimed.',
 '7. OSV scan covers resolved runtime Maven packages only. Existing dependency-maintenance and lint warnings remain. Review the original audit for licence and field-readiness limits.',
 '8. Native visual rendering/browser interaction was not verified here. A prior local-file browser policy rejection was not bypassed.',
 'No phone was controlled, no user history/database was wiped, and no Git commit or push was performed in this pass.',
]
test_copy = here / 'test-results'
test_copy.mkdir(exist_ok=True)
for f in (build / 'test-results/testDebugUnitTest').glob('TEST-*.xml'): shutil.copy2(f, test_copy / f.name)
(here / 'test-results-summary.json').write_text(json.dumps(totals, indent=2), encoding='utf-8')
shutil.copy2(lint_path, here / lint_path.name)
shutil.copy2(build / 'reports/lint-results-debug.txt', here / 'lint-results-debug.txt')
(root / 'docs/AUDIT_FIXES_2026-10-04.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
for name in ('QUICK_STAT_SUMMARY.md', 'LANGUAGE_BENCHMARKS.md'):
    p = root / 'docs' / name
    p.write_text(p.read_text(encoding='utf-8').replace('442 unit tests', f'{totals["tests"]} unit tests')
        .replace('passed 442 tests', f'passed {totals["tests"]} tests'), encoding='utf-8')
print(json.dumps({'tests': totals, 'lint': severity, 'report': str(root / 'docs/AUDIT_FIXES_2026-10-04.txt'),
    'debug_apk': str(delivery), 'main_changed_files': len(changed)}, indent=2))
