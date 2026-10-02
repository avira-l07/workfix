"""Compare the shipped VITS voice at 1/2 threads on fixed Tamil/Telugu/Odia phrases.

This is desktop synthesis only, not Android latency or intelligibility.
"""

import argparse
import hashlib
import json
import statistics
import time
from pathlib import Path

import sherpa_onnx
import validate_stt


ROOT = Path(__file__).resolve().parents[1]
TEXT = {
    "ta": "தயவுசெய்து தண்ணீர் மற்றும் மருத்துவ உதவியை அனுப்புங்கள்.",
    "te": "దయచేసి నీరు మరియు వైద్య సహాయం పంపండి.",
    "or": "ଦୟାକରି ତୁରନ୍ତ ଏକ ଉଦ୍ଧାରକାରୀ ଦଳ ପଠାନ୍ତୁ।",
}


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threads", type=int, choices=(1, 2), required=True)
    args = parser.parse_args()
    report = {"threads": args.threads, "scope": "Windows desktop synthesis only", "languages": {}}
    for lang, text in TEXT.items():
        model = ROOT / f"models/bundled/{lang}/tts/model.onnx"
        tokens = ROOT / f"app/src/main/assets/language_packs/{lang}/tts/tokens.txt"
        manifest = json.loads((ROOT / f"app/src/main/assets/language_packs/{lang}_dev_manifest.json").read_text(encoding="utf-8"))
        if digest(model) != manifest["ttsModel"]["checksumsSha256"]["model.onnx"]:
            raise RuntimeError(f"TTS model checksum mismatch: {lang}")
        if digest(tokens) != manifest["ttsModel"]["checksumsSha256"]["tokens.txt"]:
            raise RuntimeError(f"TTS token checksum mismatch: {lang}")
        started = time.perf_counter()
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model), tokens=str(tokens)),
                num_threads=args.threads,
            ),
        ))
        load_ms = (time.perf_counter() - started) * 1000
        tts.generate(text)  # match the app's load-time smoke/warm-up
        trials = []
        for _ in range(5):
            started = time.perf_counter()
            audio = tts.generate(text)
            ms = (time.perf_counter() - started) * 1000
            if len(audio.samples) == 0 or audio.sample_rate <= 0:
                raise RuntimeError(f"Empty PCM: {lang}")
            trials.append({"synthesis_ms": ms, "sample_rate_hz": audio.sample_rate,
                           "audio_ms": len(audio.samples) * 1000 / audio.sample_rate})
        durations = sorted(t["synthesis_ms"] for t in trials)
        report["languages"][lang] = {
            "model_bytes": model.stat().st_size + tokens.stat().st_size,
            "load_ms": load_ms,
            "mean_synthesis_ms": statistics.mean(durations),
            "p95_synthesis_ms_nearest_rank": durations[(95 * len(durations) + 99) // 100 - 1],
            "rtf": sum(t["synthesis_ms"] for t in trials) / sum(t["audio_ms"] for t in trials),
            "sample_rate_hz": trials[0]["sample_rate_hz"],
            "desktop_process_peak_bytes": validate_stt.peak_memory_bytes(),
            "trials": trials,
        }
        print(f"{lang}: mean {statistics.mean(durations):.0f} ms, p95 {durations[-1]:.0f} ms", flush=True)
        del tts
    result = ROOT / f"tools/stt_results/target-tts-{args.threads}threads.json"
    result.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {result}")


if __name__ == "__main__":
    main()
