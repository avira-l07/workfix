"""Offline, reproducible Marathi MMS/Piper comparison. This does not rate intelligibility.

Prepare requires already downloaded, pinned Piper files (see the repair report).
    python tools/evaluate_marathi_piper.py prepare
    python tools/evaluate_marathi_piper.py benchmark --voice piper
    python tools/evaluate_marathi_piper.py benchmark --voice mms
"""

import argparse
import hashlib
import json
import math
import statistics
import time
import unicodedata
from pathlib import Path

import numpy as np
import onnx
import sherpa_onnx
import soundfile as sf
import validate_stt
from benchmark_five_language_tts import PHRASES

ROOT = Path(__file__).resolve().parents[1]
CANDIDATE = ROOT / "tools/stt_models/marathi-piper-candidate"
RESULTS = ROOT / "tools/stt_results/marathi-tts-repair-2026-10-04"
RAW_SHA = "e1200d474a74ebd6d1737be2c7affe56f1f9efc18915d4595d7f5c2b15cf06f4"
CONFIG_SHA = "11055302ee1e3c9902e5e96c03cbfc0a9eec29b1b06b91ef52a3c66e9873edbb"
MMS_MODEL_SHA = "046cecf7cca77680c9ca319953e777c8d0233ce78e9b55a47aa909edf87d99e8"
MMS_TOKENS_SHA = "4d968029d0754b41633cb0871cce6796a5ab3d3bc2b9b91c5721cfdf85156083"
PROBES = [
    "डॉक्टरांना बोलवा", "डक्टरांना बोलवा", "डाक्टरांना बोलवा",
    "ऑक्सिजन द्या", "अॅम्ब्युलन्स बोलवा", "ॲम्ब्युलन्स बोलवा",
    "कॅम्पमध्ये या", "कॉफी", "कफी", "ळ", "ऱ", "१२३",
]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare():
    raw = CANDIDATE / "mr_IN-google-medium.onnx"
    assert digest(raw) == RAW_SHA, "Pinned source model checksum mismatch"
    config_path = CANDIDATE / (raw.name + ".json")
    config_text = config_path.read_text(encoding="utf-8")
    # Normalize only transport line endings; the pinned JSON contents stay identical.
    assert hashlib.sha256(config_text.encode("utf-8")).hexdigest() == CONFIG_SHA, "Pinned source voice config mismatch"
    config = json.loads(config_text)
    assert config["espeak"]["voice"] == "mr" and config["phoneme_type"] == "espeak"
    assert config["num_speakers"] == 9 and config["audio"]["sample_rate"] == 22050
    model = onnx.load(raw)
    # Official sherpa-onnx Piper conversion: trained weights and graph stay unchanged.
    onnx.helper.set_model_props(model, {
        "model_type": "vits", "comment": "piper", "language": "Marathi",
        "voice": "mr", "has_espeak": "1", "n_speakers": "9", "sample_rate": "22050",
    })
    onnx.save(model, CANDIDATE / "model.onnx")
    (CANDIDATE / "tokens.txt").write_text("".join(
        f"{phoneme} {ids[0]}\n" for phoneme, ids in config["phoneme_id_map"].items()
        if len(phoneme) == 1), encoding="utf-8")
    # Sherpa's Piper frontend encodes individual Unicode phonemes and rejects
    # multi-codepoint dictionary keys; trained single-character IDs stay unchanged.
    print("Sherpa-compatible Piper model prepared; weights unchanged")


def probe_frontend():
    """Inspect the same bundled eSpeak frontend; phonemes are not listener ratings."""
    import ctypes

    lib = ctypes.CDLL(str(Path(sherpa_onnx.__file__).parent / "lib/sherpa-onnx-c-api.dll"))
    lib.espeak_Initialize.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    lib.espeak_Initialize.restype = ctypes.c_int
    lib.espeak_SetVoiceByName.argtypes = [ctypes.c_char_p]
    lib.espeak_SetVoiceByName.restype = ctypes.c_int
    lib.espeak_TextToPhonemes.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_int, ctypes.c_int]
    lib.espeak_TextToPhonemes.restype = ctypes.c_char_p
    assert lib.espeak_Initialize(2, 0, str(CANDIDATE.resolve()).encode(), 0) > 0
    assert lib.espeak_SetVoiceByName(b"mr") == 0
    vocabulary = {line.rsplit(" ", 1)[0] for line in (CANDIDATE / "tokens.txt").read_text(encoding="utf-8").splitlines()}
    rows = []
    for text in PROBES + PHRASES["mr"]:
        buffer = ctypes.create_string_buffer(text.encode("utf-8"))
        pointer = ctypes.c_void_p(ctypes.addressof(buffer))
        parts = []
        for _ in range(20):
            if not pointer.value:
                break
            result = lib.espeak_TextToPhonemes(ctypes.byref(pointer), 1, 2)
            if result:
                parts.append(result.decode("utf-8"))
        assert not pointer.value, "Incomplete frontend conversion"
        phonemes = " ".join(parts)
        missing = sorted(set(phonemes) - vocabulary)
        assert phonemes and not missing, (text, missing)
        rows.append({"text": text, "phonemes": phonemes, "missing_phonemes": missing})
    assert "ɔ" in rows[0]["phonemes"] and rows[0]["phonemes"] != rows[1]["phonemes"]
    assert rows[7]["phonemes"] != rows[8]["phonemes"]
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "phoneme-probes.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Marathi phoneme probes passed; doctor/coffee vowel distinctions retained; listener quality Not verified")


def benchmark(args):
    RESULTS.mkdir(parents=True, exist_ok=True)
    if args.voice == "piper":
        model, tokens = CANDIDATE / "model.onnx", CANDIDATE / "tokens.txt"
        data_dir = CANDIDATE / "espeak-ng-data"
        expected_rate = 22050
        spec = json.loads((ROOT / "app/src/main/assets/language_packs/mr_dev_manifest.json")
                          .read_text(encoding="utf-8"))["ttsModel"]["checksumsSha256"]
        for name, expected in spec.items():
            assert digest(CANDIDATE / name) == expected, f"Pinned Piper file mismatch: {name}"
        assert (data_dir / "mr_dict").is_file() and (data_dir / "lang/inc/mr").is_file()
        kwargs = {"data_dir": str(data_dir)}
    else:
        assert args.sid == 0, "Legacy MMS has only speaker 0"
        model = ROOT / "models/bundled/mr/tts/model.onnx"
        tokens = ROOT / "app/src/main/assets/language_packs/mr/tts/tokens.txt"
        assert digest(model) == MMS_MODEL_SHA and digest(tokens) == MMS_TOKENS_SHA
        kwargs, data_dir, expected_rate = {}, None, 16000
    vocabulary = {line.rsplit(" ", 1)[0] for line in tokens.read_text(encoding="utf-8").splitlines()}
    started = time.perf_counter()
    engine = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model), tokens=str(tokens), **kwargs),
            num_threads=args.threads, debug=args.debug)))
    load_ms = (time.perf_counter() - started) * 1000
    engine.generate(PHRASES["mr"][0], sid=args.sid)
    rows = []
    for repetition in range(6):
        for phrase in PHRASES["mr"]:
            started = time.perf_counter()
            audio = engine.generate(phrase, sid=args.sid)
            elapsed = (time.perf_counter() - started) * 1000
            samples = np.asarray(audio.samples)
            assert audio.sample_rate == expected_rate and samples.size > 0
            assert np.isfinite(samples).all() and np.max(np.abs(samples)) > .001
            missing = [f"U+{ord(c):04X}" for c in sorted(set(phrase))
                       if unicodedata.category(c)[0] in "LM" and c not in vocabulary]
            rows.append({"text": phrase, "repetition": repetition, "synthesis_ms": elapsed,
                         "audio_ms": samples.size * 1000 / audio.sample_rate,
                         "finite_non_silent": True,
                         "raw_letters_missing_from_grapheme_tokens": missing if args.voice == "mms" else None})
            if repetition == 0:
                sf.write(RESULTS / f"{args.voice}-sid{args.sid}-{len(rows)-1}.wav",
                         samples, audio.sample_rate, subtype="PCM_16")
    probes = []
    for index, text in enumerate(PROBES):
        missing = [f"U+{ord(c):04X}" for c in sorted(set(text))
                   if unicodedata.category(c)[0] in "LM" and c not in vocabulary]
        if args.voice == "mms" and missing:
            probes.append({"text": text, "status": "Rejected by current app vocabulary guard",
                           "missing": missing})
            continue
        started = time.perf_counter()
        audio = engine.generate(text, sid=args.sid)
        elapsed = (time.perf_counter()-started)*1000
        samples = np.asarray(audio.samples)
        audible_signal = bool(samples.size and np.isfinite(samples).all() and np.max(np.abs(samples)) > .001)
        sf.write(RESULTS / f"{args.voice}-sid{args.sid}-probe-{index}.wav", samples,
                 audio.sample_rate, subtype="PCM_16")
        probes.append({"text": text, "synthesis_ms": elapsed, "finite_non_silent": audible_signal,
                       "audio_samples": samples.size, "sample_rate_hz": audio.sample_rate,
                       "wav": f"{args.voice}-sid{args.sid}-probe-{index}.wav"})
    ms = sorted(r["synthesis_ms"] for r in rows)
    model_bytes = model.stat().st_size + tokens.stat().st_size
    frontend_bytes = sum(p.stat().st_size for p in data_dir.rglob("*") if p.is_file()) if data_dir else 0
    report = {
        "scope": "Windows desktop, offline synthesis, same five phrases x six repetitions",
        "voice": args.voice, "threads": args.threads, "speaker_id": args.sid,
        "model_sha256": digest(model), "tokens_sha256": digest(tokens),
        "model_and_tokens_bytes": model_bytes, "frontend_bytes": frontend_bytes,
        "speech_pack_bytes": model_bytes + frontend_bytes, "load_ms": load_ms,
        "summary": {"trials": len(rows), "sample_rate_hz": expected_rate,
                    "mean_synthesis_ms": statistics.mean(ms), "p95_synthesis_ms": ms[math.ceil(.95*len(ms))-1],
                    "rtf": sum(r["synthesis_ms"] for r in rows)/sum(r["audio_ms"] for r in rows),
                    "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes()},
        "rows": rows, "probes": probes, "human_intelligibility": "Not verified",
        "phone_validation": "Not verified", "tts_accuracy": "Not measured",
        "legacy_comparison_caveat": "Raw MMS inference bypasses the app guard and skips U+0949 in the doctor phrase" if args.voice == "mms" else None,
    }
    destination = RESULTS / f"{args.voice}-sid{args.sid}-{args.threads}threads.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"voice": args.voice, **report["summary"], "speech_pack_bytes": report["speech_pack_bytes"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("prepare")
    sub.add_parser("phonemes")
    bench = sub.add_parser("benchmark")
    bench.add_argument("--voice", required=True, choices=("piper", "mms"))
    bench.add_argument("--threads", type=int, choices=(1, 2, 4), default=1)
    bench.add_argument("--sid", type=int, choices=range(9), default=0)
    bench.add_argument("--debug", action="store_true")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare()
    elif args.command == "phonemes":
        probe_frontend()
    else:
        benchmark(args)
