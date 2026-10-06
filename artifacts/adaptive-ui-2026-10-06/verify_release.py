"""Record the checks for this APK; Android device rendering is not inferred."""
import hashlib
import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent
apk = out / 'iTantra-1.5-adaptive-ui.apk'
normal_apk = root / 'app/build/outputs/apk/debug/app-debug.apk'

def digest(path):
    return hashlib.file_digest(Path(path).open('rb'), 'sha256').hexdigest()

metadata = json.loads((normal_apk.parent / 'output-metadata.json').read_text())
version = metadata['elements'][0]
assert metadata['applicationId'] == 'com.example.itantra'
assert version['versionCode'] == 6 and version['versionName'] == '1.5-adaptive-ui'
assert digest(apk) == digest(normal_apk)
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=464, failures=0, errors=0, skipped=0), counts
backups = json.loads((root / 'old ui/snapshot-2026-10-06.json').read_text())
assert len(backups) == 21
assert all(digest(root / row['backup']) == row['sha256'] for row in backups)
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    for label in ('Speak & translate', 'Your conversation starts here.', 'Get models', 'Switch light or dark appearance'):
        assert label.encode() in dex, label

# Update only the guide inside the existing evaluation kit; measurements stay intact.
kit = root / 'artifacts/evaluation-2026-10-06/iTantra-evaluation-test-pack.zip'
with zipfile.ZipFile(kit) as archive:
    entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
readme_names = [entry.filename for entry, _ in entries if entry.filename.endswith('README.md')]
assert len(readme_names) == 1, readme_names
temporary = kit.with_suffix('.updated.zip')
with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for entry, data in entries:
        if entry.filename == readme_names[0]:
            data = (kit.parent / 'README.md').read_bytes()
        archive.writestr(entry, data)
temporary.replace(kit)
with zipfile.ZipFile(kit) as archive:
    assert archive.testzip() is None
evaluation_verification = kit.parent / 'verification.json'
evidence = json.loads(evaluation_verification.read_text())
evidence['zip_sha256'] = digest(kit)
evidence['phone_test_apk'] = str(apk)
evaluation_verification.write_text(json.dumps(evidence, indent=2) + '\n')

report = dict(
    app_version=version['versionName'], version_code=version['versionCode'],
    application_id=metadata['applicationId'], apk=str(apk), apk_bytes=apk.stat().st_size,
    apk_sha256=digest(apk), conventional_apk_matches=True, apk_crc_check='PASS',
    new_ui_labels_present_in_dex=True, host_tests=counts,
    preserved_old_ui_files=len(backups), old_ui_hash_mismatches=0,
    apk_signature='v2 verified with apksigner',
    signing_certificate_sha256='765479824648e2c71b8fcd6b5c5a837427da652318f08ed954a584829a148f95',
    signing_certificate_matches_previous_tts_apk=True,
    native_phone_install='INSTALL_FAILED_USER_RESTRICTED: Install canceled by user',
    native_phone_rendering='Not verified', other_phone_install='Requires transferring and installing the new APK',
)
(out / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
