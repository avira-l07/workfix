"""Verify the audit APK and preserve build/model evidence; no model downloads."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]
APK = OUT / "iTantra-1.16-model-audit-test.apk"
BUILD = ROOT / "artifacts/functions-audit-2026-10-08/build/app"
spec = importlib.util.spec_from_file_location("apk_inspector", ROOT / "artifacts/security-hardening-2026-10-08/inspect_apks.py")
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)
binary = inspector.inspect("release", APK)
assert not binary["debuggable"] and not binary["allow_backup"]
assert binary["logging_method_reference_count"] == 0
assert not binary["sensitive_sdk_logging_markers"]
assert binary["exported_activities"] == ["com.example.itantra.MainActivity"]
assert binary["test_preview_classes_absent"] and binary["sherpa_jni_class_count"] > 20

with zipfile.ZipFile(APK) as signed, zipfile.ZipFile(BUILD / "outputs/apk/release/app-release-unsigned.apk") as unsigned:
    assert signed.testzip() is None
    contents = {name for name in unsigned.namelist() if not name.startswith("META-INF/")}
    assert contents == {name for name in signed.namelist() if not name.startswith("META-INF/")}
    assert all(unsigned.read(name) == signed.read(name) for name in contents)
    dex = b"".join(signed.read(name) for name in contents if re.fullmatch(r"classes\d*\.dex", name))
    markers = ["NUMERIC_CONTENT_REQUIRES_REVIEW", "SOURCE_SCRIPT_MISMATCH", "needs numbers written in words"]
    assert all(marker.encode() in dex for marker in markers)
binary["content_matches_built_release"] = True
binary["new_validation_markers_present"] = markers
with zipfile.ZipFile(ROOT / "artifacts/security-hardening-2026-10-08/iTantra-1.15-security-test.apk") as old:
    old_native = {name: hashlib.sha256(old.read(name)).hexdigest() for name in old.namelist()
                  if name.startswith("lib/") and name.endswith(".so")}
assert binary["native_library_sha256"] == old_native
binary["native_libraries_match_previous_test_apk"] = True

certificate = (OUT / "signature.txt").read_text(encoding="utf-8-sig")
cert_hash = "765479824648e2c71b8fcd6b5c5a837427da652318f08ed954a584829a148f95"
assert f"certificate SHA-256 digest: {cert_hash}" in certificate
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in certificate
assert "Number of signers: 1" in certificate
binary["signing_certificate_sha256"] = cert_hash
binary["signing_purpose"] = "Existing development certificate; phone testing only"
badging = subprocess.check_output([r"C:\Users\avira\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt.exe", "dump", "badging", str(APK)], text=True)
package = badging.splitlines()[0]
assert "name='com.example.itantra'" in package
assert "versionCode='17'" in package and "versionName='1.16-model-audit'" in package
binary["actual_apk_package"] = package

results = [ET.parse(path).getroot() for path in (BUILD / "test-results/testDebugUnitTest").glob("TEST-*.xml")]
tests = {key: sum(int(result.attrib[key]) for result in results) for key in ("tests", "failures", "errors", "skipped")}
assert tests == {"tests": 589, "failures": 0, "errors": 0, "skipped": 0}
assert "BUILD SUCCESSFUL" in (OUT / "verified-build.log").read_text(encoding="utf-8-sig")
voices = json.loads((OUT / "voice-audit.json").read_text(encoding="utf-8-sig"))["voices"]
assert len(voices) == 10 and all(all(v["checksums_verified"].values()) for v in voices)
waveforms = sum(bool(case.get("audio_file")) for voice in voices for case in voice["cases"])
assert waveforms == 53
candidate = json.loads((OUT / "direct-candidate-audit.json").read_text(encoding="utf-8-sig"))
routes = candidate["results"]
assert len(routes) == 72 and all(case.get("phone_digits_preserved") is True for case in routes)
evidence = {"date": "2026-10-11", "apk": binary, "unit_tests": tests,
            "voice_packs_checksums_verified": len(voices), "desktop_waveforms_generated": waveforms,
            "desktop_candidate_routes": len(routes), "phone_validation": "NOT RUN; no attached device",
            "human_linguistic_accuracy_score": None,
            "limitations": "Structural validation and desktop probes do not establish translation meaning or voice intelligibility."}
(OUT / "verification.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
(OUT / "SHA256SUMS.txt").write_text(f"{binary['sha256']}  {APK.name}\n", encoding="utf-8")
print(json.dumps({"apk_sha256": binary["sha256"], "bytes": binary["bytes"], "tests": tests,
                  "voice_packs": len(voices), "waveforms": waveforms, "candidate_routes": len(routes)}))
