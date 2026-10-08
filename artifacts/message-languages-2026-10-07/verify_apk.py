"""Verify saved host tests and APK evidence; phone UI/GPS/audio remain untested."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
apk = root / "iTantra-1.12-message-languages.apk"
metadata = json.loads((root / "output-metadata.json").read_text())
version = metadata["elements"][0]
assert metadata["applicationId"] == "com.example.itantra"
assert (version["versionCode"], version["versionName"]) == (13, "1.12-message-languages")
assert "BUILD SUCCESSFUL" in (root / "build-verification.log").read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / "test-results").glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=513, failures=0, errors=0, skipped=0), counts
suite = ET.parse(root / "test-results/TEST-com.itantra.regression.MessageLanguagesTest.xml").getroot()
assert int(suite.attrib["tests"]) == 8 and int(suite.attrib["failures"]) == 0
signing = (root / "signature-verification.log").read_text()
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signing
certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signing).group(1)
previous = json.loads((root.parent / "connectivity-audit-2026-10-07/verification.json").read_text())
assert certificate == previous["signing_certificate_sha256"]
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
    for marker in (b"MessageActionsButtonKt", b"translateMessage", b"replayMessageTranslation",
                   b"stopMessageSpeech", b"SocketState", b"localSasConfirmation", b"operatorNameInput"):
        assert marker in dex, marker
with apk.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
report = dict(
    app_version=version["versionName"], version_code=version["versionCode"],
    application_id=metadata["applicationId"], apk=str(apk), apk_bytes=apk.stat().st_size,
    apk_sha256=digest, host_tests=counts, new_message_language_tests=8,
    apk_crc="PASS", apk_v2_signature="PASS", signing_certificate_sha256=certificate,
    signing_matches_previous=True, previous_connectivity_and_input_fixes_included=True,
    phone_ui_gps_and_audio="Not run: no attached Android phones",
    translation_quality="Not measured; uses the existing neural translation engine",
)
(root / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
