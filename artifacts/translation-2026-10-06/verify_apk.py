"""Verify the routing-only APK; unit tests are not translation-quality metrics."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
apk = root / 'iTantra-1.8-translation-routing.apk'
metadata = json.loads((root / 'build/app/outputs/apk/debug/output-metadata.json').read_text())
version = metadata['elements'][0]
assert metadata['applicationId'] == 'com.example.itantra'
assert (version['versionCode'], version['versionName']) == (9, '1.8-translation-routing')
assert 'BUILD SUCCESSFUL' in (root / 'build-verification.log').read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / 'build/app/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=471, failures=0, errors=0, skipped=0), counts
signing = (root / 'signature-verification.log').read_text()
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signing
certificate = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', signing).group(1)
previous = json.loads((root.parent / 'current-ui-2026-10-06/verification.json').read_text())
assert certificate == previous['signing_certificate_sha256']
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    assert b'messageLanguageLabel' in dex
    assert b'translateEmergencyPhrase' not in dex
    assert b'SavedSpeechButton' in dex
    assert b'MlKitOfflineTranslationEngine' in dex
    assert not any(name.endswith('libitantra_mt_jni.so') for name in archive.namelist())
with apk.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
report = dict(app_version=version['versionName'], version_code=version['versionCode'],
              apk=str(apk), apk_bytes=apk.stat().st_size, apk_sha256=digest,
              host_tests=counts, apk_crc='PASS', apk_v2_signature='PASS',
              signing_certificate_sha256=certificate, signing_matches_previous=True,
              ordinary_phrasebook_fallback_removed=True,
              active_engine='ML Kit on-device translation; unchanged model weights',
              replacement_candidates_promoted=0, translation_quality_errors='Unresolved',
              phone_install='Not run: no connected phone', phone_quality_evaluation='Not run')
(root / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
