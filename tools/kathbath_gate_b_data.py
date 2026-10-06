"""Recheck saved Gate A data without downloading or copying training WAVs.

The Kaggle preflight notebook embeds this module, so importing the notebook is
the only new upload needed. This module neither trains nor installs packages.
"""

import hashlib
import json
import math
import shutil
import subprocess
import sys
import wave
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath, PureWindowsPath

REVIEWED_REPORT_SHA256 = "73ea703471c99427ce0fbe9757c9854a2607bfe9bb35d6eeda4772a252384451"


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def portable_relative_path(relative):
    if not isinstance(relative, str) or not relative or "\x00" in relative:
        raise RuntimeError(f"Unsafe saved path: {relative}")
    # The original benchmark manifests were generated on Windows. Interpret
    # both separators, without editing the checksum-protected source manifests.
    part = PurePosixPath(relative.replace("\\", "/"))
    windows = PureWindowsPath(relative)
    if part.is_absolute() or windows.drive or windows.root or ".." in part.parts or ":" in relative:
        raise RuntimeError(f"Unsafe saved path: {relative}")
    return part


def contained_file(root, relative):
    part = portable_relative_path(relative)
    root = Path(root).resolve()
    path = (root / part).resolve(strict=True)
    if not path.is_relative_to(root) or not path.is_file():
        raise RuntimeError(f"Saved file escapes its input root: {relative}")
    return path


def saved_audio_path(path, saved_root):
    original = PurePosixPath(path.replace("\\", "/"))
    try:
        relative = original.relative_to("/kaggle/working")
    except ValueError as exc:
        raise RuntimeError(f"Audio path is not from the reviewed Gate A session: {path}") from exc
    if not relative.parts or relative.parts[0] not in ("itantra-kathbath", "itantra-noisy"):
        raise RuntimeError(f"Unexpected Gate A audio folder: {path}")
    return contained_file(saved_root, relative.as_posix())


def find_saved_root(input_root):
    candidates = [p.parent for p in Path(input_root).rglob("itantra_gate_a_report.json")
                  if (p.parent / "itantra-kathbath").is_dir()
                  and (p.parent / "itantra-noisy").is_dir()
                  and (p.parent / "itantra/tools/stt_training").is_dir()]
    if len(candidates) != 1:
        raise RuntimeError("Attach exactly one completed Gate A notebook output using "
                           f"Add Input > Notebook > Your Work; found {len(candidates)} complete roots")
    return candidates[0].resolve()


def wav_duration(path):
    with wave.open(str(path), "rb") as audio:
        if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth(), audio.getcomptype()) != (
                16000, 1, 2, "NONE"):
            raise RuntimeError(f"Expected 16-kHz mono PCM16 WAV: {path}")
        if not audio.getnframes():
            raise RuntimeError(f"Empty WAV: {path}")
        audio.setpos(audio.getnframes() - 1)
        if len(audio.readframes(1)) != 2:
            raise RuntimeError(f"Truncated WAV payload: {path}")
        return audio.getnframes() / 16000


def prepare(saved_root, repo, language="ta", reviewed_sha=REVIEWED_REPORT_SHA256):
    if language != "ta":
        raise RuntimeError("This preflight is Tamil only; Telugu follows the reviewed Tamil trial")
    saved_root, repo = Path(saved_root).resolve(), Path(repo).resolve()
    if repo == saved_root or repo.is_relative_to(saved_root):
        raise RuntimeError("Working output must be separate from saved input")
    report_path = contained_file(saved_root, "itantra_gate_a_report.json")
    if digest(report_path) != reviewed_sha:
        raise RuntimeError("Saved Gate A report differs from the report reviewed on 4 October 2026")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    entry = report["languages"][language]
    audit, plan = entry["audit"], entry["plan"]
    if report["status"] != "Gate A only; training not run":
        raise RuntimeError("Unexpected Gate A report status")

    source_repo = saved_root / "itantra"
    provenance = json.loads(contained_file(source_repo, "bundle-provenance.json").read_text(encoding="utf-8"))
    if provenance != report["bundle_provenance"]:
        raise RuntimeError("Saved bundle provenance differs from the reviewed report")
    # Verify the original evaluation audio and tools before executing copied code.
    for name, expected in provenance["working_files"].items():
        if digest(contained_file(source_repo, name)) != expected:
            raise RuntimeError(f"Original bundle file changed: {name}")
    tools = repo / "tools"
    tools.mkdir(parents=True, exist_ok=True)
    for name in provenance["working_files"]:
        part = portable_relative_path(name)
        if part.parts[0] == "tools" and part.suffix == ".py":
            destination = tools / part.name
            shutil.copyfile(contained_file(source_repo, name), destination)
    sys.path.insert(0, str(tools))
    from check_speech_leakage import check_rows, text_hash
    from prepare_speech_finetune import native_text

    data = tools / "stt_training"
    data.mkdir(exist_ok=True)
    index_path = contained_file(source_repo, f"tools/stt_training/{language}-combined-protected.json")
    index = json.loads(index_path.read_text(encoding="utf-8"))
    base = json.loads(contained_file(source_repo, f"tools/stt_training/{language}-protected.json").read_text(encoding="utf-8"))
    if index.get("language") != language or set(index.get("splits", {})) != {"validation", "test"}:
        raise RuntimeError("Wrong language or incomplete protected FLEURS splits")
    extra = index.get("extra_protection", {})
    if (extra.get("dataset_revision") != plan["revision"]
            or extra.get("kathbath_noisy_rows") != entry["noisy_test_count"]
            or extra.get("kathbath_nontrain_rows", {}).get("valid", 0) <= 0):
        raise RuntimeError("Combined protection lacks the reviewed Kathbath valid/noisy scope")
    for key in ("text_sha256", "source_ids", "audio_sha256"):
        if not set(base[key]).issubset(index[key]):
            raise RuntimeError(f"Combined protection dropped FLEURS entries: {key}")

    sides, speaker_seconds, audio_hashes = {}, defaultdict(float), {}
    for split in ("train", "dev"):
        manifest = contained_file(source_repo, f"tools/stt_training/manifests/{language}-{split}.jsonl")
        rows = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
        if len(rows) != audit["counts"][split]:
            raise RuntimeError(f"{split} clip count differs from Gate A")
        for row in rows:
            row["audio_filepath"] = str(saved_audio_path(row["audio_filepath"], saved_root))
            duration = wav_duration(row["audio_filepath"])
            if not 1 <= duration <= 20 or abs(duration - row["duration"]) > 1 / 16000:
                raise RuntimeError(f"Invalid measured duration: {row['source_id']}")
            if not native_text(language, row["text"]):
                raise RuntimeError(f"Non-native reference transcript: {row['source_id']}")
            if (row["source"], row["source_version"], row["license"]) != (
                    "ai4bharat/Kathbath", plan["revision"], "CC-BY-4.0"):
                raise RuntimeError(f"Source provenance differs: {row['source_id']}")
            speaker_seconds[row["speaker_group"]] += duration
        if not math.isclose(sum(r["duration"] for r in rows) / 3600, audit["hours"][split], abs_tol=1e-8):
            raise RuntimeError(f"{split} hours differ from Gate A")
        if len({r["speaker_group"] for r in rows}) != audit["speaker_groups_by_split"][split]:
            raise RuntimeError(f"{split} speaker count differs from Gate A")
        errors = check_rows(index, rows)
        if errors:
            raise RuntimeError(f"{split} leakage check failed: {'; '.join(errors[:5])}")
        sides[split] = rows
        audio_hashes[split] = {digest(r["audio_filepath"]) for r in rows}
    train, dev = sides["train"], sides["dev"]
    if ({r["speaker_group"] for r in train} & {r["speaker_group"] for r in dev}
            or {r["source_id"] for r in train} & {r["source_id"] for r in dev}
            or audio_hashes["train"] & audio_hashes["dev"]):
        raise RuntimeError("Train/dev speaker, source ID or audio overlap")
    if max(speaker_seconds.values()) > audit["speaker_cap_minutes"] * 60 + 1 / 16000:
        raise RuntimeError("Measured speaker duration exceeds the Gate A cap")
    all_rows = train + dev
    if len({r["source_id"] for r in all_rows}) != len(all_rows):
        raise RuntimeError("Duplicate training/dev source IDs")
    if dict(Counter(r["gender"] for r in all_rows)) != audit["gender_clips"]:
        raise RuntimeError("Gender counts differ from Gate A")

    noisy_path = contained_file(saved_root, f"itantra-noisy/noisy-{language}-test-unknown.json")
    noisy = json.loads(noisy_path.read_text(encoding="utf-8"))
    if len(noisy) != entry["noisy_test_count"]:
        raise RuntimeError("Noisy test count differs from Gate A")
    for row in noisy:
        if row["language"] != language or row["split"] != "test_unknown":
            raise RuntimeError("Wrong noisy test language/split")
        row["file"] = str(saved_audio_path(row["file"], saved_root))
        wav_duration(row["file"])
        if digest(row["file"]) != row["sha256"]:
            raise RuntimeError(f"Noisy test WAV checksum mismatch: {row['source_id']}")
        if (row["source_id"] not in index["source_ids"] or row["sha256"] not in index["audio_sha256"]
                or text_hash(row["reference"]) not in index["text_sha256"]):
            raise RuntimeError("Noisy test row is absent from the combined protection index")

    # Absolute file fields also work with the existing ROOT / row['file'] evaluators.
    # Rewrite only small manifests; leave all evaluation WAVs in read-only input.
    for directory, count in (("five-language-validation", 30), ("five-language-test", 100)):
        name = f"manifest-{language}-{count}.json"
        original = contained_file(source_repo, f"tools/stt_models/{directory}/{name}")
        records = json.loads(original.read_text(encoding="utf-8"))
        if len(records) != count:
            raise RuntimeError(f"Expected {count} protected FLEURS evaluation rows")
        for row in records:
            row["file"] = str(contained_file(source_repo, row["file"]))
            if digest(row["file"]) != row["sha256"]:
                raise RuntimeError("FLEURS evaluation WAV checksum mismatch")
        destination = tools / "stt_models" / directory
        destination.mkdir(parents=True, exist_ok=True)
        (destination / name).write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest_root = data / "manifests"
    manifest_root.mkdir(exist_ok=True)
    for split, rows in sides.items():
        (manifest_root / f"{language}-{split}.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    protected = data / f"{language}-combined-protected.json"
    shutil.copyfile(index_path, protected)
    rebased_noisy = data / f"noisy-{language}-test-unknown.json"
    rebased_noisy.write_text(json.dumps(noisy, ensure_ascii=False, indent=2), encoding="utf-8")
    check = subprocess.run([sys.executable, str(tools / "check_speech_leakage.py"), "check",
                            "--language", language, "--protected", str(protected), "--manifest",
                            str(manifest_root / f"{language}-train.jsonl"),
                            str(manifest_root / f"{language}-dev.jsonl")],
                           check=True, capture_output=True, text=True)
    return {"status": "Saved Tamil data checks passed; training not run", "language": language,
            "saved_data_verified": True,
            "gate_a_report_sha256": digest(report_path), "saved_root": str(saved_root), "repo": str(repo),
            "bundle_files_verified": len(provenance["working_files"]), "counts": audit["counts"],
            "hours": audit["hours"], "speakers": audit["speaker_groups_by_split"],
            "max_speaker_seconds": max(speaker_seconds.values()), "leakage_output": check.stdout.strip(),
            "noisy_test_count": len(noisy), "training_audio_copied": False,
            "post_training_wer": "Not run", "phone_accuracy": "Not verified"}
