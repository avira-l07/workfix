"""Measure desktop MMS-VITS synthesis speed for the four voice languages.

This checks model loading, nonempty PCM and synthesis throughput. It cannot
measure intelligibility or naturalness; those require independent listeners.
"""

import hashlib
import json
import statistics
import time
from pathlib import Path

import sherpa_onnx

import validate_stt


ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = ROOT / "models/bundled"
TOKENS_ROOT = ROOT / "app/src/main/assets/language_packs"
PHRASES = {
    "hi": "कृपया पानी और चिकित्सा सहायता भेजें।",
    "en": "Please send water and medical assistance.",
    "ta": "தயவுசெய்து தண்ணீர் மற்றும் மருத்துவ உதவியை அனுப்புங்கள்.",
    "te": "దయచేసి నీరు మరియు వైద్య సహాయం పంపండి.",
}


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main() -> None:
    report = {"scope": "desktop synthetic audio only; no listener ratings or Android latency", "languages": {}}
    for code, phrase in PHRASES.items():
        model = MODEL_ROOT / code / "tts/model.onnx"
        tokens = TOKENS_ROOT / code / "tts/tokens.txt"
        started = time.perf_counter()
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                    model=str(model), tokens=str(tokens), noise_scale=0, noise_scale_w=0,
                ),
                num_threads=2,
            )
        ))
        load_seconds = time.perf_counter() - started
        trials = []
        for _ in range(5):
            started = time.perf_counter()
            audio = tts.generate(phrase)
            synth_seconds = time.perf_counter() - started
            if len(audio.samples) == 0 or audio.sample_rate <= 0:
                raise RuntimeError(f"Empty TTS output for {code}")
            audio_seconds = len(audio.samples) / audio.sample_rate
            trials.append({
                "synthesis_seconds": synth_seconds,
                "audio_seconds": audio_seconds,
                "rtf": synth_seconds / audio_seconds,
                "sample_rate_hz": audio.sample_rate,
                "pcm_samples": len(audio.samples),
            })
        report["languages"][code] = {
            "phrase": phrase,
            "model_bytes": model.stat().st_size,
            "model_sha256": sha256(model),
            "tokens_bytes": tokens.stat().st_size,
            "tokens_sha256": sha256(tokens),
            "load_seconds": load_seconds,
            "mean_synthesis_seconds": statistics.mean(t["synthesis_seconds"] for t in trials),
            "median_synthesis_seconds": statistics.median(t["synthesis_seconds"] for t in trials),
            "mean_rtf": statistics.mean(t["rtf"] for t in trials),
            "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes(),
            "trials": trials,
        }
        print(f"{code}: {report['languages'][code]['mean_synthesis_seconds']:.3f}s synth, "
              f"RTF {report['languages'][code]['mean_rtf']:.3f}", flush=True)
        del tts
    destination = ROOT / "tools/stt_results/four-language-tts-desktop.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
