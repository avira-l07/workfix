"""Verify the packaged current UI and preserved source snapshots."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent
apk = out / 'iTantra-1.7-connected-ui.apk'
normal_apk = root / 'app/build/outputs/apk/debug/app-debug.apk'

def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

metadata = json.loads((normal_apk.parent / 'output-metadata.json').read_text())
version = metadata['elements'][0]
assert metadata['applicationId'] == 'com.example.itantra'
assert (version['versionCode'], version['versionName']) == (8, '1.7-connected-ui')
assert digest(apk) == digest(normal_apk)
assert 'BUILD SUCCESSFUL' in (out / 'connectivity-verification.log').read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=467, failures=0, errors=0, skipped=0), counts
original = json.loads((root / 'old ui/snapshot-2026-10-06.json').read_text())
assert len(original) == 21
assert all(digest(root / row['backup']) == row['sha256'] for row in original)
before = root / 'old ui/before-full-integration-2026-10-06'
current = json.loads((before / 'snapshot.json').read_text())
assert len(current) == 25
assert all(digest(before / row['Path']) == row['Sha256'] for row in current)
signing = (out / 'connectivity-signing-verification.log').read_text()
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signing
certificate = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', signing).group(1)
previous = json.loads((root / 'artifacts/adaptive-ui-2026-10-06/verification.json').read_text())
assert certificate == previous['signing_certificate_sha256']
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    for label in ('Speak & translate', 'Find your people.', 'Your conversations.',
                  'Keep the important words.', 'Make it yours.', 'Your color story',
                  'A world of words.', 'Everything, in view.', 'A second chance.'):
        assert label.encode() in dex, label
    assert b'ITantraAppHeaderKt' in dex and b'TransceiverHubScreenKt' in dex
    assert b'AdaptiveHubScreenKt' not in dex
    for label in ('Tap to retry', 'RUNNING ENGLISH BENCHMARK',
                  'Disconnect Wi-Fi Direct before discovering Bluetooth devices',
                  'Disconnect Wi-Fi Direct before starting a Bluetooth connection.'):
        assert label.encode() in dex, label

# Keep the previously provided test kit pointing to the newest APK.
kit = root / 'artifacts/evaluation-2026-10-06/iTantra-evaluation-test-pack.zip'
with zipfile.ZipFile(kit) as archive:
    entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
guides = [entry.filename for entry, _ in entries if entry.filename.endswith('README.md')]
assert len(guides) == 1
temporary = kit.with_suffix('.updated.zip')
with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for entry, data in entries:
        archive.writestr(entry, (kit.parent / 'README.md').read_bytes() if entry.filename == guides[0] else data)
temporary.replace(kit)
with zipfile.ZipFile(kit) as archive:
    assert archive.testzip() is None
evaluation_path = kit.parent / 'verification.json'
evaluation = json.loads(evaluation_path.read_text())
evaluation.update(zip_sha256=digest(kit), phone_test_apk=str(apk))
evaluation_path.write_text(json.dumps(evaluation, indent=2) + '\n')

report = dict(app_version=version['versionName'], version_code=version['versionCode'],
    application_id=metadata['applicationId'], apk=str(apk), apk_bytes=apk.stat().st_size,
    apk_sha256=digest(apk), conventional_apk_matches=True, apk_crc_check='PASS',
    apk_v2_signature='PASS', signing_certificate_sha256=certificate,
    signing_certificate_matches_previous=True, current_ui_labels_present_in_dex=True,
    canonical_hub_and_shared_header_in_dex=True, host_tests=counts,
    original_ui_files_preserved=len(original), preceding_current_ui_files_preserved=len(current),
    backup_hash_mismatches=0, native_phone_rendering='Not verified',
    device_install='Not retried; previous USB installation was refused by the test phone',
    other_phone_install='Install the new APK as an update')
(out / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
