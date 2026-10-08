"""Summarize saved scanner evidence without printing candidate secret values."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]
def read(name):
    return json.loads((OUT / name).read_text(encoding="utf-8-sig"))
def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")

source = read("semgrep.json")
compiled = read("semgrep-apk-java.json")
apk = read("mobsf-report.json")
tests = read("existing-security-test-evidence.json")
candidates = apk["secrets"]
assert len(candidates) == len(set(candidates))
known = Counter(value.split(":", 1)[0] for value in candidates if ": " in value)
locations = []
for file in (ROOT / "app/src/main").rglob("*"):
    if file.suffix not in {".kt", ".cpp", ".h", ".xml", ".json"} or file.stat().st_size > 2_000_000:
        continue
    text = file.read_text(encoding="utf-8", errors="replace")
    for candidate in candidates:
        offset = text.find(candidate)
        if offset < 0:
            continue
        line = text.count("\n", 0, offset) + 1
        context = text.splitlines()[line - 1].replace(candidate, "[CANDIDATE REDACTED]")
        locations.append({"sha256": hashlib.sha256(candidate.encode()).hexdigest(),
                          "length": len(candidate), "file": file.relative_to(ROOT).as_posix(),
                          "line": line, "context": context[:350]})
write("mobsf-secret-source-locations.json", locations)

binary_findings = []
for library in apk["binary_analysis"]:
    for key, value in library.items():
        if isinstance(value, dict) and value.get("severity") in {"high", "warning"}:
            binary_findings.append({"library": library["name"], "check": key, **value})
summary = {
    "date": "2026-10-08", "app_version": apk["version_name"], "version_code": apk["version_code"],
    "apk_sha256": apk["sha256"], "tools": {"semgrep": source["version"], "mobsf": apk["version"]},
    "semgrep_source": {"findings": len(source["results"]), "errors": len(source["errors"]),
                       "files": len(source["paths"]["scanned"]), "applicable_rules": 48},
    "semgrep_apk_java": {"findings": len(compiled["results"]), "parse_warnings": len(compiled["errors"]),
                         "files": len(compiled["paths"]["scanned"]), "applicable_rules": 124},
    "mobsf_code": apk["code_analysis"]["summary"],
    "mobsf_manifest": apk["manifest_analysis"]["manifest_summary"],
    "mobsf_certificate": apk["certificate_analysis"]["certificate_summary"],
    "mobsf_native_findings": binary_findings,
    "heuristic_secret_candidates": len(candidates), "known_secret_formats": dict(known),
    "first_party_candidate_locations": len(locations),
    "first_party_candidate_values": len({row["sha256"] for row in locations}),
    "detected_trackers": apk["trackers"]["detected_trackers"],
    "tracker_signature_count": apk["trackers"]["total_trackers"],
    "mobsf_raw_security_score": apk["appsec"]["security_score"],
    "prior_security_test_evidence": {key: sum(suite[key] for suite in tests)
                                     for key in ["tests", "failures", "errors", "skipped"]},
    "dynamic_analysis": "Not run: no attached phone",
}
write("scan-summary.json", summary)
print(json.dumps(summary, ensure_ascii=False, indent=2))
