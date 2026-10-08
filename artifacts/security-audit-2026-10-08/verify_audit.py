"""Verify saved inputs, scan outputs, and previous test evidence."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

out = Path(__file__).resolve().parent
root = out.parents[1]
def read(name):
    return json.loads((out / name).read_text(encoding="utf-8-sig"))
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

source = read("source-input-manifest.json")
changed = [row["path"] for row in source
           if not (root / row["path"]).is_file()
           or digest(root / row["path"]) != row["sha256"]]
tests = read("existing-security-test-evidence.json")
test_changes = [row["class"] for row in tests
                if digest(Path(row["evidence"])) != row["sha256"]]
apk = root / "artifacts/functions-audit-2026-10-08/iTantra-1.14-functions-audit.apk"
report = read("mobsf-report.json")
assert not changed, changed
assert not test_changes, test_changes
assert digest(apk) == report["sha256"]
assert report == read("mobsf-scan.json")
assert len(read("semgrep.json")["results"]) == 0
assert not read("semgrep.json")["errors"]
assert len(read("semgrep-apk-java.json")["results"]) == 4
assert read("mobsf-local-policy.json")["external_network_blocked"]
assert read("mobsf-local-policy.json")["server_started"] is False
assert all(row["locations"] for row in read("mobsf-secret-remaining-locations.json"))
probe = ET.parse(out / "probe-results.xml").getroot()
assert probe.get("tests") == "3"
assert all(probe.get(key) == "0" for key in ("failures", "errors", "skipped"))
probe_output = probe.findtext("system-out", "")
assert "pending=512 payloadBytes=4194304" in probe_output
assert "UNVERIFIED_CONTROL: 64 plaintext messages rejected; queue=0" in probe_output
assert "type=ACK resultingState=DELIVERED" in probe_output
assert "type=HUMAN_ACK resultingState=ACKNOWLEDGED" in probe_output
links = []
for name in ["SECURITY-AUDIT.md", "SECURITY-FAULTS.md"]:
    links += re.findall(r"\]\(<([CD]:/[^>]+)>\)", (out / name).read_text(encoding="utf-8"))
assert all(Path(re.sub(r":\d+$", "", link)).exists() for link in links)

classification = read("mobsf-secret-remaining-locations.json")
labels = {
    "7f44193a6edea087f5c0811306efae871c412b46ebee9a179fb3acf0cb5144a3": "WebSocket protocol ACCEPT_MAGIC constant",
    "5bce903c016e0d95bb96e3e488c21c1664c5daad58acb2e6e4ba3a219131fbf0": "Generated Room schema identity hash",
    "afc27cd3388848286fc18f8ac0b7466b1b4d7d8076822ef871d8a169696ffd7b": "Generated Room legacy schema identity hash",
    "1b211e77dabbd5061d8e93d0759d428adec83e51d60f068ed09712d2a1af9429": "Public ONNX conversion metadata encoded as hexadecimal",
    "8f8d0fbe800c1e740b6dd385409d2b27e08aafd2049bdb5b81df0fe133c4c4d9": "R8 synthesized-class version hash",
    "ace550ea2c4cad0c95c126045f9e62e962d2b78780e7572e956f6aadf181696b": "Bundled ML Kit Firebase installation configuration",
    "3697e55a04399e71b062bfcb4e34a35008a452ce3e68c39edd60a22783510300": "Bundled ML Kit encoded Firebase API configuration fragment",
    "04864a2f5ef722592ce401590aae3e1c45fead5365263873df66275ff7a60379": "Bundled ML Kit encoded Firebase API configuration fragment",
}
for row in classification:
    row["review"] = labels[row["sha256"]]
(out / "mobsf-secret-remaining-review.json").write_text(
    json.dumps(classification, indent=2, ensure_ascii=False), encoding="utf-8")

result = {
    "date": "2026-10-08",
    "source_inputs_verified": len(source), "source_input_changes": changed,
    "apk_sha256": digest(apk), "apk_size_bytes": apk.stat().st_size,
    "existing_test_suites_verified": len(tests),
    "existing_security_test_cases": sum(row["tests"] for row in tests),
    "tests_run_in_this_security_task": True,
    "earlier_full_unit_suite_rerun": False,
    "follow_up_probes": {"tests": 3, "failures": 0, "errors": 0, "skipped": 0,
        "purpose": "Reproduce existing faults, not demonstrate fixes",
        "wrong_peer_ack_and_human_ack_reproduced": True,
        "unlimited_pending_queue_reproduced": True,
        "unverified_text_rejection_control_passed": True},
    "report_equals_complete_scan_api_response": True,
    "semgrep_source_findings": 0, "semgrep_apk_java_findings": 4,
    "remaining_non_native_secret_candidates_located_and_reviewed": len(classification),
    "external_network_blocked_during_mobsf_analysis": True,
    "dynamic_analysis_run": False,
    "report_local_links_verified": len(links),
    "artifact_sha256": {name: digest(out / name) for name in [
        "semgrep.json", "semgrep.sarif", "semgrep-apk-java.json", "mobsf-report.json",
        "mobsf-scan.json", "mobsf-scan-logs.json", "mobsf-secret-remaining-review.json",
        "SECURITY-AUDIT.md", "SECURITY-FAULTS.md", "probe-results.xml", "probe-run.log",
        "probes/com/itantra/regression/SecurityFaultProbe.kt", "probe.init.gradle",
    ]},
}
(out / "verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
