"""
Phase 1 Baseline Benchmark — iTantra STT
Tests Whisper Tiny int8 (currently deployed) on desktop with sherpa-onnx 1.13.8.
Tests Hindi and English utterances. Reports actual transcribed text and inference time.
"""
import time
import numpy as np
import sherpa_onnx
import os, sys

# ── Model path: pull from device first or point to local copy ──────────────
STT_DIR = os.path.join(os.path.dirname(__file__), "stt_models", "tiny")
ENCODER = os.path.join(STT_DIR, "tiny-encoder.int8.onnx")
DECODER = os.path.join(STT_DIR, "tiny-decoder.int8.onnx")
TOKENS  = os.path.join(STT_DIR, "tiny-tokens.txt")

def check_models():
    for f in [ENCODER, DECODER, TOKENS]:
        if not os.path.exists(f):
            print(f"MISSING: {f}")
            return False
        size_mb = os.path.getsize(f) / 1024 / 1024
        print(f"  OK {os.path.basename(f):40s} {size_mb:.1f} MB")
    return True

def make_recognizer(language: str, task: str = "transcribe"):
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=ENCODER,
        decoder=DECODER,
        tokens=TOKENS,
        language=language,
        task=task,
        num_threads=4,
        debug=False,
    )

def silence(duration_s: float, sr: int = 16000) -> np.ndarray:
    return np.zeros(int(sr * duration_s), dtype=np.float32)

def run_benchmark():
    print("=" * 65)
    print("iTantra Phase 1 STT Baseline — Whisper Tiny INT8")
    print("=" * 65)

    print("\nChecking model files:")
    if not check_models():
        print("\n[BLOCKER] Model files not found at:", STT_DIR)
        print("Pull them from device with:")
        print("  adb -s 7229d2bb shell run-as com.example.itantra ls /data/user/0/com.example.itantra/files/language_packs/shared/stt/")
        print("  adb -s 7229d2bb exec-out run-as com.example.itantra cat /data/user/0/com.example.itantra/files/language_packs/shared/stt/tiny-encoder.int8.onnx > stt_models/tiny/tiny-encoder.int8.onnx")
        sys.exit(1)

    print("\nLoading Hindi recognizer (language='hi', task='transcribe')...")
    t0 = time.perf_counter()
    rec_hi = make_recognizer("hi", "transcribe")
    load_time_hi = time.perf_counter() - t0
    print(f"  Loaded in {load_time_hi:.2f}s")

    print("\nLoading English recognizer (language='en', task='transcribe')...")
    t0 = time.perf_counter()
    rec_en = make_recognizer("en", "transcribe")
    load_time_en = time.perf_counter() - t0
    print(f"  Loaded in {load_time_en:.2f}s")

    # Test cases: (description, audio, lang)
    test_cases = []

    audio_dir = os.path.join(os.path.dirname(__file__), "test_audio")
    if os.path.isdir(audio_dir):
        import wave
        for wav_file in sorted(os.listdir(audio_dir)):
            if not wav_file.endswith(".wav"):
                continue
            wav_path = os.path.join(audio_dir, wav_file)
            try:
                with wave.open(wav_path) as wf:
                    sr = wf.getframerate()
                    n_frames = wf.getnframes()
                    raw = wf.readframes(n_frames)
                    audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
                    if sr != 16000:
                        print(f"  SKIP {wav_file}: sample rate {sr} != 16000")
                        continue
                    lang = "hi" if "_hi" in wav_file or wav_file.startswith("hi_") else "en"
                    test_cases.append((wav_file, audio, lang))
            except Exception as e:
                print(f"  SKIP {wav_file}: {e}")
    else:
        print(f"\n[INFO] No test_audio/ dir found. Using silence probes only.")
        test_cases = [
            ("synthetic_silence_2s_hi", silence(2.0), "hi"),
            ("synthetic_silence_2s_en", silence(2.0), "en"),
        ]

    print(f"\n{'─'*80}")
    print(f"{'File':<35} {'Lang':>4} {'Output':<40}")
    print(f"{'─'*80}")
    results = []
    for desc, audio, lang in test_cases:
        rec = rec_hi if lang == "hi" else rec_en
        padded = np.concatenate([audio, np.zeros(1600, dtype=np.float32)])
        t0 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(16000, padded)
        sherpa_onnx.decode_stream(rec, stream)
        elapsed_ms = (time.perf_counter() - t0) * 1000
        text = stream.result.text.strip()
        has_devanagari = any('\u0900' <= c <= '\u097F' for c in text)
        flag = "✓ Devanagari" if (lang == "hi" and has_devanagari) else ("✓ Latin OK" if lang == "en" else "✗ NO Devanagari")
        print(f"{desc:<35} {lang:>4} {repr(text):<40} {elapsed_ms:>6.0f}ms  {flag}")
        results.append({"file": desc, "lang": lang, "text": text, "has_devanagari": has_devanagari, "ms": elapsed_ms})

    print(f"{'─'*80}")

    hi_results = [r for r in results if r["lang"] == "hi" and "silence" not in r["file"]]
    if hi_results:
        devanagari_rate = sum(1 for r in hi_results if r["has_devanagari"]) / len(hi_results) * 100
        avg_ms = sum(r["ms"] for r in hi_results) / len(hi_results)
        print(f"\nHindi Devanagari output rate: {devanagari_rate:.0f}% ({len(hi_results)} utterances)")
        print(f"Average inference time: {avg_ms:.0f}ms")
        if devanagari_rate < 80:
            print("⚠ VERDICT: Whisper Tiny fails Devanagari output criterion (<80%). Replace with Whisper Small.")
        else:
            print("✓ VERDICT: Whisper Tiny Devanagari output acceptable.")
    else:
        print("\n[NOTE] No real Hindi audio files tested. Place 16kHz mono WAV files in tools/test_audio/")
        print("       Files with '_hi' in name are treated as Hindi.")
        print("       Suggested source: Mozilla Common Voice Hindi corpus (CC0 licensed).")

    print("\n[PHASE 1 COMPLETE]")

if __name__ == "__main__":
    run_benchmark()
