"""Copy five checksum-verified FLEURS recordings into Android self-test assets."""

import hashlib
import json
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/benchmark/five_self_test"
SOURCES = {
    "hi": ROOT / "tools/stt_models/fleurs-hi-test-manifest.json",
    "en": ROOT / "tools/stt_models/fleurs-manifest.json",
    "ta": ROOT / "tools/stt_models/extra-language-test/manifest-ta-te.json",
    "te": ROOT / "tools/stt_models/extra-language-test/manifest-ta-te.json",
    "or": ROOT / "tools/stt_models/odia-validation/manifest-or-20.json",
}


def main() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    manifest = []
    for code, source in SOURCES.items():
        rows = json.loads(source.read_text(encoding="utf-8"))
        item = next(row for row in rows if row["language"] == code)
        original = ROOT / item["file"]
        actual = hashlib.sha256(original.read_bytes()).hexdigest()
        if actual != item["sha256"]:
            raise RuntimeError(f"Source audio changed: {original}")
        target = DEST / f"{code}.wav"
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != actual:
            raise RuntimeError(f"Existing self-test audio changed: {target}")
        if not target.exists():
            shutil.copyfile(original, target)
        manifest.append({
            "language": code,
            "asset": f"benchmark/five_self_test/{code}.wav",
            "reference": item["reference"],
            "sha256": actual,
            "source": item["source"],
            "license": item.get("license", "CC-BY-4.0"),
        })
    (DEST / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Prepared {len(manifest)} recordings in {DEST}")


if __name__ == "__main__":
    main()
