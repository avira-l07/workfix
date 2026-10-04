"""Fail if training/dev speech duplicates any FLEURS validation/test sentence or audio.

Build a protected index from COMPLETE FLEURS validation and test Parquet splits:
  python tools/check_speech_leakage.py protect --language ta \
    --validation PARQUET --test PARQUET --output tools/stt_training/ta-protected.json
Check NeMo JSONL manifests against it:
  python tools/check_speech_leakage.py check --language ta \
    --protected tools/stt_training/ta-protected.json --manifest TRAIN.jsonl DEV.jsonl

Training files are local and ignored by Git. The Gradle check invokes workspace-check
when any training manifests exist, failing closed if their protected index is absent.
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from validate_stt import normalize

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = ("ta", "te", "bn", "gu")
DATA = ROOT / "tools/stt_training"


def text_hash(text):
    normalized = normalize(text)
    if not normalized:
        raise ValueError("Empty normalized transcript")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def file_hash(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def make_index(language, validation, test):
    sys.path.insert(0, str(ROOT / "tools/stt_models/validation-deps"))
    import pyarrow.parquet as pq

    index = {"language": language, "splits": {}, "text_sha256": [],
             "audio_sha256": [], "source_ids": []}
    texts, audios, ids = set(), set(), set()
    for split, path in (("validation", validation), ("test", test)):
        parquet = pq.ParquetFile(path)
        columns = [name for name in ("id", "audio", "transcription")
                   if name in parquet.schema_arrow.names]
        if "transcription" not in columns or "id" not in columns or "audio" not in columns:
            raise ValueError(f"Missing FLEURS columns in {path}")
        count = 0
        for batch in parquet.iter_batches(batch_size=64, columns=columns):
            for row in batch.to_pylist():
                texts.add(text_hash(row["transcription"]))
                audios.add(hashlib.sha256(row["audio"]["bytes"]).hexdigest())
                ids.add(str(row["id"]))
                count += 1
        if count < 100:
            raise ValueError(f"{split} split is incomplete: {count} rows")
        index["splits"][split] = {"rows": count, "parquet_sha256": file_hash(path)}
    index["text_sha256"], index["audio_sha256"], index["source_ids"] = (
        sorted(texts), sorted(audios), sorted(ids))
    return index


def check_rows(index, rows):
    protected_text = set(index["text_sha256"])
    protected_audio = set(index["audio_sha256"])
    protected_ids = set(index["source_ids"])
    errors = []
    for n, row in enumerate(rows, 1):
        try:
            key = text_hash(row["text"])
        except (KeyError, ValueError) as exc:
            errors.append(f"row {n}: invalid transcript: {exc}")
            continue
        if key in protected_text:
            errors.append(f"row {n}: protected FLEURS transcript")
        source_id = str(row.get("source_id", "")).strip()
        if not source_id:
            errors.append(f"row {n}: missing source id")
        elif source_id in protected_ids:
            errors.append(f"row {n}: protected FLEURS source id")
        audio_path = row.get("audio_filepath")
        if not audio_path or not Path(audio_path).is_file():
            errors.append(f"row {n}: missing audio file")
        elif file_hash(audio_path) in protected_audio:
            errors.append(f"row {n}: protected FLEURS audio")
    return errors


def check_manifest(index, paths):
    rows = []
    for path in paths:
        with Path(path).open(encoding="utf-8") as stream:
            rows.extend(json.loads(line) for line in stream if line.strip())
    return len(rows), check_rows(index, rows)


def workspace_check():
    manifest_root = DATA / "manifests"
    if not manifest_root.is_dir():
        print("No local training manifests; leakage check has no training rows")
        return 0
    for language in LANGUAGES:
        paths = sorted(manifest_root.glob(f"{language}-*.jsonl"))
        if not paths:
            continue
        kathbath = any(json.loads(line).get("source") == "ai4bharat/Kathbath"
                       for path in paths for line in path.read_text(encoding="utf-8").splitlines()
                       if line.strip())
        protected = DATA / (f"{language}-combined-protected.json" if kathbath
                            else f"{language}-protected.json")
        if not protected.is_file():
            raise RuntimeError(f"{language}: training manifest exists without required protected index: {protected.name}")
        index = json.loads(protected.read_text(encoding="utf-8"))
        if set(index.get("splits", {})) != {"validation", "test"}:
            raise RuntimeError(f"{language}: protected index must include both full splits")
        if kathbath and not index.get("extra_protection", {}).get("kathbath_noisy_rows"):
            raise RuntimeError(f"{language}: Kathbath train/dev requires valid and noisy test protection")
        count, errors = check_manifest(index, paths)
        print(f"{language}: checked {count} train/dev rows; overlaps/errors={len(errors)}")
        if errors:
            raise RuntimeError("\n".join(errors[:20]))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    protect = sub.add_parser("protect")
    protect.add_argument("--language", choices=LANGUAGES, required=True)
    protect.add_argument("--validation", type=Path, required=True)
    protect.add_argument("--test", type=Path, required=True)
    protect.add_argument("--output", type=Path, required=True)
    check = sub.add_parser("check")
    check.add_argument("--language", choices=LANGUAGES, required=True)
    check.add_argument("--protected", type=Path, required=True)
    check.add_argument("--manifest", type=Path, nargs="+", required=True)
    sub.add_parser("workspace-check")
    args = parser.parse_args()
    if args.action == "protect":
        index = make_index(args.language, args.validation, args.test)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(index, indent=2), encoding="utf-8")
        print(f"{args.language}: protected {index['splits']} with {len(index['text_sha256'])} distinct texts")
    elif args.action == "check":
        index = json.loads(args.protected.read_text(encoding="utf-8"))
        if index.get("language") != args.language or set(index.get("splits", {})) != {"validation", "test"}:
            raise RuntimeError("Wrong language or incomplete protected index")
        count, errors = check_manifest(index, args.manifest)
        print(f"{args.language}: checked {count} rows; overlaps/errors={len(errors)}")
        if errors:
            raise RuntimeError("\n".join(errors[:20]))
    else:
        workspace_check()


if __name__ == "__main__":
    main()
