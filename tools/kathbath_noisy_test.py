"""Kaggle-only Kathbath noisy test-unknown importer; never use as train/dev.

The two publisher archive URLs were HEAD-checked on 3 Oct 2026. This importer
checks actual tar paths and transcript/audio matches before producing manifests.
It downloads/extracts audio only in /kaggle/working, never into the repository.
"""

import argparse
import hashlib
import json
import math
import os
import shutil
import tarfile
from pathlib import Path

import requests

URLS = {
    "testunk_audio.tar": ("https://objectstore.e2enetworks.net/indic-superb/kathbath/noisy/testunk_audio.tar", 1418004480),
    "transcripts_n2w.tar": ("https://objectstore.e2enetworks.net/indic-superb/kathbath/noisy/transcripts_n2w.tar", 15646720),
}
LANGS = {"ta": "tamil", "te": "telugu"}


def sha256(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def download_archives(root):
    root.mkdir(parents=True, exist_ok=True)
    total = sum(size for _, size in URLS.values())
    if shutil.disk_usage(root).free < total + 3_000_000_000:
        raise RuntimeError("Need archive bytes plus 3 GB working headroom on Kaggle")
    result = {}
    for name, (url, expected_bytes) in URLS.items():
        path = root / name
        if not path.exists() or path.stat().st_size != expected_bytes:
            temporary = path.with_suffix(path.suffix + ".part")
            for attempt in range(6):
                offset = temporary.stat().st_size if temporary.exists() else 0
                if offset == expected_bytes:
                    break
                try:
                    with requests.get(url, headers={"Range": f"bytes={offset}-"} if offset else {},
                                      stream=True, timeout=(30, 180)) as response:
                        response.raise_for_status()
                        append = offset > 0 and response.status_code == 206
                        expected_response = expected_bytes - offset if append else expected_bytes
                        if int(response.headers.get("Content-Length", 0)) != expected_response:
                            raise RuntimeError(f"Publisher archive size changed: {url}")
                        with temporary.open("ab" if append else "wb") as target:
                            for chunk in response.iter_content(1024 * 1024):
                                if chunk:
                                    target.write(chunk)
                except requests.RequestException:
                    if attempt == 5:
                        raise
                    print(f"{name}: interrupted; resuming from {temporary.stat().st_size if temporary.exists() else 0} bytes")
            if temporary.stat().st_size != expected_bytes:
                raise RuntimeError(f"Incomplete archive: {temporary}")
            temporary.replace(path)
        result[name] = {"path": path, "bytes": path.stat().st_size, "sha256": sha256(path), "url": url}
        print(f"{name}: {result[name]['bytes']} bytes, SHA-256 {result[name]['sha256']}")
    return result


def split_member(name):
    parts = Path(name).parts
    for language, folder in LANGS.items():
        if folder in parts:
            pos = parts.index(folder)
            if len(parts) > pos + 2:
                return language, parts[pos + 1], parts[-1]
    return None


def parse_transcript_lines(content):
    records = {}
    for number, line in enumerate(content.decode("utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        pieces = line.strip().split(maxsplit=1)
        if len(pieces) != 2 or not pieces[0].lower().endswith((".m4a", ".wav")):
            raise RuntimeError(f"Unexpected Kathbath transcript format at line {number}")
        name, text = pieces
        if name in records or not text.strip():
            raise RuntimeError(f"Duplicate or empty Kathbath transcript at line {number}")
        records[name] = text
    return records


def transcript_maps(archive):
    found = {}
    with tarfile.open(archive, "r:") as tar:
        for member in tar:
            parsed = split_member(member.name)
            if not parsed or not member.isfile():
                continue
            language, split, filename = parsed
            if split != "test" or filename != "transcription_n2w.txt":
                continue
            if language in found:
                raise RuntimeError(f"Multiple {language} noisy test transcript files")
            found[language] = parse_transcript_lines(tar.extractfile(member).read())
    if set(found) != set(LANGS):
        raise RuntimeError(f"Expected tamil/test and telugu/test transcripts; found {list(found)}")
    return found


def audio_to_wav(encoded, output):
    import numpy as np
    import soundfile as sf
    from scipy.signal import resample_poly
    from torchcodec.decoders import AudioDecoder

    samples = AudioDecoder(str(encoded)).get_all_samples()
    data = samples.data.detach().cpu().numpy()
    rate = int(samples.sample_rate)
    if data.ndim == 2:
        data = data.mean(axis=0)
    if data.ndim != 1 or rate <= 0:
        raise RuntimeError(f"Invalid noisy audio shape/rate: {encoded}")
    if rate != 16000:
        divisor = math.gcd(rate, 16000)
        data = resample_poly(data, 16000 // divisor, rate // divisor)
    sf.write(output, np.asarray(data, dtype=np.float32), 16000, subtype="PCM_16")


def build_manifests(archive, transcripts, output_dir):
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = output_dir / "encoded"
    wav_dir = output_dir / "wav"
    raw_dir.mkdir(exist_ok=True)
    wav_dir.mkdir(exist_ok=True)
    found = {language: {} for language in LANGS}
    inspected = []
    with tarfile.open(archive, "r:") as tar:
        for member in tar:
            if len(inspected) < 12:
                inspected.append(member.name)
            parsed = split_member(member.name)
            if not parsed or not member.isfile():
                continue
            language, split, filename = parsed
            if split != "test" or not filename.lower().endswith((".m4a", ".wav")):
                continue
            if filename not in transcripts[language]:
                raise RuntimeError(f"No exact transcript for {member.name}; archive layout mismatch")
            if filename in found[language]:
                raise RuntimeError(f"Duplicate noisy audio file {filename}")
            encoded = raw_dir / f"{language}-{filename}"
            with tar.extractfile(member) as source, encoded.open("wb") as target:
                shutil.copyfileobj(source, target)
            wav = wav_dir / f"{language}-{Path(filename).stem}.wav"
            audio_to_wav(encoded, wav)
            encoded.unlink()
            found[language][filename] = {"language": language, "split": "test_unknown",
                                         "row": len(found[language]), "file": str(wav.resolve()),
                                         "sha256": sha256(wav), "reference": transcripts[language][filename],
                                         "source_id": f"kathbath:{language}:{Path(filename).stem}",
                                         "license": "CC-BY-4.0", "source": "AI4Bharat Kathbath noisy test"}
    print("Audio archive first members:", inspected)
    for language in LANGS:
        missing = set(transcripts[language]) - set(found[language])
        if missing or not found[language]:
            raise RuntimeError(f"{language}: {len(missing)} transcripts without audio; "
                               "check actual test-unknown archive layout")
        rows = list(found[language].values())
        path = output_dir / f"noisy-{language}-test-unknown.json"
        path.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"{language}: {len(rows)} noisy TEST-ONLY clips -> {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("/kaggle/working/itantra-noisy"))
    args = parser.parse_args()
    if not str(args.output_dir.resolve()).startswith("/kaggle/working/") or os.name == "nt":
        raise RuntimeError("Noisy Kathbath audio must be downloaded in /kaggle/working, not on the laptop")
    archives = download_archives(args.output_dir / "archives")
    transcripts = transcript_maps(archives["transcripts_n2w.tar"]["path"])
    print("Transcript test counts:", {language: len(rows) for language, rows in transcripts.items()})
    build_manifests(archives["testunk_audio.tar"]["path"], transcripts, args.output_dir)
    (args.output_dir / "archive-audit.json").write_text(json.dumps(
        {name: {key: str(value) for key, value in meta.items()} for name, meta in archives.items()}, indent=2),
        encoding="utf-8")


if __name__ == "__main__":
    main()
