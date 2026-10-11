"""Probe all 72 Indic-Indic routes of the existing pinned candidate, offline.

Diagnostic inputs only, no human-approved references or accuracy percentages.
This does not install/promote the candidate into the Android application.
"""
import csv
import hashlib
import importlib
import importlib.util
import json
from pathlib import Path
import sys
import time
import types

ROOT = Path(__file__).resolve().parents[1]
CANDIDATE = ROOT / "artifacts/translation-2026-10-06"
OUT = ROOT / "artifacts/model-conversion-audit-2026-10-11"
TAGS = {"hi": "hin_Deva", "bn": "ben_Beng", "gu": "guj_Gujr", "mr": "mar_Deva",
        "kn": "kan_Knda", "ml": "mal_Mlym", "ta": "tam_Taml", "te": "tel_Telu", "or": "ory_Orya"}


def main():
    sys.path.insert(0, str(CANDIDATE / "python"))
    # The existing processor is loaded without its unused training collator.
    package = types.ModuleType("IndicTransToolkit")
    package.__path__ = [str(CANDIDATE / "python/IndicTransToolkit")]
    sys.modules["IndicTransToolkit"] = package
    package.IndicProcessor = importlib.import_module("IndicTransToolkit.processor").IndicProcessor
    reference = importlib.util.spec_from_file_location("direct_reference", CANDIDATE / "direct-translate-reference.py")
    module = importlib.util.module_from_spec(reference)
    reference.loader.exec_module(module)
    verified = json.loads((CANDIDATE / "direct-model-verification.json").read_text(encoding="utf-8"))
    for item in verified["files"]:
        with (CANDIDATE / "direct-int8" / item["name"]).open("rb") as stream:
            assert hashlib.file_digest(stream, "sha256").hexdigest() == item["sha256"], item["name"]
    model = module.IndicTransONNX(CANDIDATE / "direct-int8")
    # Reuse the existing voice audit's native field sentences, never expected MT answers.
    texts = {lang: json.loads((OUT / f"voice-{lang}.json").read_text(encoding="utf-8"))["cases"][0]["text"]
             for lang in TAGS}
    rows = []
    for source, tag in TAGS.items():
        for target, target_tag in TAGS.items():
            if source == target:
                continue
            text = texts[source] + " 9876543210 Zubin"
            started = time.perf_counter()
            try:
                translated = model.translate(text, tag, target_tag)
                row = {"source": source, "target": target, "input": text, "output": translated,
                       "phone_digits_preserved": "9876543210" in translated,
                       "name_review": "REQUIRED; arbitrary name may be transliterated",
                       "latency_seconds": round(time.perf_counter() - started, 3)}
            except Exception as error:
                row = {"source": source, "target": target, "input": text, "error": str(error)}
            rows.append(row)
            (OUT / "direct-candidate-audit.json").write_text(json.dumps({
                "date": "2026-10-11", "model": verified,
                "scope": "Diagnostic desktop probes; no approved human references, no accuracy score, not integrated in APK",
                "results": rows}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{source}: {sum(r['source'] == source for r in rows)} routes probed", flush=True)
    with (OUT / "direct-candidate-review.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["source", "target", "input", "output", "review_status", "reviewer", "correction_or_notes"])
        for row in rows:
            writer.writerow([row["source"], row["target"], row["input"], row.get("output", row.get("error")), "Pending", "", ""])


if __name__ == "__main__":
    main()
