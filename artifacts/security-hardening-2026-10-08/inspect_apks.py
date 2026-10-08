"""Inspect actual release/debug DEX and manifest; retain aggregate results, never tokens."""
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
BUILD = ROOT / "artifacts/functions-audit-2026-10-08/build/app"
ANDROID = "{http://schemas.android.com/apk/res/android}"

def dex_metadata(data):
    # DEX ID tables follow https://source.android.com/docs/core/runtime/dex-format.
    # Only ASCII descriptors/log markers are compared; MUTF-8 Unicode strings are
    # decoded with replacement and are never used as source code or instructions.
    assert data[:4] == b"dex\n" and data[4:7] in (b"035", b"037", b"038", b"039", b"040")
    assert struct.unpack_from("<I", data, 40)[0] == 0x12345678
    string_count, string_offset, type_count, type_offset = struct.unpack_from("<4I", data, 56)
    method_count, method_offset, class_count, class_offset = struct.unpack_from("<4I", data, 88)
    strings = []
    for index in range(string_count):
        position = struct.unpack_from("<I", data, string_offset + index * 4)[0]
        while data[position] & 0x80:
            position += 1
        position += 1  # Skip the UTF-16-length ULEB128.
        end = data.index(b"\0", position)
        strings.append(data[position:end].decode("utf-8", errors="replace"))
    types = [strings[struct.unpack_from("<I", data, type_offset + index * 4)[0]] for index in range(type_count)]
    methods = []
    for index in range(method_count):
        class_index, _, name_index = struct.unpack_from("<HHI", data, method_offset + index * 8)
        methods.append((types[class_index], strings[name_index]))
    classes = {types[struct.unpack_from("<I", data, class_offset + index * 32)[0]] for index in range(class_count)}
    return strings, classes, methods

def inspect(variant, apk):
    manifest_path = BUILD / f"intermediates/merged_manifests/{variant}/process{variant.title()}Manifest/AndroidManifest.xml"
    root = ET.parse(manifest_path).getroot()
    application = root.find("application")
    activities = [child.attrib[ANDROID + "name"] for child in application
                  if child.tag == "activity" and child.attrib.get(ANDROID + "exported") == "true"]
    assert application.attrib[ANDROID + "allowBackup"] == "false"
    log_methods = {"v", "d", "i", "w", "e", "wtf", "println"}
    references = 0
    token_strings = []
    known_sdk_log_markers = ["refresh_token: ", "auth token: ", "refreshed auth token: "]
    with zipfile.ZipFile(apk) as archive:
        native = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                  if name.startswith("lib/") and name.endswith(".so")}
        classes = set()
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            string_values, class_names, methods = dex_metadata(archive.read(name))
            classes.update(class_names)
            strings = set(string_values)
            token_strings.extend(marker for marker in known_sdk_log_markers if marker in strings)
            references += sum(owner == "Landroid/util/Log;" and method in log_methods for owner, method in methods)
        sherpa = [cls for cls in classes if cls.startswith("Lcom/k2fsa/sherpa/onnx/")]
    return {
        "path": str(apk), "sha256": hashlib.file_digest(apk.open("rb"), "sha256").hexdigest(),
        "bytes": apk.stat().st_size, "debuggable": application.attrib.get(ANDROID + "debuggable", "false") == "true",
        "allow_backup": False, "exported_activities": activities,
        "logging_method_reference_count": references, "sensitive_sdk_logging_markers": sorted(set(token_strings)),
        "sherpa_jni_class_count": len(sherpa), "native_library_sha256": native,
        "test_preview_classes_absent": "Landroidx/compose/ui/tooling/PreviewActivity;" not in classes,
    }

def main():
    debug = inspect("debug", BUILD / "outputs/apk/debug/app-debug.apk")
    release = inspect("release", BUILD / "outputs/apk/release/app-release-unsigned.apk")
    phone = inspect("release", OUT / "iTantra-1.15-security-test.apk")
    distributed = OUT / "iTantra-1.15-security-release-unsigned.apk"
    assert hashlib.file_digest(distributed.open("rb"), "sha256").hexdigest() == release["sha256"]
    # Signing may add signature entries/blocks, but must not change any app content.
    with zipfile.ZipFile(distributed) as unsigned, zipfile.ZipFile(phone["path"]) as signed:
        content = [name for name in unsigned.namelist() if not name.startswith("META-INF/")]
        assert set(content) == {name for name in signed.namelist() if not name.startswith("META-INF/")}
        assert all(unsigned.read(name) == signed.read(name) for name in content)
    phone["content_matches_verified_release"] = True
    cert = (OUT / "phone-test-signature.txt").read_text(encoding="utf-8-sig")
    cert_match = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", cert)
    assert cert_match and cert_match.group(1) == "765479824648e2c71b8fcd6b5c5a837427da652318f08ed954a584829a148f95"
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in cert
    phone["signing_certificate_sha256"] = cert_match.group(1)
    phone["signing_certificate_purpose"] = "Existing development certificate; phone testing only"
    old = ROOT / "artifacts/functions-audit-2026-10-08/iTantra-1.14-functions-audit.apk"
    with zipfile.ZipFile(old) as archive:
        old_native = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                      if name.startswith("lib/") and name.endswith(".so")}
    release["native_matches_previous_apk"] = release["native_library_sha256"] == old_native
    assert debug["logging_method_reference_count"] > 0 and debug["debuggable"]
    assert not release["debuggable"] and release["logging_method_reference_count"] == 0
    assert not release["sensitive_sdk_logging_markers"] and release["test_preview_classes_absent"]
    assert release["exported_activities"] == ["com.example.itantra.MainActivity"]
    assert release["sherpa_jni_class_count"] > 20 and release["native_matches_previous_apk"]
    tests = [ET.parse(path).getroot() for path in (BUILD / "test-results/testDebugUnitTest").glob("TEST-*.xml")]
    summary = {key: sum(int(test.attrib[key]) for test in tests) for key in ("tests", "failures", "errors", "skipped")}
    assert summary["tests"] >= 570 and summary["failures"] == summary["errors"] == summary["skipped"] == 0
    (OUT / "binary-verification.json").write_text(json.dumps({"debug": debug, "release": release, "phone_test": phone, "unit_tests": summary}, indent=2))
    print(json.dumps({"unit_tests": summary, "release_debuggable": release["debuggable"],
                      "release_logging_method_references": release["logging_method_reference_count"], "jni_classes": release["sherpa_jni_class_count"],
                      "native_libraries_unchanged": release["native_matches_previous_apk"]}))

if __name__ == "__main__":
    main()
