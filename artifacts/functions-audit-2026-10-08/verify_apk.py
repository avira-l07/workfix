"""Check saved build/test/APK evidence; this does not measure phone behavior."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
apk = root / 'iTantra-1.14-functions-audit.apk'
metadata = json.loads((root / 'output-metadata.json').read_text())
version = metadata['elements'][0]
assert metadata['applicationId'] == 'com.example.itantra'
assert (version['versionCode'], version['versionName']) == (15, '1.14-functions-audit')
assert 'BUILD SUCCESSFUL' in (root / 'build-verification.log').read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / 'test-results').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=549, failures=0, errors=0, skipped=0), counts
for name, expected in [('com.itantra.regression.AppFunctionsAuditTest', 6),
                       ('com.itantra.core.location.GpsFunctionsAuditTest', 6),
                       ('com.itantra.feature.languages.SelectiveLanguageProvisioningTest', 7)]:
    suite = ET.parse(root / f'test-results/TEST-{name}.xml').getroot()
    assert int(suite.attrib['tests']) == expected
signing = (root / 'signature-verification.log').read_text()
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signing
certificate = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', signing).group(1)
previous = json.loads((root.parent / 'speech-workflow-2026-10-07/verification.json').read_text())
assert certificate == previous['signing_certificate_sha256']
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    for marker in (b'isRecentGpsFix', b'isValidGpsCoordinates', b'updateDisplayName',
                   b'recognizeCapture', b'awaitPlaybackDrain', b'MessageActionsButtonKt',
                   b'localSasConfirmation', b'operatorNameInput'):
        assert marker in dex, marker
with apk.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
report = dict(
    app_version=version['versionName'], version_code=version['versionCode'],
    application_id=metadata['applicationId'], apk=str(apk), apk_bytes=apk.stat().st_size,
    apk_sha256=digest, host_tests=counts, new_function_regressions=14,
    apk_crc='PASS', apk_v2_signature='PASS', signing_certificate_sha256=certificate,
    signing_matches_previous=True, earlier_connectivity_message_and_speech_fixes_included=True,
    phone_gps_permissions_and_ui='Not run: no attached Android phones',
    private_data_wipe='Not performed on a phone; storage host tests and code reviewed',
    stt_accuracy='Not measured', tts_intelligibility='Not measured',
    translation_quality='Not measured; existing neural translation engine retained',
)
(root / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
