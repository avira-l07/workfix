"""Small synthetic checks for saved-data rebasing and rejection of bad inputs."""

import json
import contextlib
import io
import shutil
import struct
import tempfile
import wave
from pathlib import Path, PurePosixPath
from unittest.mock import patch

from kathbath_gate_b_data import contained_file, digest, find_saved_root, prepare, saved_audio_path

ROOT = Path(__file__).resolve().parents[1]


def wav(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16000)
        audio.writeframes(struct.pack("<h", value) * 32000)


def fixture(root, windows_paths=False):
    saved = root / "input/saved-gate-a"
    source = saved / "itantra"
    tools = source / "tools"
    tools.mkdir(parents=True)
    for name in ("check_speech_leakage.py", "prepare_speech_finetune.py", "validate_stt.py"):
        shutil.copyfile(ROOT / "tools" / name, tools / name)
    data = tools / "stt_training"
    (data / "manifests").mkdir(parents=True)
    rows = []
    for i in range(3):
        rel = f"itantra-kathbath/ta/prepared/audio/{i}.wav"
        wav(saved / rel, i + 1)
        rows.append({"audio_filepath": f"/kaggle/working/{rel}", "duration": 2.0,
                     "text": "வணக்கம் உலகம்", "source_id": f"kathbath:ta:{i}",
                     "speaker_group": f"ai4bharat/Kathbath:{i}", "speaker_id": i,
                     "gender": "Female" if i == 2 else "Male", "source": "ai4bharat/Kathbath",
                     "source_version": "fixture-revision", "license": "CC-BY-4.0"})
    for split, items in (("train", rows[:2]), ("dev", rows[2:])):
        (data / "manifests" / f"ta-{split}.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in items), encoding="utf-8")
    rel = "itantra-noisy/wav/noisy.wav"
    wav(saved / rel, 10)
    noisy = [{"file": f"/kaggle/working/{rel}", "language": "ta", "split": "test_unknown",
              "sha256": digest(saved / rel), "reference": "நன்றி", "source_id": "kathbath:ta:noisy"}]
    (saved / "itantra-noisy/noisy-ta-test-unknown.json").write_text(json.dumps(noisy), encoding="utf-8")
    from check_speech_leakage import text_hash
    base = {"language": "ta", "splits": {"validation": {}, "test": {}},
            "text_sha256": [], "source_ids": [], "audio_sha256": []}
    combined = dict(base, text_sha256=[text_hash("நன்றி")], source_ids=["kathbath:ta:noisy"],
                    audio_sha256=[noisy[0]["sha256"]], extra_protection={
                        "dataset_revision": "fixture-revision", "kathbath_noisy_rows": 1,
                        "kathbath_nontrain_rows": {"valid": 1}})
    for name, value in (("ta-protected.json", base), ("ta-combined-protected.json", combined)):
        (data / name).write_text(json.dumps(value), encoding="utf-8")
    for folder, count in (("five-language-validation", 30), ("five-language-test", 100)):
        path = tools / "stt_models" / folder
        wav(path / "evaluation.wav", 20)
        (path / f"manifest-ta-{count}.json").write_text(json.dumps([
            {"file": (f"tools/stt_models/{folder}/evaluation.wav".replace("/", "\\") if windows_paths
                      else f"tools/stt_models/{folder}/evaluation.wav"), "sha256": digest(path / "evaluation.wav")}
            for _ in range(count)]), encoding="utf-8")
    files = [*tools.glob("*.py"), data / "ta-protected.json",
             *tools.glob("stt_models/*/*.json"), *tools.glob("stt_models/*/*.wav")]
    provenance = {"git_commit": "synthetic", "working_files": {
        (p.relative_to(source).as_posix().replace("/", "\\") if windows_paths
         else p.relative_to(source).as_posix()): digest(p) for p in files}}
    (source / "bundle-provenance.json").write_text(json.dumps(provenance), encoding="utf-8")
    report = {"status": "Gate A only; training not run", "bundle_provenance": provenance,
              "languages": {"ta": {"plan": {"revision": "fixture-revision"},
                  "audit": {"counts": {"train": 2, "dev": 1}, "hours": {"train": 4/3600, "dev": 2/3600},
                            "speaker_groups_by_split": {"train": 2, "dev": 1},
                            "speaker_cap_minutes": 20, "gender_clips": {"Male": 2, "Female": 1}},
                  "noisy_test_count": 1}}}
    (saved / "itantra_gate_a_report.json").write_text(json.dumps(report), encoding="utf-8")
    return saved, digest(saved / "itantra_gate_a_report.json")


def rejects(callback, expected):
    try:
        callback()
    except (RuntimeError, FileNotFoundError) as exc:
        assert expected in str(exc), str(exc)
    else:
        raise AssertionError(f"Expected rejection: {expected}")


def check_notebook_cells():
    notebook = json.loads((ROOT / "tools/kathbath_gate_b_preflight.ipynb").read_text(encoding="utf-8"))
    cells = {i: "".join(c["source"]) for i, c in enumerate(notebook["cells"]) if c["cell_type"] == "code"}
    for i, source in cells.items():
        compile(source, f"preflight-cell-{i}", "exec")
    assert cells[2] == (ROOT / "tools/kathbath_gate_b_data.py").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="itantra-preflight-cell-check-") as tmp:
        root = Path(tmp)
        saved, sha = fixture(root)
        session = root / "session"
        session.mkdir()
        source = cells[3].replace("/kaggle/working", session.as_posix()).replace("/kaggle/input", (root / "input").as_posix())
        namespace = {}
        with contextlib.redirect_stdout(io.StringIO()):
            # Run the check without the helper cell, as after a fresh kernel.
            # It must save an actionable failure report, not throw a NameError.
            rejects(lambda: exec(source, namespace), "helper code cell")
            report = session / "itantra_gate_b_preflight.json"
            failed = report.read_bytes()
            assert json.loads(failed)["error_type"] == "RuntimeError"
            rejects(lambda: exec(cells[5], namespace), "saved-data check")
            assert report.read_bytes() == failed
            # Reproduce the old screenshot's falsely overwritten status, even
            # with leftover success fields. An error must still block promotion.
            namespace["preflight"].update(status="Saved data passed; CUDA GPU unavailable",
                                          saved_data_verified=True, gate_a_report_sha256=sha,
                                          counts={"train": 2, "dev": 1})
            rejects(lambda: exec(cells[5], namespace), "saved-data check")
            assert report.read_bytes() == failed
            # Even the ZIP cell can run after failure without relying on globals.
            zip_source = cells[7].replace("/kaggle/working", session.as_posix())
            exec(zip_source, {})
            assert (session / "itantra_gate_b_preflight_report.zip").is_file()
            # Running the helper first restores the successful synthetic path.
            exec(cells[2], namespace)
            namespace["prepare"] = lambda saved_root, repo, language: prepare(saved_root, repo, language, reviewed_sha=sha)
            exec(source, namespace)
            success = json.loads(report.read_text(encoding="utf-8"))
            assert success["counts"] == {"train": 2, "dev": 1}
            assert success["status"] == "Saved Tamil data checks passed; training not run"
            assert success["saved_data_verified"] is True


def check_cpu_notebook_report():
    notebook = json.loads((ROOT / "tools/kathbath_gate_b_datacheck_cpu.ipynb").read_text(encoding="utf-8"))
    for cell in notebook["cells"]:
        if cell["cell_type"] == "code":
            compile("".join(cell["source"]), "cpu-notebook-cell", "exec")
    source = "".join(notebook["cells"][5]["source"])
    with tempfile.TemporaryDirectory() as tmp:
        target = Path(tmp) / "itantra_gate_b_preflight.json"
        source = source.replace("/kaggle/working/itantra_gate_b_preflight.json", target.as_posix())
        data_report = {"saved_data_verified": True, "language": "ta"}
        namespace = {"summary": {"saved-data-check": "Failed"}, "data_report": data_report,
                     "Path": Path, "json": json, "pack_reports": lambda: None}
        # Data preparation may pass before a later subset/plan check fails.
        # Even with a populated data_report, the final cell must not pass it.
        rejects(lambda: exec(source, namespace), "complete successfully")
        assert not target.exists()
        namespace["summary"]["saved-data-check"] = "Completed"
        data_report["error"] = "previous failure"
        rejects(lambda: exec(source, namespace), "complete successfully")
        assert not target.exists()
        del data_report["error"]
        with contextlib.redirect_stdout(io.StringIO()):
            exec(source, namespace)
        report = json.loads(target.read_text(encoding="utf-8"))
        assert report["saved_data_verified"] is True
        assert report["gpu_runtime"] == "Not checked"
        assert report["post_training_wer"] == "Not run"
        assert report["phone_accuracy"] == "Not verified"


def main():
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        saved, sha = fixture(root, windows_paths=True)
        result = prepare(saved, root / "portable", reviewed_sha=sha)
        assert result["saved_data_verified"] is True
        source = saved / "itantra"
        # Windows accepts raw backslashes too, hiding the Kaggle/Linux failure.
        # Require the resolver to use the normalized path when opening the file.
        expected = (source / "tools/stt_models/five-language-validation/evaluation.wav").resolve()

        with patch.object(Path, "__truediv__", autospec=True) as join:
            def checked_join(root_path, relative):
                assert isinstance(relative, PurePosixPath), "Opening unnormalized path on Linux"
                assert "\\" not in relative.as_posix()
                return root_path.joinpath(relative)
            join.side_effect = checked_join
            assert contained_file(source, r"tools\stt_models\five-language-validation\evaluation.wav") == expected
        assert contained_file(source, r"tools\stt_models\five-language-validation\evaluation.wav") == (
            source / "tools/stt_models/five-language-validation/evaluation.wav").resolve()
        rebased = json.loads((root / "portable/tools/stt_models/five-language-validation/manifest-ta-30.json").read_text())
        assert all(Path(r["file"]).is_file() and digest(r["file"]) == r["sha256"] for r in rebased)
        assert digest(saved / "itantra_gate_a_report.json") == sha  # Source bytes remain unchanged.
        for unsafe in (r"tools\..\escape.wav", r"C:\escape.wav", r"C:escape.wav",
                       r"\\server\share\escape.wav", r"\escape.wav", "../escape.wav", "tools/stream:secret"):
            rejects(lambda value=unsafe: contained_file(source, value), "Unsafe")
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        saved, sha = fixture(root)
        assert find_saved_root(root / "input") == saved.resolve()
        result = prepare(saved, root / "work", reviewed_sha=sha)
        assert result["leakage_output"] == "ta: checked 3 rows; overlaps/errors=0"
        assert result["training_audio_copied"] is False
        manifest = root / "work/tools/stt_training/manifests/ta-train.jsonl"
        rows = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines()]
        assert all(Path(r["audio_filepath"]).is_relative_to(saved) for r in rows)
        assert not list((root / "work").rglob("*.wav"))
        rejects(lambda: contained_file(saved, "../escape.wav"), "Unsafe")
        rejects(lambda: saved_audio_path("/kaggle/working/itantra-kathbath/../../escape", saved), "Unsafe")
        rejects(lambda: prepare(saved, root / "bad", reviewed_sha="0"*64), "report differs")
        rejects(lambda: prepare(saved, root / "bad", language="te", reviewed_sha=sha), "Tamil only")
        noisy = saved / "itantra-noisy/wav/noisy.wav"
        wav(noisy, 99)
        rejects(lambda: prepare(saved, root / "bad", reviewed_sha=sha), "Noisy test WAV checksum")
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        saved, sha = fixture(root)
        shutil.copyfile(saved / "itantra-kathbath/ta/prepared/audio/0.wav",
                        saved / "itantra-kathbath/ta/prepared/audio/2.wav")
        rejects(lambda: prepare(saved, root / "work", reviewed_sha=sha), "audio overlap")
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        saved, sha = fixture(root)
        (saved / "itantra/tools/validate_stt.py").write_text("# changed\n", encoding="utf-8")
        rejects(lambda: prepare(saved, root / "work", reviewed_sha=sha), "bundle file changed")
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        saved, sha = fixture(root)
        manifest = saved / "itantra/tools/stt_training/manifests/ta-dev.jsonl"
        row = json.loads(manifest.read_text(encoding="utf-8"))
        row["speaker_group"] = "ai4bharat/Kathbath:0"
        manifest.write_text(json.dumps(row) + "\n", encoding="utf-8")
        rejects(lambda: prepare(saved, root / "work", reviewed_sha=sha), "speaker, source ID or audio overlap")
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        saved, sha = fixture(root)
        audio = saved / "itantra-kathbath/ta/prepared/audio/0.wav"
        audio.write_bytes(audio.read_bytes()[:50])
        rejects(lambda: prepare(saved, root / "work", reviewed_sha=sha), "Truncated WAV")
    check_notebook_cells()
    check_cpu_notebook_report()
    print("Saved-data preflight: Windows/POSIX path rebasing, checksums, leakage, no WAV copies, rejection checks "
          "and notebook cell-order/failure-report regression checks passed (synthetic only)")


if __name__ == "__main__":
    main()
