"""Repeatable 30-trial desktop VITS latency benchmark; one fresh process per language.

    python tools/benchmark_five_language_tts.py --language hi

Five fixed short field phrases are synthesized six times each after warm-up.
Generated PCM and its sample rate are checked; intelligibility needs a listener.
"""

import argparse
import hashlib
import json
import math
import statistics
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

import sherpa_onnx
import validate_stt

ROOT = Path(__file__).resolve().parents[1]
PHRASES = {
    "kn": ["ಸಹಾಯ ಬೇಕು", "ನೀರು ಕಳುಹಿಸಿ", "ರಸ್ತೆ ಮುಚ್ಚಲಾಗಿದೆ", "ನಾವು ಸುರಕ್ಷಿತವಾಗಿದ್ದೇವೆ", "ವೈದ್ಯರನ್ನು ಕರೆಸಿ"],
    "ml": ["സഹായം വേണം", "വെള്ളം അയയ്ക്കൂ", "റോഡ് അടച്ചിരിക്കുന്നു", "ഞങ്ങൾ സുരക്ഷിതരാണ്", "ഡോക്ടറെ വിളിക്കൂ"],
    "mr": ["मदत हवी आहे", "पाणी पाठवा", "रस्ता बंद आहे", "आम्ही सुरक्षित आहोत", "डॉक्टरांना बोलवा"],
    "hi": ["मदद चाहिए", "पानी भेजिए", "रास्ता बंद है", "हम सुरक्षित हैं", "डॉक्टर को बुलाइए"],
    "en": ["We need help", "Send clean water", "The road is blocked", "We are safe", "Call a doctor"],
    "ta": ["உதவி தேவை", "தண்ணீர் அனுப்புங்கள்", "சாலை மூடப்பட்டுள்ளது", "நாங்கள் பாதுகாப்பாக இருக்கிறோம்", "மருத்துவரை அழைக்கவும்"],
    "te": ["సహాయం కావాలి", "నీరు పంపండి", "రహదారి మూసివేశారు", "మేము సురక్షితంగా ఉన్నాము", "వైద్యుడిని పిలవండి"],
    "or": ["ସାହାଯ୍ୟ ଆବଶ୍ୟକ", "ପାଣି ପଠାନ୍ତୁ", "ରାସ୍ତା ବନ୍ଦ ଅଛି", "ଆମେ ସୁରକ୍ଷିତ ଅଛୁ", "ଡାକ୍ତରଙ୍କୁ ଡାକନ୍ତୁ"],
    "bn": ["সাহায্য দরকার", "পানি পাঠান", "রাস্তা বন্ধ", "আমরা নিরাপদে আছি", "ডাক্তার ডাকুন"],
    "gu": ["મદદ જોઈએ છે", "પાણી મોકલો", "રસ્તો બંધ છે", "અમે સુરક્ષિત છીએ", "ડોક્ટરને બોલાવો"],
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", required=True, choices=PHRASES)
    args = parser.parse_args()
    lang = args.language
    if lang == "mr":
        # Marathi now uses phonemes and a downloadable frontend, not MMS graphemes.
        subprocess.run([sys.executable, str(ROOT / "tools/evaluate_marathi_piper.py"),
                        "benchmark", "--voice", "piper"], check=True)
        return
    model = ROOT / f"models/bundled/{lang}/tts/model.onnx"
    tokens = ROOT / f"app/src/main/assets/language_packs/{lang}/tts/tokens.txt"
    manifest = json.loads((ROOT / f"app/src/main/assets/language_packs/{lang}_dev_manifest.json")
                          .read_text(encoding="utf-8"))
    checksums = manifest["ttsModel"]["checksumsSha256"]
    if digest(model) != checksums["model.onnx"] or digest(tokens) != checksums["tokens.txt"]:
        raise RuntimeError(f"Pinned TTS file checksum mismatch: {lang}")
    vocabulary = {line.rsplit(" ", 1)[0] for line in tokens.read_text(encoding="utf-8").splitlines()}
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model), tokens=str(tokens)),
            num_threads=1)))
    tts.generate(PHRASES[lang][0])  # match app load-time warm-up
    rows = []
    for repetition in range(6):
        for phrase in PHRASES[lang]:
            start = time.perf_counter()
            audio = tts.generate(phrase)
            elapsed_ms = (time.perf_counter() - start) * 1000
            if audio.sample_rate <= 0 or len(audio.samples) == 0 or max(map(abs, audio.samples)) < .001:
                raise RuntimeError(f"Silent or invalid PCM: {lang} {phrase}")
            rows.append({"phrase": phrase, "repetition": repetition, "synthesis_ms": elapsed_ms,
                         "audio_ms": len(audio.samples) * 1000 / audio.sample_rate,
                         "sample_rate_hz": audio.sample_rate,
                         "letters_or_marks_absent_from_token_file": [f"U+{ord(c):04X}" for c in
                             sorted(set(phrase)) if unicodedata.category(c)[0] in "LM" and c not in vocabulary]})
    sorted_ms = sorted(row["synthesis_ms"] for row in rows)
    summary = {"count": 30, "mean_synthesis_ms": statistics.mean(sorted_ms),
               "p95_synthesis_ms_nearest_rank": sorted_ms[math.ceil(.95 * len(sorted_ms)) - 1],
               "rtf": sum(r["synthesis_ms"] for r in rows) / sum(r["audio_ms"] for r in rows),
               "sample_rate_hz": rows[0]["sample_rate_hz"],
               "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes()}
    result = ROOT / f"tools/stt_results/five-language-{lang}-tts-30.json"
    result.write_text(json.dumps({"scope": "Windows desktop, fixed short field phrases",
                                  "model_bytes": model.stat().st_size + tokens.stat().st_size,
                                  "model_sha256": digest(model), "summary": summary, "rows": rows},
                                 ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{lang}: 30 samples, mean={summary['mean_synthesis_ms']:.1f}ms, "
          f"p95={summary['p95_synthesis_ms_nearest_rank']:.1f}ms", flush=True)


if __name__ == "__main__":
    main()
