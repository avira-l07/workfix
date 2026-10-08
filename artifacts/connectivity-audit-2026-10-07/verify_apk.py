"""Verify the APK and saved host test evidence; this does not test phone radios."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
apk = root / "iTantra-1.11-connectivity-audit.apk"
metadata = json.loads((root / "output-metadata.json").read_text())
version = metadata["elements"][0]
assert metadata["applicationId"] == "com.example.itantra"
assert (version["versionCode"], version["versionName"]) == (12, "1.11-connectivity-audit")
assert "BUILD SUCCESSFUL" in (root / "build-verification.log").read_text()
counts = dict(tests=0, failures=0, errors=0, skipped=0)
results = root / "test-results"
for path in results.glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
assert counts == dict(tests=505, failures=0, errors=0, skipped=0), counts
audit_suites = {
    "com.itantra.core.transport.peer.BluetoothSocketLifecycleTest": 6,
    "com.itantra.core.transport.peer.WifiSocketAuditTest": 4,
    "com.itantra.core.transport.peer.WifiManagerLifecycleTest": 3,
    "com.itantra.core.transport.ConnectionReceiptAuditTest": 5,
    "com.itantra.core.transport.ConnectionServiceTypeTest": 2,
    "com.itantra.regression.MessageRoutingAuditTest": 7,
    "com.itantra.regression.InitialHandshakeTimeoutTest": 1,
    "com.itantra.regression.VerificationDeliveryTest": 2,
    "com.itantra.regression.WifiVerificationIntegrationTest": 1,
    "com.itantra.regression.TransportSwitchTest": 2,
}
for name, expected in audit_suites.items():
    suite = ET.parse(results / f"TEST-{name}.xml").getroot()
    assert (int(suite.attrib["tests"]), int(suite.attrib["failures"])) == (expected, 0), name
signing = (root / "signature-verification.log").read_text()
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signing
certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signing).group(1)
previous = json.loads((root.parent / "connection-fix-2026-10-07/verification.json").read_text())
assert certificate == previous["signing_certificate_sha256"]
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
    for marker in (b"SocketState", b"connectionToken", b"startPeerConnection", b"retrySavedLocation",
                   b"localSasConfirmation", b"operatorNameInput"):
        assert marker in dex, marker
with apk.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
report = dict(
    app_version=version["versionName"], version_code=version["versionCode"],
    application_id=metadata["applicationId"], apk=str(apk), apk_bytes=apk.stat().st_size,
    apk_sha256=digest, host_tests=counts, audited_suites=audit_suites,
    new_audit_test_count=28, apk_crc="PASS", apk_v2_signature="PASS",
    signing_certificate_sha256=certificate, signing_matches_previous=True,
    previous_input_and_confirmation_fixes_included=True,
    actual_localhost_tcp_tested=True, bluetooth_production_stream_logic_tested=True,
    wifi_direct_native_group_negotiation="Not run: no attached Android devices",
    bluetooth_native_pairing_and_radio="Not run: no attached Android devices",
    two_phone_ui_and_background_tests="Not run: see CONNECTIVITY-AUDIT.md checklist",
    audit_report=str(root / "CONNECTIVITY-AUDIT.md"),
)
(root / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
