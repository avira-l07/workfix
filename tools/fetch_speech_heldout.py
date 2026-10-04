"""Fetch pinned, public FLEURS test splits and extract 100 distinct held-out clips.

  python tools/fetch_speech_heldout.py --languages ta te bn gu

Downloaded Parquet and WAVs stay under ignored tools/stt_models/. The complete
validation+test transcript/audio index stays under ignored tools/stt_training/.
"""

import argparse
import hashlib
import io
import json
import sys
from pathlib import Path

from benchmark_five_language_stt import DATA, ROOT, SOURCES, digest, source_file
from check_speech_leakage import DATA as TRAINING, make_index, text_hash

DEST = ROOT / "tools/stt_models/five-language-test"
TEST_FILES = {
    "ml": ("ml_in/test/0000.parquet", 899473320,
           "8b609c11b4ac862f6376a4b4887d59a75791fbc5cffc5bcf3431edb56ced5bf5"),
    "ta": ("ta_in/test-00000-of-00001.parquet", 489237869,
           "6a0ca70812792cb075e20ecef7b69aac173af5f1f4ae37d3a42c58e219ea4bb6"),
    "te": ("te_in/test-00000-of-00001.parquet", 331720646,
           "545a999e972e49649814e2f0267da898965a5e896aa215ac5d85898dff357fa1"),
    "bn": ("bn_in/test/0000.parquet", 791045036,
           "7193d9b9c3c28add1e4c5ceac84805dba57d6fcd34772e16a76e26a5309443a7"),
    "gu": ("gu_in/test/0000.parquet", 671840520,
           "2ff07db72023fc565a732ef5dc78c7aa5057c94de56f558febb7cc28188749d4"),
}


def fetch(language):
    import requests
    import soundfile as sf

    sys.path.insert(0, str(ROOT / "tools/stt_models/validation-deps"))
    import pyarrow.parquet as pq

    remote, expected_bytes, expected_sha = TEST_FILES[language]
    revision = SOURCES[language][1]
    DEST.mkdir(parents=True, exist_ok=True)
    parquet = DEST / f"fleurs-{language}-test.parquet"
    if not parquet.is_file() or digest(parquet) != expected_sha:
        partial = parquet.with_suffix(".parquet.part")
        url = f"https://huggingface.co/datasets/google/fleurs/resolve/{revision}/{remote}?download=true"
        for attempt in range(6):
            offset = partial.stat().st_size if partial.exists() else 0
            if offset == expected_bytes:
                break
            try:
                with requests.get(url, headers={"Range": f"bytes={offset}-"} if offset else {},
                                  stream=True, timeout=(30, 180)) as response:
                    response.raise_for_status()
                    append = offset > 0 and response.status_code == 206
                    with partial.open("ab" if append else "wb") as output:
                        for chunk in response.iter_content(1024 * 1024):
                            if chunk:
                                output.write(chunk)
            except requests.RequestException:
                if attempt == 5:
                    raise
                print(f"{language}: download interrupted; resuming from {partial.stat().st_size} bytes", flush=True)
        if partial.stat().st_size != expected_bytes or digest(partial) != expected_sha:
            raise RuntimeError(f"{language}: incomplete or changed publisher test split")
        partial.replace(parquet)
    validation = source_file(language)
    if not validation.is_file() or digest(validation) != SOURCES[language][3]:
        raise RuntimeError(f"{language}: verified full validation Parquet required; run benchmark fetch")
    protected = make_index(language, validation, parquet)
    TRAINING.mkdir(parents=True, exist_ok=True)
    (TRAINING / f"{language}-protected.json").write_text(
        json.dumps(protected, ensure_ascii=False, indent=2), encoding="utf-8")

    validation_texts = set()
    for batch in pq.ParquetFile(validation).iter_batches(batch_size=64, columns=["transcription"]):
        validation_texts.update(text_hash(row["transcription"]) for row in batch.to_pylist())
    seen = set()
    manifest = []
    for batch in pq.ParquetFile(parquet).iter_batches(
            batch_size=32, columns=["id", "audio", "transcription"]):
        for row in batch.to_pylist():
            key = text_hash(row["transcription"])
            if key in seen or key in validation_texts:
                continue
            audio_bytes = row["audio"]["bytes"]
            audio, rate = sf.read(io.BytesIO(audio_bytes), dtype="float32")
            if rate != 16000 or audio.ndim != 1:
                raise RuntimeError(f"{language}: expected 16-kHz mono test audio")
            path = DEST / f"{language}-{len(manifest):03d}.wav"
            expected_audio_sha = hashlib.sha256(audio_bytes).hexdigest()
            if path.exists() and digest(path) != expected_audio_sha:
                raise RuntimeError(f"Changed held-out clip: {path}")
            path.write_bytes(audio_bytes)
            manifest.append({"language": language, "row": len(manifest), "id": row["id"],
                             "file": str(path.relative_to(ROOT)), "reference": row["transcription"],
                             "sha256": expected_audio_sha, "audio_seconds": len(audio) / rate,
                             "source_revision": revision, "parquet_sha256": expected_sha,
                             "split": "test", "license": "CC-BY-4.0"})
            seen.add(key)
            if len(manifest) == 100:
                break
        if len(manifest) == 100:
            break
    if len(manifest) != 100:
        raise RuntimeError(f"{language}: only {len(manifest)} distinct held-out test sentences")
    (DEST / f"manifest-{language}-100.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{language}: 100 distinct test sentences; full split rows={protected['splits']}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--languages", nargs="+", choices=tuple(TEST_FILES),
                        default=["ta", "te", "bn", "gu"])
    args = parser.parse_args()
    for language in args.languages:
        fetch(language)
