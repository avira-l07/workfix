"""Kaggle-only Kathbath Gate A: metadata plan, held-out protection, 10-h export.

Run from the repository root with HF_TOKEN supplied as a Kaggle secret. Never
run the audio subcommands on the Windows development host. This script does
not train or promote a model. See docs/KATHBATH_GATE_A.md.
"""

import argparse
import hashlib
import heapq
import io
import json
import os
import re
from collections import Counter, defaultdict
from pathlib import Path

from check_speech_leakage import check_rows, text_hash
from prepare_speech_finetune import native_text, prepare
from validate_stt import normalize

REPO = "ai4bharat/Kathbath"
ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SHARDS = {"ta": 40, "te": 33}
LANG_FOLDER = {"ta": "tamil", "te": "telugu"}
SAMPLE_INDICES = {"ta": [0, 13, 26, 39], "te": [0, 11, 22, 32]}


def token():
    value = os.environ.get("HF_TOKEN")
    if not value:
        raise RuntimeError("HF_TOKEN Kaggle secret is missing")
    return value  # Never print, serialize, log or include it in a URL.


def hub(language):
    from huggingface_hub import HfApi, HfFileSystem

    secret = token()
    revision = HfApi(token=secret).dataset_info(REPO).sha
    return HfFileSystem(token=secret), revision


def list_files(fs, language, revision):
    folder = LANG_FOLDER[language]
    path = f"datasets/{REPO}@{revision}/{folder}"
    return sorted(fs.ls(path, detail=False))


def metadata_rows(fs, path, columns):
    import pyarrow.parquet as pq

    with fs.open(path, "rb") as remote:
        parquet = pq.ParquetFile(remote)
        missing = set(columns) - set(parquet.schema_arrow.names)
        if missing:
            raise RuntimeError(f"{path}: missing metadata columns {sorted(missing)}")
        for batch in parquet.iter_batches(batch_size=256, columns=columns):
            yield from batch.to_pylist()


def choose_shards(shards, target_hours, cap_minutes=20):
    """Greedy broad-speaker coverage; only metadata is involved."""
    remaining = list(shards)
    chosen, covered = [], set()
    hours = 0.0
    speaker_seconds = Counter()
    def capped_hours():
        return sum(min(seconds, cap_minutes * 60) for seconds in speaker_seconds.values()) / 3600
    while remaining and (hours < target_hours * 1.15 or capped_hours() < target_hours * 1.05):
        best = max(remaining, key=lambda s: (
            len(set(s["speaker_ids"]) - covered) / max(s["hours"], 0.01),
            len(set(s["speaker_ids"]) - covered), -s["index"]))
        chosen.append(best["index"])
        covered.update(best["speaker_ids"])
        hours += best["hours"]
        speaker_seconds.update({int(k): v for k, v in best["speaker_seconds"].items()})
        remaining.remove(best)
    if capped_hours() < target_hours:
        raise RuntimeError(f"Only {capped_hours():.2f} capped metadata hours among available train shards")
    return chosen


def plan(language, target_hours, output):
    fs, revision = hub(language)
    files = list_files(fs, language, revision)
    train = [(int(m.group(1)), path) for path in files
             if (m := re.search(r"/train-(\d+)-of-\d+\.parquet$", path))]
    train.sort()
    if len(train) != EXPECTED_SHARDS[language]:
        raise RuntimeError(f"Expected {EXPECTED_SHARDS[language]} {language} train shards; found {len(train)}")
    non_train = [path for path in files if path not in {p for _, p in train}]
    shards = []
    for index, path in train:
        clips, seconds, speakers, gender, per_speaker = 0, 0.0, set(), Counter(), Counter()
        for row in metadata_rows(fs, path, ["duration", "speaker_id", "gender"]):
            clips += 1
            seconds += float(row["duration"])
            speakers.add(int(row["speaker_id"]))
            per_speaker[int(row["speaker_id"])] += float(row["duration"])
            gender[str(row["gender"])] += 1
        shards.append({"index": index, "path": path, "clips": clips,
                       "hours": seconds / 3600, "speaker_ids": sorted(speakers),
                       "speaker_seconds": dict(per_speaker), "gender_clips": dict(gender)})
    if sum(s["clips"] for s in shards) == 0:
        raise RuntimeError("Empty train metadata")
    samples = [next(s for s in shards if s["index"] == i) for i in SAMPLE_INDICES[language]]
    overlap = {f"{a['index']}:{b['index']}": len(set(a["speaker_ids"]) & set(b["speaker_ids"]))
               for i, a in enumerate(samples) for b in samples[i + 1:]}
    chosen = choose_shards(shards, target_hours)
    chosen_set = set(chosen)
    selected = [s for s in shards if s["index"] in chosen_set]
    result = {"language": language, "dataset": REPO, "revision": revision,
              "license": "CC-BY-4.0 (Hub metadata); README also describes CC0 packaging and IndicCorp text provenance",
              "target_hours": target_hours, "sample_indices": SAMPLE_INDICES[language],
              "sample_overlap_speakers": overlap, "selected_shards": chosen,
              "selected_metadata_hours": sum(s["hours"] for s in selected),
              "selected_distinct_speakers": len(set().union(*(set(s["speaker_ids"]) for s in selected))),
              "non_train_files": non_train, "all_shards": shards}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{language}: revision={revision}; train shards={len(shards)}; non-train files={len(non_train)}")
    for shard in samples:
        print(f"  shard {shard['index']}: {shard['clips']} clips, {shard['hours']:.3f} h, {len(shard['speaker_ids'])} speakers")
    print(f"  sampled speaker overlaps: {overlap}")
    print(f"  selected shards={chosen}; metadata hours={result['selected_metadata_hours']:.3f}; "
          f"distinct speakers={result['selected_distinct_speakers']}")
    print("  non-train files:", *non_train, sep="\n    ")
    print(f"  wrote {output}")


def source_id(language, fname):
    return f"kathbath:{language}:{Path(str(fname)).stem}"


def decoded_audio(item):
    """Use the verified datasets AudioDecoder; fail instead of guessing a codec."""
    import numpy as np

    if hasattr(item, "get_all_samples"):
        samples = item.get_all_samples()
        audio, rate = samples.data, samples.sample_rate
        audio = audio.detach().cpu().numpy() if hasattr(audio, "detach") else np.asarray(audio)
    elif isinstance(item, dict) and "array" in item and "sampling_rate" in item:
        audio, rate = np.asarray(item["array"]), item["sampling_rate"]
    else:
        raise RuntimeError("audio_filepath is not a decoded AudioDecoder; check datasets/torchcodec versions")
    if audio.ndim == 2:
        if audio.shape[0] != 1:
            raise RuntimeError("Expected mono Kathbath audio")
        audio = audio[0]
    if audio.ndim != 1 or rate != 16000:
        raise RuntimeError(f"Expected 16-kHz mono Kathbath audio; got shape={audio.shape}, rate={rate}")
    return np.asarray(audio, dtype=np.float32)


def streamed_rows(paths):
    from datasets import Audio, load_dataset

    stream = load_dataset("parquet", data_files={"train": ["hf://" + p for p in paths]}, split="train",
                          streaming=True, token=token())
    stream = stream.cast_column("audio_filepath", Audio(sampling_rate=16000, decode=True))
    yield from stream


def wav_bytes(audio):
    import soundfile as sf

    buffer = io.BytesIO()
    sf.write(buffer, audio, 16000, format="WAV", subtype="PCM_16")
    return buffer.getvalue()


def protect(language, plan_path, fleurs_path, noisy_manifest, output):
    """Union complete FLEURS protection with every remote non-train split and noisy test."""
    plan_data = json.loads(plan_path.read_text(encoding="utf-8"))
    index = json.loads(fleurs_path.read_text(encoding="utf-8"))
    if plan_data["language"] != language or index.get("language") != language:
        raise RuntimeError("Plan and FLEURS protection language mismatch")
    if set(index.get("splits", {})) != {"validation", "test"}:
        raise RuntimeError("Complete FLEURS validation/test index required")
    non_train = [p for p in plan_data["non_train_files"] if p.endswith(".parquet")]
    if not non_train or not any("/valid-" in p for p in non_train):
        raise RuntimeError("Kathbath valid Parquet was not found; inspect non-train files")
    texts, ids, audios = map(lambda key: set(index[key]),
                            ("text_sha256", "source_ids", "audio_sha256"))
    split_counts = Counter()
    for path in non_train:
        split = Path(path).name.split("-")[0]
        for row in streamed_rows([path]):
            texts.add(text_hash(row["text"]))
            ids.add(source_id(language, row["fname"]))
            audios.add(hashlib.sha256(wav_bytes(decoded_audio(row["audio_filepath"]))).hexdigest())
            split_counts[split] += 1
    noisy = json.loads(noisy_manifest.read_text(encoding="utf-8"))
    if not noisy:
        raise RuntimeError("Noisy test manifest empty; cannot complete Gate A leakage check")
    for row in noisy:
        if row["language"] != language or row["split"] != "test_unknown":
            raise RuntimeError("Wrong noisy test language/split")
        texts.add(text_hash(row["reference"]))
        ids.add(row["source_id"])
        audios.add(row["sha256"])
    index.update({"text_sha256": sorted(texts), "source_ids": sorted(ids),
                  "audio_sha256": sorted(audios),
                  "extra_protection": {"kathbath_nontrain_rows": dict(split_counts),
                                       "kathbath_noisy_rows": len(noisy),
                                       "dataset_revision": plan_data["revision"]}})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    print(f"{language}: protected Kathbath non-train={dict(split_counts)}, noisy={len(noisy)}; "
          f"total text/source/audio hashes={len(texts)}/{len(ids)}/{len(audios)}")


def choose_clips(candidates, target_seconds, cap_seconds, seed):
    """Select diverse speakers with a per-speaker audio cap, deterministically."""
    by_speaker = defaultdict(list)
    for row in candidates:
        by_speaker[int(row["speaker_id"])].append(row)
    for speaker, rows in by_speaker.items():
        rows.sort(key=lambda r: hashlib.sha256(f"{seed}:{speaker}:{r['fname']}".encode()).hexdigest())
    used, pointers, heap = defaultdict(float), defaultdict(int), []
    for speaker in by_speaker:
        heapq.heappush(heap, (0.0, speaker))
    chosen, seconds = [], 0.0
    while heap and seconds < target_seconds:
        _, speaker = heapq.heappop(heap)
        rows = by_speaker[speaker]
        while pointers[speaker] < len(rows):
            row = rows[pointers[speaker]]
            pointers[speaker] += 1
            if used[speaker] + row["duration"] <= cap_seconds:
                chosen.append(row)
                used[speaker] += row["duration"]
                seconds += row["duration"]
                heapq.heappush(heap, (used[speaker], speaker))
                break
    if seconds < target_seconds * .98:
        raise RuntimeError(f"Speaker cap/shards yielded only {seconds / 3600:.2f} h")
    return chosen


def export(language, plan_path, protected_path, output_dir, target_hours, cap_minutes, seed):
    import shutil

    plan_data = json.loads(plan_path.read_text(encoding="utf-8"))
    protected = json.loads(protected_path.read_text(encoding="utf-8"))
    if plan_data["language"] != language or protected.get("language") != language:
        raise RuntimeError("Wrong plan/protected language")
    if not protected.get("extra_protection", {}).get("kathbath_noisy_rows"):
        raise RuntimeError("Combined FLEURS + Kathbath valid/noisy protection required")
    protected_text = set(protected["text_sha256"])
    protected_ids = set(protected["source_ids"])
    protected_audio = set(protected["audio_sha256"])
    fs, revision = hub(language)
    if revision != plan_data["revision"]:
        raise RuntimeError("Kathbath revision changed since metadata plan")
    paths = [next(s["path"] for s in plan_data["all_shards"] if s["index"] == index)
             for index in plan_data["selected_shards"]]
    excluded = Counter()
    candidates = []
    for path in paths:
        shard = int(re.search(r"/train-(\d+)-", path).group(1))
        for row in metadata_rows(fs, path, ["fname", "text", "duration", "speaker_id", "gender"]):
            text = normalize(row["text"])
            duration = float(row["duration"])
            sid = source_id(language, row["fname"])
            if not 1 <= duration <= 20 or not native_text(language, text):
                excluded["duration_or_script"] += 1
                continue
            if text_hash(text) in protected_text or sid in protected_ids:
                excluded["protected_text_or_id"] += 1
                continue
            candidates.append({"fname": row["fname"], "text": text, "duration": duration,
                               "speaker_id": int(row["speaker_id"]), "gender": row["gender"],
                               "shard": shard, "source_id": sid})
    chosen = choose_clips(candidates, target_hours * 3600, cap_minutes * 60, seed)
    selected = {row["source_id"]: row for row in chosen}
    if len(selected) != len(chosen):
        raise RuntimeError("Duplicate Kathbath fname/source ID among selected clips")
    projected_bytes = sum(row["duration"] * 16000 * 2 for row in chosen) * 2.3
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output_dir.parent).free < projected_bytes + 1_000_000_000:
        raise RuntimeError("Insufficient Kaggle free disk for selected WAVs plus prepared copy")
    if output_dir.exists() and any(output_dir.iterdir()):
        raise RuntimeError("Output directory must be empty; preserve prior trial results")
    raw_dir = output_dir / "raw_wav"
    raw_dir.mkdir(parents=True)
    source_rows = []
    for path in paths:
        for row in streamed_rows([path]):
            sid = source_id(language, row["fname"])
            if sid not in selected:
                continue
            chosen_row = selected.pop(sid)
            data = wav_bytes(decoded_audio(row["audio_filepath"]))
            if hashlib.sha256(data).hexdigest() in protected_audio:
                excluded["protected_audio"] += 1
                continue
            wav = raw_dir / (hashlib.sha256(sid.encode()).hexdigest()[:24] + ".wav")
            wav.write_bytes(data)
            source_rows.append({"audio_filepath": str(wav.resolve()), "text": chosen_row["text"],
                                "duration": chosen_row["duration"],
                                "source": "ai4bharat/Kathbath", "source_version": revision,
                                "license": "CC-BY-4.0", "source_id": sid,
                                "speaker_id": chosen_row["speaker_id"], "gender": chosen_row["gender"],
                                "source_shard": chosen_row["shard"], "noisy": False})
        if not selected:
            break
    if selected:
        raise RuntimeError(f"Selected metadata not found in streamed audio: {len(selected)} clips")
    if sum(r["duration"] for r in source_rows) / 3600 < target_hours * .98:
        # A protected-audio collision requires selecting replacement metadata.
        raise RuntimeError("Audio overlap lowered hours below target; regenerate plan with more shards")
    source_manifest = output_dir / f"{language}-source.jsonl"
    source_manifest.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in source_rows),
                               encoding="utf-8")
    prepare(language, source_manifest, output_dir / "prepared", protected_path, seed)
    audit_path = output_dir / "prepared" / f"{language}-audit.json"
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if sum(audit["hours"].values()) < target_hours * .98:
        raise RuntimeError("Measured WAV hours below target after preparation")
    audit.update({"excluded_metadata_or_audio": dict(excluded),
                  "native_script_check": "All exported train/dev transcripts passed",
                  "target_hours": target_hours, "speaker_cap_minutes": cap_minutes,
                  "attribution": "AI4Bharat Kathbath; CC-BY-4.0; see plan for packaging/text discrepancy"})
    audit_path.write_text(json.dumps(audit, indent=2), encoding="utf-8")
    print(f"{language}: selected {len(source_rows)} clips; excluded={dict(excluded)}")
    print("Run: python tools/check_speech_leakage.py check --language", language,
          "--protected", protected_path, "--manifest",
          ROOT / f"tools/stt_training/manifests/{language}-train.jsonl",
          ROOT / f"tools/stt_training/manifests/{language}-dev.jsonl")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--language", choices=EXPECTED_SHARDS, required=True)
    p.add_argument("--target-hours", type=float, default=10)
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("protect")
    p.add_argument("--language", choices=EXPECTED_SHARDS, required=True)
    p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--fleurs", type=Path, required=True)
    p.add_argument("--noisy-manifest", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("export")
    p.add_argument("--language", choices=EXPECTED_SHARDS, required=True)
    p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--protected", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    p.add_argument("--target-hours", type=float, default=10)
    p.add_argument("--cap-minutes", type=float, default=20)
    p.add_argument("--seed", type=int, default=17)
    a = parser.parse_args()
    if a.action in ("protect", "export") and (os.name == "nt" or not Path.cwd().resolve().is_relative_to(Path("/kaggle/working").resolve())):
        parser.error("Audio operations must run under /kaggle/working; no laptop audio downloads")
    if a.action == "plan":
        plan(a.language, a.target_hours, a.output)
    elif a.action == "protect":
        protect(a.language, a.plan, a.fleurs, a.noisy_manifest, a.output)
    else:
        export(a.language, a.plan, a.protected, a.output_dir,
               a.target_hours, a.cap_minutes, a.seed)


if __name__ == "__main__":
    main()
