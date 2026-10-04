"""Package scripts and public FLEURS holdouts for a Kaggle Gate A run.

No Kathbath audio, token, training checkpoint, or private recording is copied.
Output stays under Git-ignored tools/stt_training/ for manual Kaggle upload.
"""

import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "tools/stt_training/kaggle_gate_a_bundle.zip"
SCRIPTS = [
    "tools/benchmark_five_language_stt.py", "tools/validate_stt.py",
    "tools/check_speech_leakage.py", "tools/prepare_speech_finetune.py",
    "tools/kathbath_trial.py", "tools/kathbath_noisy_test.py",
    "tools/test_kathbath_trial.py", "tools/test_speech_leakage.py",
    "tools/evaluate_stt_preprocessing.py", "tools/export_finetuned_indic_ctc.py",
    "tools/finetune_indicconformer.ipynb",
]


def main():
    files = [ROOT / item for item in SCRIPTS]
    for language in ("ta", "te"):
        files.extend([
            ROOT / f"tools/stt_training/{language}-protected.json",
            ROOT / f"tools/stt_models/five-language-validation/manifest-{language}-30.json",
            ROOT / f"tools/stt_models/five-language-test/manifest-{language}-100.json",
        ])
        for folder, count in (("five-language-validation", 30), ("five-language-test", 100)):
            manifest = ROOT / f"tools/stt_models/{folder}/manifest-{language}-{count}.json"
            rows = json.loads(manifest.read_text(encoding="utf-8"))
            assert len(rows) == count
            for row in rows:
                audio = ROOT / row["file"]
                assert hashlib.sha256(audio.read_bytes()).hexdigest() == row["sha256"]
                files.append(audio)
    missing = [str(file) for file in files if not file.is_file()]
    if missing:
        raise RuntimeError("Missing pinned public holdouts: " + ", ".join(missing[:8]))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for file in sorted(files):
            bundle.write(file, file.relative_to(ROOT).as_posix())
        provenance = {"git_commit": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                                   check=True, capture_output=True, text=True).stdout.strip(),
                      "working_files": {file.relative_to(ROOT).as_posix():
                                        hashlib.sha256(file.read_bytes()).hexdigest() for file in files}}
        bundle.writestr("bundle-provenance.json", json.dumps(provenance, indent=2))
    with OUTPUT.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    print(f"{OUTPUT}\nfiles={len(files)} bytes={OUTPUT.stat().st_size} sha256={sha}")


if __name__ == "__main__":
    main()
