"""
Phase 2 Comparison Benchmark — iTantra STT
Compares Whisper Tiny INT8 (current) vs Whisper Small INT8 (candidate replacement).
Runs both models on identical audio under identical decoding conditions.
Reports: output text, Devanagari output rate, inference time.

Acceptance criteria:
  - Hindi Devanagari output rate >= 80% for Small (vs current Tiny baseline)
  - Inference time on desktop <= 3x realtime (audio duration / inference time)
  - No silent degradation on English utterances

Usage:
  1. Place 16kHz mono WAV files in tools/test_audio/
     - Hindi files: name must contain '_hi' or start with 'hi_'
     - English files: anything else
  2. Run: python tools/phase2_comparison_benchmark.py

Test audio sources (use appropriately licensed recordings):
  - Mozilla Common Voice Hindi: https://commonvoice.mozilla.org/en/datasets (CC0)
  - AIR-CMLT (Indic speech): https://github.com/AI4Bharat/MUCS2021 (check license)
  - Record your own using Audacity at 16kHz mono
"""
import time
import wave
import os
import sys
import json
import numpy as np

try:
    import sherpa_onnx
except ImportError:
    print("ERROR: sherpa_onnx not installed. Run: pip install sherpa-onnx")
    sys.exit(1)

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
AUDIO_DIR = os.path.join(SCRIPT_DIR, "test_audio")

MODELS = {
    "tiny": {
        "encoder": os.path.join(SCRIPT_DIR, "stt_models", "tiny", "tiny-encoder.int8.onnx"),
        "decoder": os.path.join(SCRIPT_DIR, "stt_models", "tiny", "tiny-decoder.int8.onnx"),
        "tokens":  os.path.join(SCRIPT_DIR, "stt_models", "tiny", "tiny-tokens.txt"),
        "label": "Whisper Tiny INT8 (current)",
    },
    "small": {
        "encoder": os.path.join(SCRIPT_DIR, "stt_models", "small", "small-encoder.int8.onnx"),
        "decoder": os.path.join(SCRIPT_DIR, "stt_models", "small", "small-decoder.int8.onnx"),
        "tokens":  os.path.join(SCRIPT_DIR, "stt_models", "small", "small-tokens.txt"),
        "label": "Whisper Small INT8 (candidate)",
    },
}

def check_model(key: str) -> bool:
    m = MODELS[key]
    ok = True
    for field in ["encoder", "decoder", "tokens"]:
        f = m[field]
        if os.path.exists(f):
            size_mb = os.path.getsize(f) / 1024 / 1024
            print(f"    ✓ {os.path.basename(f):45s} {size_mb:.1f} MB")
        else:
            print(f"    ✗ MISSING: {f}")
            ok = False
    return ok

def load_recognizer(key: str, language: str, task: str = "transcribe"):
    m = MODELS[key]
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=m["encoder"],
        decoder=m["decoder"],
        tokens=m["tokens"],
        language=language,
        task=task,
        num_threads=4,
        debug=False,
    )

def load_wav(path: str):
    with wave.open(path) as wf:
        sr = wf.getframerate()
        nch = wf.getnchannels()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)
        audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    return audio, sr, nch

def infer(rec, audio: np.ndarray, sr: int) -> tuple[str, float]:
    padded = np.concatenate([audio, np.zeros(1600, dtype=np.float32)])  # 100ms silence pad
    t0 = time.perf_counter()
    stream = rec.create_stream()
    stream.accept_waveform(sr, padded)
    sherpa_onnx.decode_stream(rec, stream)
    elapsed_ms = (time.perf_counter() - t0) * 1000
    return stream.result.text.strip(), elapsed_ms

def has_devanagari(text: str) -> bool:
    return any('\u0900' <= c <= '\u097F' for c in text)

def rtf(audio_duration_s: float, infer_ms: float) -> float:
    return infer_ms / (audio_duration_s * 1000)

def run():
    print("=" * 72)
    print("iTantra STT Phase 2 Comparison — Tiny INT8 vs Small INT8")
    print("=" * 72)

    # ── Check model availability ──────────────────────────────────────────
    tiny_ok = small_ok = True
    for key in ["tiny", "small"]:
        print(f"\n{MODELS[key]['label']}:")
        if not check_model(key):
            if key == "tiny":
                tiny_ok = False
                print("  [BLOCKER] Tiny model missing. Pull from device:")
                print("    adb -s 7229d2bb exec-out 'run-as com.example.itantra cat /data/user/0/com.example.itantra/files/language_packs/shared/stt/tiny-encoder.int8.onnx' > tools/stt_models/tiny/tiny-encoder.int8.onnx")
            else:
                small_ok = False
                print("  [BLOCKER] Small model missing. Extract from downloaded archive:")
                print("    cd tools/stt_models && tar xvf sherpa-onnx-whisper-small.tar.bz2")
                print("    cp sherpa-onnx-whisper-small/small-*.int8.onnx small/")
                print("    cp sherpa-onnx-whisper-small/small-tokens.txt small/")

    if not tiny_ok or not small_ok:
        print("\n[BLOCKED] Cannot proceed — fix missing models above and re-run.")
        sys.exit(1)

    # ── Load audio ────────────────────────────────────────────────────────
    test_cases = []
    if os.path.isdir(AUDIO_DIR):
        for fname in sorted(os.listdir(AUDIO_DIR)):
            if not fname.endswith(".wav"):
                continue
            path = os.path.join(AUDIO_DIR, fname)
            try:
                audio, sr, nch = load_wav(path)
                if sr != 16000:
                    print(f"\nSKIP {fname}: sample rate {sr} (need 16000)")
                    continue
                lang = "hi" if "_hi" in fname or fname.startswith("hi_") else "en"
                test_cases.append({"file": fname, "audio": audio, "lang": lang, "duration_s": len(audio) / 16000})
            except Exception as e:
                print(f"\nSKIP {fname}: {e}")
    else:
        print(f"\n[INFO] No test_audio/ directory at {AUDIO_DIR}")
        print("       Place 16kHz mono WAV files there to test real accuracy.")
        print("       Pipeline test only (silence probes).")
        silence = np.zeros(32000, dtype=np.float32)
        test_cases = [
            {"file": "silence_2s_hi.wav", "audio": silence, "lang": "hi", "duration_s": 2.0},
            {"file": "silence_2s_en.wav", "audio": silence, "lang": "en", "duration_s": 2.0},
        ]

    if not test_cases:
        print("[BLOCKED] No audio files found in", AUDIO_DIR)
        sys.exit(1)

    # ── Load recognizers ─────────────────────────────────────────────────
    print("\nLoading recognizers...")
    recs = {}
    for key in ["tiny", "small"]:
        print(f"  {MODELS[key]['label']}...")
        t0 = time.perf_counter()
        recs[(key, "hi")] = load_recognizer(key, "hi", "transcribe")
        recs[(key, "en")] = load_recognizer(key, "en", "transcribe")
        print(f"    loaded in {time.perf_counter()-t0:.1f}s")

    # ── Benchmark ─────────────────────────────────────────────────────────
    results = {key: [] for key in ["tiny", "small"]}

    print(f"\n{'─'*72}")
    print(f"{'File':<30} {'Lang':>4}  {'Tiny output':<30} {'Small output':<30}")
    print(f"{'─'*72}")

    for tc in test_cases:
        fname = tc["file"]
        audio = tc["audio"]
        lang = tc["lang"]
        dur  = tc["duration_s"]

        row_tiny, row_small = {}, {}
        for key, row in [("tiny", row_tiny), ("small", row_small)]:
            rec = recs[(key, lang)]
            text, ms = infer(rec, audio, 16000)
            row["file"] = fname
            row["lang"] = lang
            row["text"] = text
            row["has_devanagari"] = has_devanagari(text)
            row["ms"] = ms
            row["rtf"] = rtf(dur, ms)
            results[key].append(row)

        # Devanagari flag
        tiny_flag = "✓" if (lang == "en" or row_tiny["has_devanagari"]) else "✗"
        small_flag = "✓" if (lang == "en" or row_small["has_devanagari"]) else "✗"
        print(f"{fname:<30} {lang:>4}  {tiny_flag} {repr(row_tiny['text'])[:27]:<28} {small_flag} {repr(row_small['text'])[:27]:<28}")

    print(f"{'─'*72}")

    # ── Summary ──────────────────────────────────────────────────────────
    print(f"\n{'MODEL':<30} {'Hindi Dev%':>10} {'Avg ms':>8} {'Avg RTF':>8}")
    print(f"{'─'*60}")
    verdict = {}
    for key in ["tiny", "small"]:
        hi = [r for r in results[key] if r["lang"] == "hi" and "silence" not in r["file"]]
        all_r = results[key]
        dev_rate = (sum(1 for r in hi if r["has_devanagari"]) / len(hi) * 100) if hi else float("nan")
        avg_ms = sum(r["ms"] for r in all_r) / len(all_r) if all_r else 0
        avg_rtf = sum(r["rtf"] for r in all_r) / len(all_r) if all_r else 0
        label = MODELS[key]["label"]
        verdict[key] = {"dev_rate": dev_rate, "avg_ms": avg_ms, "avg_rtf": avg_rtf}
        print(f"{label:<30} {dev_rate:>9.0f}% {avg_ms:>8.0f} {avg_rtf:>8.2f}x")

    print(f"\n{'─'*60}")
    print("ACCEPTANCE CRITERIA:")
    small_pass = verdict["small"]["dev_rate"] >= 80
    rtf_pass = verdict["small"]["avg_rtf"] <= 3.0
    tiny_dev = verdict["tiny"]["dev_rate"]
    small_dev = verdict["small"]["dev_rate"]

    print(f"  Hindi Devanagari >= 80%: {'PASS' if small_pass else 'FAIL'} (Small={small_dev:.0f}%, Tiny={tiny_dev:.0f}%)")
    print(f"  RTF <= 3.0x on desktop:  {'PASS' if rtf_pass else 'FAIL'} (Small RTF={verdict['small']['avg_rtf']:.2f}x)")

    if small_pass and rtf_pass:
        print("\n✓ VERDICT: Whisper Small passes acceptance criteria. Proceed to Phase 3 integration.")
    elif not small_pass:
        print("\n✗ VERDICT: Whisper Small does NOT produce adequate Devanagari output.")
        print("  → Do not silently ship Small as a fix.")
        print("  → Investigate: (1) Is the language token 'hi' being set? (2) Is audio at 16kHz mono?")
        print("  → Consider: Whisper Medium, or a language-specific Hindi ASR (e.g. AI4Bharat's model).")
    else:
        print("\n⚠ VERDICT: Small passes Devanagari criterion but RTF is high. Measure on device before shipping.")

    # ── Save results ──────────────────────────────────────────────────────
    report_path = os.path.join(SCRIPT_DIR, "phase2_results.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump({"tiny": results["tiny"], "small": results["small"], "verdict": verdict}, f, ensure_ascii=False, indent=2)
    print(f"\nFull results saved to: {report_path}")
    print("[PHASE 2 COMPLETE]")

if __name__ == "__main__":
    run()
