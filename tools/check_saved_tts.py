"""Fresh offline repeated-synthesis check of every currently selected TTS pack.

Uses existing local model files and the field phrases from the benchmark. No
downloads, training, phone playback or listener-quality claims are made.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts/tts-replay-2026-10-06"
LANGUAGES = ("hi", "en", "bn", "gu", "mr", "kn", "ml", "ta", "te", "or")


def check_language(language):
    # Reuse the installed audit runtime when available; no new dependencies.
    for folder in ("validation-deps", "patched-python"):
        location = ROOT / "tools/stt_models" / folder
        if location.is_dir():
            sys.path.insert(0, str(location))
    import numpy as np
    import sherpa_onnx
    from benchmark_five_language_tts import PHRASES, digest
    manifest = json.loads((ROOT / f"app/src/main/assets/language_packs/{language}_dev_manifest.json").read_text(encoding="utf-8"))
    if language == "mr":
        directory = ROOT / "tools/stt_models/marathi-piper-candidate"
        model, tokens = directory / "model.onnx", directory / "tokens.txt"
        extra = {"data_dir": str(directory / "espeak-ng-data")}
        for name, checksum in manifest["ttsModel"]["checksumsSha256"].items():
            assert digest(directory / name) == checksum, f"Marathi pinned file mismatch: {name}"
    else:
        candidates = [ROOT / f"{folder}/{language}/tts/model.onnx" for folder in
                      ("models/bundled", "app/src/main/assets/language_packs", "models/language_packs")]
        model = next((path for path in candidates if path.is_file()), candidates[0])
        tokens = ROOT / f"app/src/main/assets/language_packs/{language}/tts/tokens.txt"
        expected = manifest["ttsModel"]["checksumsSha256"]
        assert digest(model) == expected["model.onnx"] and digest(tokens) == expected["tokens.txt"], "Pinned model checksum mismatch"
        extra = {}
    engine = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model), tokens=str(tokens), **extra), num_threads=1)))
    phrase = PHRASES[language][0]
    trials = []
    for repeat in range(2):
        started = time.perf_counter()
        audio = engine.generate(phrase)
        elapsed = (time.perf_counter() - started) * 1000
        samples = np.asarray(audio.samples)
        assert audio.sample_rate > 0 and samples.size > 0 and np.isfinite(samples).all() and np.max(np.abs(samples)) > .001, "Silent or invalid audio"
        trials.append({"play": repeat + 1, "samples": int(samples.size), "sample_rate_hz": audio.sample_rate, "synthesis_ms": elapsed, "peak": float(np.max(np.abs(samples)))})
        if repeat == 0:
            with wave.open(str(OUT / f"{language}-speech.wav"), "wb") as wav:
                wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(audio.sample_rate)
                wav.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    report = {"language": language, "passed": True, "phrase": phrase, "model_sha256": digest(model), "tokens_sha256": digest(tokens), "trials": trials}
    (OUT / f"{language}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=LANGUAGES)
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.language:
        check_language(args.language)
        return
    rows = []
    for language in LANGUAGES:
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--language", language], capture_output=True)
        (OUT / f"{language}.log").write_bytes(result.stdout + result.stderr)
        if result.returncode == 0:
            rows.append(json.loads((OUT / f"{language}.json").read_text(encoding="utf-8")))
        else:
            rows.append({"language": language, "passed": False, "returncode": result.returncode, "log": f"{language}.log"})
        print(f"{language}: {'PASS' if rows[-1]['passed'] else 'FAILED'}", flush=True)
    report = {"scope": "Fresh Windows desktop synthesis, two syntheses per language; no phone or listening-quality certification", "languages": rows}
    (OUT / "all-language-tts-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if not all(row["passed"] for row in rows):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
