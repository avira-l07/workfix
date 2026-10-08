"""Locate remaining scanner candidates without disclosing their values."""
import hashlib
import json
from pathlib import Path
import re

out = Path(__file__).resolve().parent
report = json.loads((out / "mobsf-report.json").read_text(encoding="utf-8"))
provenance = json.loads((out / "mobsf-secret-provenance.json").read_text(encoding="utf-8"))
remaining = {row["sha256"]: row for row in provenance
             if not row["first_party"] and not row["native_libraries"]}
values = {value: hashlib.sha256(value.encode()).hexdigest()
          for value in report["secrets"]
          if hashlib.sha256(value.encode()).hexdigest() in remaining}
pattern = re.compile("|".join(re.escape(value) for value in values))
upload = json.loads((out / "mobsf-upload.json").read_text(encoding="utf-8"))
root = Path.home() / "AppData/Local/iTantraTools/MobSF/data/uploads" / upload["hash"]
hits = {digest: [] for digest in remaining}
for folder in [root / "java_source", root / "apktool_out"]:
    if not folder.exists():
        continue
    for file in folder.rglob("*"):
        if file.suffix not in {".java", ".xml", ".json", ".txt", ".yaml", ".yml", ".properties"}:
            continue
        if file.stat().st_size > 5_000_000:
            continue
        text = file.read_text(encoding="utf-8", errors="replace")
        if not pattern.search(text):
            continue
        for line_number, line in enumerate(text.splitlines(), 1):
            found = {match.group() for match in pattern.finditer(line)}
            for value in found:
                hits[values[value]].append({
                    "file": file.relative_to(root).as_posix(), "line": line_number,
                    "context": pattern.sub("[CANDIDATE REDACTED]", line.strip())[:500],
                })
rows = [{**remaining[digest], "locations": hits[digest]} for digest in remaining]
(out / "mobsf-secret-remaining-locations.json").write_text(
    json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps(rows, indent=2, ensure_ascii=False))
