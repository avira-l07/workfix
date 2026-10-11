"""Audit pinned voice files, token coverage and real offline TTS -> STT probes.

No downloads or expected-answer substitution. Roundtrip recognition is a fallible
diagnostic proxy, not a human intelligibility score or translation benchmark.
One child per language bounds native model memory and isolates runtime failures.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import unicodedata
import wave

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = ("hi", "en", "bn", "gu", "mr", "kn", "ml", "ta", "te", "or")
MT_LANGUAGES = set(LANGUAGES) - {"ml", "or"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def audit_voice(language, output):
    sys.path.insert(0, str(ROOT / "tools/stt_models/patched-python"))
    import numpy as np
    import sherpa_onnx
    from benchmark_five_language_tts import PHRASES

    manifest = json.loads((ROOT / f"app/src/main/assets/language_packs/{language}_dev_manifest.json").read_text(encoding="utf-8"))
    spec = manifest["ttsModel"]
    if language == "mr":
        directory = ROOT / "tools/stt_models/marathi-piper-candidate"
        model, tokens = directory / "model.onnx", directory / "tokens.txt"
        extra = {"data_dir": str(directory / "espeak-ng-data")}
        checked = {name: digest(directory / name) == checksum for name, checksum in spec["checksumsSha256"].items()}
    else:
        model = next(path for path in (
            ROOT / f"models/bundled/{language}/tts/model.onnx",
            ROOT / f"models/language_packs/{language}/tts/model.onnx") if path.is_file())
        tokens = ROOT / f"app/src/main/assets/language_packs/{language}/tts/tokens.txt"
        extra = {}
        checked = {"model.onnx": digest(model) == spec["checksumsSha256"]["model.onnx"],
                   "tokens.txt": digest(tokens) == spec["checksumsSha256"]["tokens.txt"]}
    assert all(checked.values()), f"Pinned voice checksum mismatch: {checked}"
    vocabulary = {line.rsplit(" ", 1)[0] for line in tokens.read_text(encoding="utf-8").splitlines()}
    report = {"language": language, "model_id": spec["modelId"], "model_sha256": digest(model),
              "checksums_verified": checked, "frontend": "espeak phonemes" if language == "mr" else "characters",
              "ascii_digits_missing": None if language == "mr" else [c for c in "0123456789" if c not in vocabulary],
              "human_listening_review": "NOT RUN", "cases": []}
    # Coverage inspection also includes mixed-script names and quantities. No name whitelist.
    for category, text in [("phrase", t) for t in PHRASES[language]] + [
        ("numeric", PHRASES[language][0] + " 9876543210"),
        ("mixed_name", PHRASES[language][0] + " Zubin")]:
        missing = [] if language == "mr" else sorted({c for c in unicodedata.normalize("NFC", text)
            if unicodedata.category(c)[0] in "LMN" and c.lower() not in vocabulary})
        report["cases"].append({"category": category, "text": text, "missing_characters": missing,
            "coverage": "phoneme frontend; needs listening review" if language == "mr" else
            "BLOCKED: text would lose characters" if missing else "covered; needs listening review"})

    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model), tokens=str(tokens), **extra), num_threads=1)))
    stt_dir = ROOT / "tools/stt_models/indicconformer-candidates"
    stt_model = stt_dir / language / "model.int8.onnx"
    stt_tokens = stt_dir / "en/tokens.txt" if language == "en" else stt_dir / "tokens.txt"
    stt_expected = manifest["sttModel"]["checksumsSha256"]
    assert digest(stt_model) == stt_expected["model.int8.onnx" if language == "en" else f"{language}/model.int8.onnx"]
    assert digest(stt_tokens) == stt_expected["tokens.txt"]
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=str(stt_model), tokens=str(stt_tokens), num_threads=2)
    for index, row in enumerate(report["cases"]):
        # Character-deficient content is deliberately never synthesized as a valid utterance.
        if row["missing_characters"] or row["category"] == "mixed_name":
            continue
        started = time.perf_counter()
        audio = tts.generate(row["text"])
        samples = np.asarray(audio.samples)
        assert samples.size > 0 and audio.sample_rate > 0 and np.isfinite(samples).all() and np.max(np.abs(samples)) > .001
        row.update(samples=int(samples.size), sample_rate_hz=audio.sample_rate,
                   generation_ms=round((time.perf_counter() - started) * 1000, 2))
        name = f"{language}-{index + 1}.wav"
        with wave.open(str(output / name), "wb") as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(audio.sample_rate)
            wav.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
        stream = recognizer.create_stream()
        stream.accept_waveform(audio.sample_rate, np.concatenate([samples, np.zeros(audio.sample_rate // 10, dtype=np.float32)]))
        recognizer.decode_stream(stream)
        row.update(audio_file=name, roundtrip_stt=stream.result.text.strip(),
                   roundtrip_scope="Automated proxy: either STT or TTS can introduce errors")
    (output / f"voice-{language}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--language", choices=LANGUAGES)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.language:
        audit_voice(args.language, output)
        return
    voices = []
    for language in LANGUAGES:
        run = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--output", str(output), "--language", language], capture_output=True)
        (output / f"voice-{language}.log").write_bytes(run.stdout + run.stderr)
        path = output / f"voice-{language}.json"
        voices.append(json.loads(path.read_text(encoding="utf-8")) if run.returncode == 0 else
                      {"language": language, "runtime_error": run.returncode, "log": f"voice-{language}.log"})
        print(f"{language}: {'verified' if run.returncode == 0 else 'FAILED'}", flush=True)
    with (output / "translation-routes.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["source", "target", "route", "new_device_quality_measurement"])
        for source in LANGUAGES:
            for target in LANGUAGES:
                route = "same-language; no translation" if source == target else "unsupported" if {source, target} - MT_LANGUAGES else "ML Kit; English intermediary" if "en" not in {source, target} else "ML Kit"
                writer.writerow([source, target, route, "NOT RUN"])
    report = {"date": "2026-10-11", "scope": "Pinned desktop voice audit; no phone attached; no human ratings or MT quality score",
              "voices": voices}
    (output / "voice-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if any("runtime_error" in voice for voice in voices):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
