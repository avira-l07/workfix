"""Check the input-fix APK and host regression results; no phone checks are implied."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
apk = root / "iTantra-1.9-input-fix.apk"
metadata = json.loads((root / "build/app/outputs/apk/debug/output-metadata.json").read_text())
version = metadata["elements"][0]
assert metadata["applicationId"] == "com.example.itantra"
assert (version["versionCode"], version["versionName"]) == (10, "1.9-input-fix")
assert "BUILD SUCCESSFUL" in (root / "build-verification.log").read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / "build/app/test-results/testDebugUnitTest").glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=473, failures=0, errors=0, skipped=0), counts
regression = ET.parse(root / "build/app/test-results/testDebugUnitTest/TEST-com.example.itantra.ui.screens.settings.OperatorNameInputTest.xml").getroot()
assert regression.attrib["tests"] == "2" and regression.attrib["failures"] == "0"
signing = (root / "signature-verification.log").read_text()
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signing
certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signing).group(1)
previous = json.loads((root.parent / "translation-2026-10-06/verification.json").read_text())
assert certificate == previous["signing_certificate_sha256"]
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
    assert b"operatorNameInput" in dex
with apk.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
report = dict(app_version=version["versionName"], version_code=version["versionCode"],
              apk=str(apk), apk_bytes=apk.stat().st_size, apk_sha256=digest,
              host_tests=counts, operator_input_regressions="2 passed",
              apk_crc="PASS", apk_v2_signature="PASS",
              signing_certificate_sha256=certificate, signing_matches_previous=True,
              phone_keyboard_check="Not run: no connected phone")
(root / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
