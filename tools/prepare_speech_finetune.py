"""Validate licensed, consented source rows and write speaker-grouped NeMo manifests.

Input JSONL fields: audio_filepath, text, source, source_version, license,
source_id, speaker_id OR source_recording_id, noisy (bool), consent (bool for
user-collected recordings). Input audio must be readable; output is 16-kHz mono
PCM WAV. All output stays under ignored tools/stt_training/ by default.
"""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from check_speech_leakage import DATA, check_rows, text_hash
from validate_stt import normalize

SCRIPT_RANGES = {"ta": (0x0B80, 0x0BFF), "te": (0x0C00, 0x0C7F),
                 "bn": (0x0980, 0x09FF), "gu": (0x0A80, 0x0AFF)}
ALLOWED_LICENSES = {"CC-BY-4.0", "CC0-1.0", "MIT"}


def speaker_split(groups, seed=17, dev_fraction=0.1):
    """Choose whole speaker/recording groups for dev, independent of clip order."""
    ordered = sorted(set(groups), key=lambda group: hashlib.sha256(
        f"{seed}:{group}".encode()).hexdigest())
    if len(ordered) < 2:
        raise ValueError("At least two independent speaker/recording groups are needed")
    dev_count = max(1, min(len(ordered) - 1, round(len(ordered) * dev_fraction)))
    dev = set(ordered[:dev_count])
    return set(ordered[dev_count:]), dev


def native_text(language, text):
    lo, hi = SCRIPT_RANGES[language]
    letters = [c for c in text if c.isalpha()]
    return bool(letters) and all(lo <= ord(c) <= hi for c in letters)


def prepare(language, input_path, output_dir, protected_path, seed):
    import numpy as np
    import soundfile as sf

    protected = json.loads(protected_path.read_text(encoding="utf-8"))
    if protected.get("language") != language or set(protected.get("splits", {})) != {"validation", "test"}:
        raise RuntimeError("Full matching FLEURS validation+test protection is required")
    rows = [json.loads(line) for line in input_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        raise RuntimeError("No licensed training rows supplied")
    overlap = check_rows(protected, rows)
    if overlap:
        raise RuntimeError("Source data overlaps held-out FLEURS or is incomplete: " + "; ".join(overlap[:8]))
    groups = [f"{row.get('source')}:{row['speaker_id'] if row.get('speaker_id') is not None else row.get('source_recording_id')}"
              for row in rows if row.get('speaker_id') is not None or row.get('source_recording_id')]
    if len(groups) != len(rows):
        raise RuntimeError("Every row needs a speaker or source-recording group")
    train_groups, dev_groups = speaker_split(groups, seed)
    output_dir.mkdir(parents=True, exist_ok=True)
    audio_dir = output_dir / "audio"
    audio_dir.mkdir(exist_ok=True)
    manifests = {"train": [], "dev": []}
    counts = Counter()
    speakers, sources, train_words = set(), set(), set()
    for row in rows:
        if row.get("license") not in ALLOWED_LICENSES or not row.get("source_version"):
            raise RuntimeError("Every row needs a checked permissive license and source version")
        if row.get("source") == "user-collected" and row.get("consent") is not True:
            raise RuntimeError("User-collected audio requires recorded speaker consent")
        group = row.get("speaker_id") if row.get("speaker_id") is not None else row.get("source_recording_id")
        if group is None or group == "":
            raise RuntimeError("Speaker or source recording group is required; never split by clip")
        group = f"{row['source']}:{group}"
        audio, rate = sf.read(row["audio_filepath"], dtype="float32", always_2d=True)
        if audio.shape[1] > 1:
            audio = audio.mean(axis=1)
        else:
            audio = audio[:, 0]
        if rate != 16000:
            try:
                import librosa
            except ImportError as exc:
                raise RuntimeError("Install librosa to resample non-16-kHz source audio") from exc
            audio = librosa.resample(audio, orig_sr=rate, target_sr=16000)
        duration = len(audio) / 16000
        text = normalize(row["text"])
        if not 1 <= duration <= 20 or not native_text(language, text):
            counts["filtered_duration_or_script"] += 1
            continue
        split = "dev" if group in dev_groups else "train"
        filename = hashlib.sha256((str(row["audio_filepath"]) + row["source_id"]).encode()).hexdigest()[:20] + ".wav"
        destination = audio_dir / filename
        sf.write(destination, np.asarray(audio, dtype=np.float32), 16000, subtype="PCM_16")
        item = {"audio_filepath": str(destination.resolve()), "duration": duration, "text": text,
                "source_id": row["source_id"], "speaker_group": group, "speaker_id": row.get("speaker_id"),
                "source": row["source"], "source_version": row["source_version"],
                "license": row["license"], "noisy": bool(row.get("noisy", False)),
                "gender": row.get("gender", "unknown"), "source_shard": row.get("source_shard")}
        manifests[split].append(item)
        counts[split] += 1
        speakers.add(group)
        sources.add((row["source"], row["source_version"], row["license"]))
        if split == "train":
            train_words.update(text.split())
    if not manifests["train"] or not manifests["dev"]:
        raise RuntimeError("Need independent speaker/recording groups in both train and dev")
    assert not ({r["speaker_group"] for r in manifests["train"]} &
                {r["speaker_group"] for r in manifests["dev"]})
    dev_words = {word for row in manifests["dev"] for word in row["text"].split()}
    histogram = {"1-5s": 0, "5-10s": 0, "10-20s": 0}
    for row in manifests["train"] + manifests["dev"]:
        duration = row["duration"]
        histogram["1-5s" if duration < 5 else "5-10s" if duration < 10 else "10-20s"] += 1
    manifest_root = DATA / "manifests"
    manifest_root.mkdir(parents=True, exist_ok=True)
    for split, items in manifests.items():
        path = manifest_root / f"{language}-{split}.jsonl"
        path.write_text("".join(json.dumps(item, ensure_ascii=False) + "\n" for item in items),
                        encoding="utf-8")
    # Check the actual re-encoded manifests again before any training step.
    errors = check_rows(protected, manifests["train"] + manifests["dev"])
    if errors:
        raise RuntimeError("Generated manifest has held-out overlap: " + "; ".join(errors[:8]))
    audit = {"language": language, "input_rows": len(rows), "counts": dict(counts),
             "hours": {split: sum(r["duration"] for r in items) / 3600
                       for split, items in manifests.items()},
             "speaker_or_recording_groups": len(speakers), "sources": sorted(sources),
             "speaker_groups_by_split": {split: len({r["speaker_group"] for r in items})
                                         for split, items in manifests.items()},
             "speaker_overlap_count": len({r["speaker_group"] for r in manifests["train"]} &
                                          {r["speaker_group"] for r in manifests["dev"]}),
             "gender_clips": dict(Counter(r["gender"] for items in manifests.values() for r in items)),
             "vocabulary_size": len(train_words),
             "source_shards": sorted({r["source_shard"] for items in manifests.values()
                                      for r in items if r["source_shard"] is not None}),
             "noisy_percent": 100 * sum(r["noisy"] for items in manifests.values() for r in items) /
                              (len(manifests["train"]) + len(manifests["dev"])),
             "duration_histogram": histogram,
             "dev_vocabulary_covered_percent": 100 * len(dev_words & train_words) / len(dev_words)
             if dev_words else None, "held_out_overlap_count": 0}
    (output_dir / f"{language}-audit.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")
    print(json.dumps(audit, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=tuple(SCRIPT_RANGES), required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--protected", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=DATA)
    parser.add_argument("--seed", type=int, default=17)
    args = parser.parse_args()
    prepare(args.language, args.input, args.output_dir, args.protected, args.seed)
