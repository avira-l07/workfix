"""Verify the connection update; localhost TCP tests are not Wi-Fi P2P hardware tests."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
apk = root / "iTantra-1.10-connection-fix.apk"
metadata = json.loads((root / "build/app/outputs/apk/debug/output-metadata.json").read_text())
version = metadata["elements"][0]
assert metadata["applicationId"] == "com.example.itantra"
assert (version["versionCode"], version["versionName"]) == (11, "1.10-connection-fix")
assert "BUILD SUCCESSFUL" in (root / "build-verification.log").read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
results = root / "build/app/test-results/testDebugUnitTest"
for path in results.glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=477, failures=0, errors=0, skipped=0), counts
for name, expected in (("VerificationDeliveryTest", 2), ("WifiVerificationIntegrationTest", 1), ("TransportSwitchTest", 2)):
    suite = ET.parse(results / f"TEST-com.itantra.regression.{name}.xml").getroot()
    assert (int(suite.attrib["tests"]), int(suite.attrib["failures"])) == (expected, 0)
signing = (root / "signature-verification.log").read_text()
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signing
certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signing).group(1)
previous = json.loads((root.parent / "input-fix-2026-10-07/verification.json").read_text())
assert certificate == previous["signing_certificate_sha256"]
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
    assert b"localSasConfirmation" in dex and b"operatorNameInput" in dex
with apk.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
report = dict(app_version=version["versionName"], version_code=version["versionCode"],
              apk=str(apk), apk_bytes=apk.stat().st_size, apk_sha256=digest,
              host_tests=counts, apk_crc="PASS", apk_v2_signature="PASS",
              signing_certificate_sha256=certificate, signing_matches_previous=True,
              checks_passed=["Suspended confirmation is not cancelled by local verification",
                  "Failed final confirmation retries until authenticated peer traffic",
                  "Unauthenticated packets cannot stop confirmation retries",
                  "Real localhost TCP, both acceptance orders, peer profiles and bidirectional messages",
                  "Reconnect rejects an old confirmation and old socket error",
                  "Connected transport switch emits a session reset"],
              previous_input_fixes_included=True,
              two_phone_wifi_direct_test="Not run: no connected phones")
(root / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
