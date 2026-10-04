"""Append measured evidence to the fresh report; does not modify app code."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import statistics
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
REPORT = ROOT / 'docs/FRESH_AUDIT_2026-10-04.txt'
MARKER = 'APPENDIX A - FRESH DESKTOP SPEECH SMOKE RESULTS'
names = dict(hi='Hindi', en='English', ta='Tamil', te='Telugu', or_='Odia',
             bn='Bengali', gu='Gujarati', mr='Marathi', ml='Malayalam', kn='Kannada')
names['or'] = names.pop('or_')
codes = ['hi', 'en', 'ta', 'te', 'or', 'bn', 'gu', 'mr', 'ml', 'kn']
lines = []


def section(title):
    lines.extend(['', title, '=' * len(title)])


def read(name):
    return json.loads((HERE / name).read_text(encoding='utf-8-sig'))


section(MARKER)
lines.extend([
    'Each row: ONE clean reference recording; THREE short warmed TTS phrases.',
    'These are smoke tests. Sample WER is NOT overall language accuracy.',
    'No phone validation, listener rating, p95, peak RAM or battery measurement.',
    'Times include contention from concurrent audit/build work on this PC.',
    'STT: four threads, 16-kHz mono, 100-ms trailing pad, greedy decoding.',
    'TTS: one thread, one warm-up, local VITS/MMS model; all outputs 16 kHz.',
    'WER: NFC/lowercase normalization, punctuation -> spaces, token edit distance.',
    'This normalization is not interchangeable with other benchmark protocols.',
    '',
    'Language  | Sample WER | STT ms | RTF   | TTS mean/max ms | Expected script',
    '----------|------------|--------|-------|-----------------|----------------',
])
speech = {}
for code in codes:
    s = read(f'speech-{code}.json')
    speech[code] = s
    t = [p['synthesis_ms'] for p in s['tts']]
    stt = s['stt']
    lines.append(f"{names[code]:9} | {stt['single_recording_wer'] * 100:9.2f}% | "
                 f"{stt['decode_ms']:6.0f} | {stt['rtf']:5.3f} | "
                 f"{statistics.mean(t):7.0f}/{max(t):4.0f}    | "
                 f"{'Observed' if stt['native_script_majority'] else 'FAILED'}")
lines.extend(['', 'Phone status for EVERY row: Not verified.',
              'Non-silent PCM for EVERY TTS row: observed; intelligibility: Not verified.',
              'Nine CTC models were selected in manual language mode. Kannada used',
              'the active Whisper Tiny fallback with kn / transcribe and app tokens.',
              'English is NeMo FastConformer INT8, not IndicConformer: its original',
              'probe JSON contains a generic CTC label. Model filename/hash and the',
              'current AdditionalSttModel declaration establish the correct identity.',
              '', 'Direct model silence outputs (one second of zeros + 100-ms pad):'])
for code in codes:
    silence = speech[code]['silence']['output']
    lines.append(f"  {names[code]}: {json.dumps(silence, ensure_ascii=False)}")
lines.extend([
    'Seven model probes returned characters on pure silence. Three returned empty.',
    'These probes bypass the production VAD/utterance gate. This is model-level',
    'behavior; no silent phone transmission is asserted. Verify noise rejection',
    'on phones before broadening accuracy claims.',
    '', 'Local STT/TTS file bytes, load time and hashes:',
    'These are on-disk source/provisioning weights, not APK contents or peak RAM.',
])
for code in codes:
    s = speech[code]
    model = 'NeMo FastConformer CTC INT8' if code == 'en' else s['stt_model']
    lines.extend(['', f"{names[code]} ({code}) - {model}",
                  f"  STT load: {s['stt_load_ms']:.0f} ms; TTS load: {s['tts_load_ms']:.0f} ms.",
                  f"  STT total: {sum(f['bytes'] for f in s['stt_files']):,} bytes.",
                  f"  TTS total: {sum(f['bytes'] for f in s['tts_files']):,} bytes."])
    for kind in ('stt_files', 'tts_files'):
        for f in s[kind]:
            lines.extend([f"  {kind[:-6].upper()}: {f['path']}",
                          f"    {f['bytes']:,} bytes; SHA-256 {f['sha256']}"])
    for i, p in enumerate(s['tts']):
        lines.append(f"  TTS[{i}] {json.dumps(p['phrase'], ensure_ascii=False)}: "
                     f"{p['synthesis_ms']:.0f} ms, {p['samples']} samples, "
                     f"peak {p['peak_amplitude']:.4f}, finite={p['finite']}, "
                     f"missing-token characters={p['missing_token_letters']}")
    lines.append(f"  Actual STT output: {s['stt']['output']}")
    lines.append(f"  Word edits/reference words: {s['stt']['word_errors']}/{s['stt']['reference_words']}.")

section('APPENDIX B - FRESH PACKAGES')
packages = read('packages.json')
for p in packages:
    lines.extend(['', p['path'], f"  Bytes: {p['bytes']:,}; MiB: {p['bytes'] / 1048576:.2f}.",
                  f"  SHA-256: {p['sha256']}",
                  f"  ONNX weight assets found: {len(p['onnx_weight_assets'])}.",
                  f"  Benchmark WAVs bundled: {p['bundled_benchmark_wav_count']}.",
                  f"  Native libraries: {len(p['native_libraries'])}."])
    for lib in p['native_libraries']:
        aligns = lib['elf_load_segment_alignments']
        lines.append(f"    {lib['path']}: {lib['bytes']:,} bytes; "
                     f"PT_LOAD alignment bytes={aligns if aligns else 'not parsed (32-bit ELF)'}")
lines.extend([
    '', 'The two APKs contain only arm64-v8a native code. The local AAR also',
    'contains other ABIs; its 32-bit ELF segment alignment was not parsed.',
    'Packaged arm64 PT_LOAD alignment: >=16,384 bytes for every inspected segment.',
    'ZIP page alignment, install/run on a 16-KiB device, and signature verification',
    'were not tested. Release APK is UNSIGNED, and is not a distribution release.',
    'Model downloads remain necessary. APK size is not total installed-model size.',
    'Debug/release packaging tasks completed in verification.log. That combined',
    'command failed at the then-incorrect audit fixture; the corrected fresh test',
    'run subsequently succeeded. No false overall build-success claim is made.',
])

section('APPENDIX C - DEPENDENCIES AND PUBLIC MODEL ENDPOINTS')
deps = read('dependency-security.json')
lines.extend([
    '170 resolved debug-runtime Maven coordinates queried live against OSV.',
    'One coordinate matched two advisory records: com.squareup.okhttp3:okhttp:3.0.0.',
    'No additional matches were returned for the queried coordinates.',
    'This excludes native source/local AAR internals, build tools, test-only',
    'dependencies and unknown vulnerabilities. Absence of a match is not a guarantee.',
    '', 'Resolved current / registry latest stable (queried this audit):',
])
for d in deps['outdated_checks']:
    lines.append(f"  {d['group']}:{d['artifact']}: {d['current']} / {d['latest_stable']} "
                 f"({'outdated' if d['outdated'] else 'current in queried registry'}).")
    lines.append(f"    Source: {d['primary_registry']}")
for key, advisory in deps['advisories'].items():
    lines.extend(['', f"  {key}: {advisory['summary']}",
                  f"    Aliases: {', '.join(advisory['aliases'])}",
                  f"    Queried record: https://api.osv.dev/v1/vulns/{key}"])
endpoints = read('download-endpoints.json')
counts = Counter(str(e.get('status')) for e in endpoints)
lines.extend(['', f"Configured root weight endpoint HEAD checks: {len(endpoints)}; statuses {dict(counts)}.",
              'Includes ten manifest STT weights, ten TTS weights and nine manual CTC weights.',
              'All requested public weight endpoints answered 200. No authentication',
              'secret was sent. No complete model download was performed by this probe.',
              'Token/lexicon endpoints, all mirror/resume behaviors and Android download',
              'installation are not proven by successful root-weight HEAD checks.'])
for e in endpoints:
    lines.append(f"  {e['language']} / {e['component']}: HTTP {e['status']}; {e['url']}")

section('APPENDIX D - NEW AUDIT TESTS AND RAW OBSERVATIONS')
suite = ET.parse(HERE / 'fresh-test-results.xml').getroot()
lines.extend([f"Suite {suite.get('name')}; timestamp UTC {suite.get('timestamp')}.",
              f"Tests={suite.get('tests')}, failures={suite.get('failures')}, "
              f"errors={suite.get('errors')}, skipped={suite.get('skipped')}.",
              'Only the fresh audit.* suite execution counts as evidence.',
              'Some probes assert/document existing defective behavior; a green probe',
              'means successful observation, not correction of the observed defect.'])
for t in suite.findall('testcase'):
    lines.append(f"  PASS {t.get('name')} ({t.get('time')} s)")
lines.extend(['', 'Raw output:', suite.findtext('system-out', default='').strip(),
              '', 'Harness stderr:', suite.findtext('system-err', default='').strip(),
              '', 'Important boundaries:',
              '- SQLite migration SQL 2->5 ran on desktop SQLite with a test adapter.',
              '  This is not Android Room/SQLCipher/Keystore integration validation.',
              '- Note deletion preserved one separate peer row. Chat/emergency row',
              '  preservation was source-traced, not exercised by this deletion test.',
              '- Storage tests used an in-memory key provider, not Android Keystore.',
              '- Invalid location parser values were accepted directly, but the real',
              '  coordinator validates length, coordinates and accuracy downstream.',
              '  Do not treat that parser observation as an end-to-end security bug.',
              '- Decrypt concurrency stress bypassed the serialized receive collector.',
              '  Its race is an API hardening finding, not a proven remote exploit.',
              '- No prior 420/other historical suite pass count is re-certified here.',
              '- The original migration-v1 fixture error was repaired only in this',
              '  audit harness; the product migration-v1 path remains unverified.'])

section('APPENDIX E - COMPLETE FRESH ANDROID LINT LOCATION INVENTORY')
issues = ET.parse(HERE / 'lint-results.xml').getroot().findall('issue')
lines.append(f"Issues by severity: {dict(Counter(i.get('severity') for i in issues))}.")
lines.append(f"Issue counts by ID: {dict(Counter(i.get('id') for i in issues))}.")
lines.extend(['These lint warnings are not counted as 74 additional confirmed defects.',
              'They include dependency/catalog recommendations and maintenance warnings.',
              'Source/lint concerns require device confirmation as stated in findings.'])
for index, i in enumerate(issues, 1):
    lines.append(f"{index:02}. [{i.get('severity')}] {i.get('id')}: {i.get('message')}")
    for loc in i.findall('location'):
        lines.append(f"    {loc.get('file')}:{loc.get('line', '?')}")

section('APPENDIX F - BUILD/HARNESS WARNING AND ERROR RECORD')
lines.extend([
    'All original logs, including failed setup attempts, are retained in the audit folder.',
    'Initial failures: D: storage exhaustion; retry output-path cross-drive mismatch;',
    'Gradle Kotlin source override API mismatch; incorrect schema-v1 audit fixture.',
    'A report source-line display initially hit Windows cp1252 UnicodeEncodeError;',
    'rerunning the display with UTF-8 output succeeded. App behavior was unaffected.',
    'None is silently presented as a passing application run.',
    'Current source compiled and packaged after setup recovery. Warnings remain.',
    'The following is a deduplicated warning-line inventory, not only a count.',
    'Full surrounding compiler and task output is available in the original logs.',
])
warnings = {}
for logfile in ('build.log', 'verification.log', 'final-checks.log', 'fresh-tests-final.log'):
    for index, line in enumerate((HERE / logfile).read_text(encoding='utf-8-sig', errors='replace').splitlines(), 1):
        if re.search(r'(^w:|warning:|WARNING:|Kotlin does not yet support)', line):
            warnings.setdefault(line, f'{logfile}:{index}')
for warning, origin in warnings.items():
    lines.extend([f'  {origin}', f'    {warning}'])
lines.extend(['', 'SQLCipher native security/source audit and local-AAR SBOM generation were',
              'not performed. Maven advisory coverage must not imply native coverage.',
              'The SLF4J no-binding message belongs to the desktop SQLite test harness.',
              'It does not prove a missing Android runtime logger.',
              'JDK 25/Kotlin fallback and native-access warnings are recorded above.',
              'Old test files were compiled in some attempts; their deprecation warnings',
              'are listed, but their old test outcomes are excluded from verification.'])

section('APPENDIX G - STATIC ROUTES, ACTIONS, PLACEHOLDERS AND CONTRAST')
static = read('static-inventory.json')
lines.append('Declared preview routes: ' + ', '.join(dict.fromkeys(static['preview_routes'])))
lines.append('Declared preview action cases (NOT clicked):')
lines.extend('  ' + action for action in static['preview_declared_action_cases'])
lines.append('Referenced preview assets:')
lines.extend(f"  {a['path']}: exists={a['exists']}" for a in static['preview_asset_links'])
lines.append('Token-only contrast examples (not rendered-widget measurements):')
lines.extend(f"  {c['foreground']} on {c['background']}: {c['ratio']}:1"
             for c in static['native_static_color_contrast'])
lines.extend([
    'No screen WCAG claim follows from these examples. Browser rendering is unverified.',
    'Pure preview bucket observations:', json.dumps(read('preview-pure-results.json'), ensure_ascii=False, indent=2),
    '', 'Fresh TODO/stub search in production app + preview:',
    '- data_extraction_rules.xml:13 retains template backup TODO; manifest disables',
    '  backups, so this is a template/config cleanup item rather than a demonstrated leak.',
    '- LanguagePackRepository.kt:60 retains a historical NotImplementedError comment.',
    '- ActiveLanguageSessionManager.kt:441-447 has an explicit NoOpEngineFactory',
    '  whose methods throw NotImplementedError. AppGraph.kt:180-203 instead supplies',
    '  an EngineFactory implementation that constructs the real Sherpa engines.',
    '  No production mic no-op claim follows from the presence of this test/default stub.',
    '- No production/preview console.log or Math.random occurrence was returned by',
    '  the scoped search. Random crypto/device IDs are legitimate source data.',
    'Dormant Nearby/CTranslate2 integrations are tracked separately in F11.',
])

section('APPENDIX H - REPRODUCTION, FILE CHANGES AND NEXT APPROVAL')
lines.extend([
    'Run from D:\\new itantra in PowerShell with the existing Android SDK/JDK configured.',
    'The successful audit logs resolved dependencies from C:\\Users\\avira\\.gradle.',
    'Reuse that configured cache; do not require a fresh download just to reproduce.',
    'Fresh test command (init script isolates audit execution/output):',
    '  .\\gradlew.bat -I audit/2026-10-04/audit.init.gradle :app:testDebugUnitTest --no-daemon',
    'Build tasks and resolved inventory:',
    '  .\\gradlew.bat -I audit/2026-10-04/audit.init.gradle :app:assembleDebug :app:assembleRelease :app:lintDebug :app:auditRuntimeInventory --no-daemon',
    'Dependency chain:',
    '  .\\gradlew.bat -I audit/2026-10-04/audit.init.gradle :app:dependencyInsight --dependency okhttp --configuration debugRuntimeClasspath --no-daemon',
    'Additional probes:',
    '  python audit/2026-10-04/speech_probe.py',
    '  python audit/2026-10-04/dependency_probe.py',
    '  python audit/2026-10-04/download_endpoint_probe.py',
    '  python audit/2026-10-04/package_probe.py',
    '  python audit/2026-10-04/static_probe.py',
    '  node --check itantra_light_ui/app.js',
    '  node --check itantra_light_ui/theme.js',
    '  node audit/2026-10-04/preview_pure_probes.cjs',
    'Speech requires the local models/audio inputs and desktop sherpa-onnx 1.13.8,',
    'numpy and soundfile. Public advisory/metadata/endpoint probes need internet.',
    'Core JVM tests use current source/classes; the final filtered command log is',
    'fresh-tests-final.log. Do not run clean against unrelated prior outputs.',
    '', 'Files added/created by the audit:',
    str(REPORT),
])
lines.extend('  ' + str(p) for p in sorted(HERE.rglob('*'))
             if p.is_file() and 'build' not in p.relative_to(HERE).parts)
lines.extend([
    'Generated build products remain under audit/2026-10-04/build (junction to C: temp).',
    'Gradle/native tools also generated their normal cache/.cxx metadata.',
    'No production source/dependency/model-manifest fixes were made.',
    'No existing tests, app DB, chats, emergency records or settings were edited.',
    'No phone access, model substitution, deletion of installed packs, commit or push.',
    '', 'Primary references used for the current advisory/version checks:',
    'https://google.github.io/osv.dev/post-v1-querybatch/',
    'https://api.osv.dev/v1/vulns/GHSA-3cqm-mf7h-prrj',
    'https://api.osv.dev/v1/vulns/GHSA-4hc2-jh7r-wrc3',
    'https://github.com/square/okhttp/pull/6741',
    'https://publicobject.com/2016/02/11/okhttp-certificate-pinning-vulnerability/',
    'Exact primary Maven/Google registry URLs are in Appendix C.',
    '', 'Next decision: which findings should be fixed?',
    'Suggested first batch: F01, F03, F02 and F04. See the prioritized plan above.',
    'These are proposed fixes; no fix is approved merely because it appears here.',
    'The attachment explicitly requires approval after this report. Product',
    'implementation therefore stops here pending the user\'s choice.',
])
prefix = REPORT.read_text(encoding='utf-8').split(MARKER, 1)[0]
REPORT.write_text(prefix + '\n'.join(lines).lstrip('\n') + '\n', encoding='utf-8')
print(f'Report completed: {REPORT}; {REPORT.stat().st_size:,} bytes; '
      f'{len(REPORT.read_text(encoding="utf-8").splitlines())} lines.')
print('Report SHA-256:', hashlib.sha256(REPORT.read_bytes()).hexdigest())
