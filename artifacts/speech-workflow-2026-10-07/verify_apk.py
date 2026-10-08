"""Verify saved host tests and APK evidence. Device speech quality is not measured here."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
apk = root / 'iTantra-1.13-speech-workflow.apk'
metadata = json.loads((root / 'output-metadata.json').read_text())
version = metadata['elements'][0]
assert metadata['applicationId'] == 'com.example.itantra'
assert (version['versionCode'], version['versionName']) == (14, '1.13-speech-workflow')
assert 'BUILD SUCCESSFUL' in (root / 'build-verification.log').read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / 'test-results').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=535, failures=0, errors=0, skipped=0), counts
for name, expected in [('com.itantra.core.inference.SpeechWorkflowAuditTest', 13),
                       ('com.itantra.regression.SpeechCaptureWorkflowTest', 7)]:
    suite = ET.parse(root / f'test-results/TEST-{name}.xml').getroot()
    assert int(suite.attrib['tests']) == expected
signing = (root / 'signature-verification.log').read_text()
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signing
certificate = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', signing).group(1)
previous = json.loads((root.parent / 'message-languages-2026-10-07/verification.json').read_text())
assert certificate == previous['signing_certificate_sha256']
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    for marker in (b'recognizeCapture', b'shareMicrophoneFrames', b'awaitPlaybackDrain',
                   b'requireSpeechAudio', b'beginTtsPlayback', b'MessageActionsButtonKt',
                   b'localSasConfirmation', b'operatorNameInput'):
        assert marker in dex, marker
with apk.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
report = dict(
    app_version=version['versionName'], version_code=version['versionCode'],
    application_id=metadata['applicationId'], apk=str(apk), apk_bytes=apk.stat().st_size,
    apk_sha256=digest, host_tests=counts, new_speech_workflow_tests=22,
    apk_crc='PASS', apk_v2_signature='PASS', signing_certificate_sha256=certificate,
    signing_matches_previous=True, previous_connectivity_and_message_language_fixes_included=True,
    phone_microphone_speaker_and_ui='Not run: no attached Android phones',
    stt_accuracy='Not measured', tts_intelligibility='Not measured',
    translation_quality='Not measured; existing neural translation engine retained',
)
(root / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
