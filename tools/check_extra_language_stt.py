"""Fetch a small fixed FLEURS sample and benchmark installed desktop Whisper models.

This is a diagnostic for model selection, not a phone or conversational accuracy test.
Run: python tools/check_extra_language_stt.py fetch --languages ta te
     python tools/check_extra_language_stt.py benchmark --languages ta te
"""
import argparse
import hashlib
import json
from pathlib import Path

import validate_stt


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tools/stt_models/extra-language-test"
REPORT = ROOT / "tools/stt_results"
CONFIGS = {"ta": "ta_in", "te": "te_in", "gu": "gu_in", "kn": "kn_in", "mr": "mr_in", "bn": "bn_in"}


def fetch(languages: list[str], count: int, split: str) -> None:
    import requests

    CACHE.mkdir(parents=True, exist_ok=True)
    manifest = []
    for language in languages:
        config = CONFIGS[language]
        response = requests.get(
            "https://datasets-server.huggingface.co/first-rows",
            params={"dataset": "google/fleurs", "config": config, "split": split},
            timeout=60,
        )
        response.raise_for_status()
        rows = response.json()["rows"][:count]
        if len(rows) < count:
            raise RuntimeError(f"Only {len(rows)} rows available for {config}")
        for item in rows:
            row = item["row"]
            path = CACHE / f"fleurs-{config}-{item['row_idx']}.wav"
            if not path.exists():
                audio = requests.get(row["audio"][0]["src"], timeout=60)
                audio.raise_for_status()
                path.write_bytes(audio.content)
            with path.open("rb") as source:
                sha256 = hashlib.file_digest(source, "sha256").hexdigest()
            manifest.append({
                "language": language,
                "row": item["row_idx"],
                "id": row["id"],
                "file": str(path.relative_to(ROOT)),
                "reference": row["transcription"],
                "sha256": sha256,
                "source": "https://huggingface.co/datasets/google/fleurs",
                "config": config,
                "split": split,
                "license": "CC-BY-4.0",
            })
        print(f"Fetched {config}: {len(rows)} {split} recordings", flush=True)
    (CACHE / f"manifest-{'-'.join(languages)}.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def benchmark(languages: list[str], fine_only: bool = False) -> None:
    manifest = CACHE / f"manifest-{'-'.join(languages)}.json"
    if not manifest.exists():
        raise FileNotFoundError("Run fetch first")
    tiny = ROOT / "models/bundled/shared/stt"
    models = [("tiny", tiny, "tiny-")]
    small = ROOT / "app/build/whisper-small"
    if (small / "small-encoder.int8.onnx").exists():
        models.append(("small", small, "small-"))
    if not fine_only:
        for model, directory, prefix in models:
            validate_stt.benchmark(
                model,
                label=f"extra-{model}-{'-'.join(languages)}",
                manifest_path=manifest,
                model_dir=directory,
                prefix=prefix,
                languages=languages,
            )
    fine_tunes = {"ta": "whisper-small-ta", "te": "whisper-tiny-te"}
    for lang in languages:
        folder = fine_tunes.get(lang)
        if folder:
            directory = ROOT / "tools/stt_models/extra-candidates" / folder
            if (directory / "encoder.int8.onnx").exists():
                validate_stt.benchmark(
                    "small" if lang == "ta" else "tiny",
                    label=f"extra-finetuned-{lang}",
                    manifest_path=manifest,
                    model_dir=directory,
                    prefix="",
                    languages=[lang],
                )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["fetch", "benchmark"])
    parser.add_argument("--languages", nargs="+", choices=CONFIGS, default=["ta", "te"])
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument("--split", choices=["validation", "test"], default="validation")
    parser.add_argument("--fine-only", action="store_true")
    args = parser.parse_args()
    if args.action == "fetch":
        fetch(args.languages, args.count, args.split)
    else:
        benchmark(args.languages, args.fine_only)
