"""Decode Odia TTS samples with the selected STT as a gross-error check.

This is not a listener intelligibility score: errors can come from either model.
"""

import json
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

import benchmark_odia_candidate as candidate
import validate_stt


ROOT = Path(__file__).resolve().parents[1]
TTS_REPORT = ROOT / "tools/stt_results/odia-tts-desktop.json"
OUTPUT = ROOT / "tools/stt_results/odia-tts-roundtrip.json"


def main() -> None:
    if not candidate.verified(candidate.MODEL, candidate.MODEL_BYTES, candidate.MODEL_SHA256):
        raise RuntimeError("Pinned Odia STT model missing or changed")
    if candidate.sha256(candidate.TOKENS) != candidate.TOKENS_SHA256:
        raise RuntimeError("Pinned Odia STT tokens changed")
    phrases = json.loads(TTS_REPORT.read_text(encoding="utf-8"))["phrases"]
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(candidate.MODEL), tokens=str(candidate.TOKENS),
        num_threads=4, decoding_method="greedy_search",
    )
    rows = []
    for name, item in phrases.items():
        path = ROOT / "tools/stt_results/odia-tts-samples" / f"{name}.wav"
        audio, rate = sf.read(path, dtype="float32")
        if rate != 16000 or audio.ndim != 1:
            raise RuntimeError(f"Unexpected generated audio format: {path}")
        stream = recognizer.create_stream()
        stream.accept_waveform(rate, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
        recognizer.decode_stream(stream)
        transcript = stream.result.text.strip()
        reference = validate_stt.normalize(item["text"]).split()
        output = validate_stt.normalize(transcript).split()
        rows.append({
            "sample": name, "input_text": item["text"], "stt_output": transcript,
            "word_errors": validate_stt.distance(reference, output),
            "reference_words": len(reference),
        })
    report = {
        "scope": "Gross-error proxy only. The selected STT and TTS are both fallible; no human listener scores.",
        "rows": rows,
        "aggregate_wer": sum(row["word_errors"] for row in rows)
        / sum(row["reference_words"] for row in rows),
    }
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
