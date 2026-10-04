"""Fresh-process cheap-win comparison for existing Bengali/Gujarati CTC packs.

  python tools/evaluate_stt_preprocessing.py --language bn --method rms --set 30
  python tools/evaluate_stt_preprocessing.py --language gu --method energy-vad --set 100

One method per process. This never modifies app routing or model files.
"""

import argparse
import json
import math
import statistics
import time
from pathlib import Path

import benchmark_five_language_stt as corpus
import validate_stt

ROOT = corpus.ROOT


def rms_normalize(audio):
    import numpy as np

    rms = float(np.sqrt(np.mean(audio * audio)))
    if rms < 1e-5:
        return audio
    gain = min(4.0, 0.1 / rms, 0.99 / max(float(np.max(np.abs(audio))), 1e-5))
    return (audio * gain).astype(np.float32)


def energy_vad_trim(audio, sample_rate=16000):
    """Energy-only VAD proxy: 20-ms RMS frames with 200-ms edge padding."""
    import numpy as np

    frame = int(sample_rate * 0.02)
    if len(audio) < frame * 15:
        return audio
    energies = np.array([np.sqrt(np.mean(part * part))
                         for part in (audio[i:i + frame] for i in range(0, len(audio), frame))])
    threshold = max(0.002, float(np.max(energies)) * 0.05)
    active = np.flatnonzero(energies >= threshold)
    if len(active) == 0:
        return audio
    first = max(0, (int(active[0]) - 10) * frame)
    last = min(len(audio), (int(active[-1]) + 11) * frame)
    return audio[first:last] if last - first >= sample_rate // 2 else audio


def evaluate(language, method, set_size, model_dir=None, manifest_override=None, label=None,
             model_file="model.int8.onnx"):
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    manifest_path = manifest_override or (
        corpus.DATA / f"manifest-{language}-30.json" if set_size == 30
        else ROOT / f"tools/stt_models/five-language-test/manifest-{language}-100.json")
    rows = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest_override is None and len(rows) != set_size:
        raise RuntimeError(f"Need exactly {set_size} held-out recordings")
    model = (model_dir / model_file) if model_dir else corpus.MODELS / language / model_file
    tokens = (model_dir / "tokens.txt") if model_dir else corpus.MODELS / "tokens.txt"
    if not model.is_file() or not tokens.is_file():
        raise RuntimeError("Candidate model and tokenizer must both exist")
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(model), tokens=str(tokens), num_threads=4,
        decoding_method="greedy_search")
    measured = []
    for item in rows:
        path = ROOT / item["file"]
        if corpus.digest(path) != item["sha256"]:
            raise RuntimeError(f"Changed held-out audio: {path}")
        audio, rate = sf.read(path, dtype="float32")
        if rate != 16000 or audio.ndim != 1:
            raise RuntimeError(f"Expected 16-kHz mono: {path}")
        if method == "rms":
            audio = rms_normalize(audio)
        elif method == "energy-vad":
            audio = energy_vad_trim(audio)
        stream = recognizer.create_stream()
        stream.accept_waveform(rate, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
        start = time.perf_counter()
        recognizer.decode_stream(stream)
        elapsed_ms = (time.perf_counter() - start) * 1000
        output = stream.result.text.strip()
        reference = validate_stt.normalize(item["reference"])
        hypothesis = validate_stt.normalize(output)
        measured.append({"row": item["row"], "output": output, "decode_ms": elapsed_ms,
                         "audio_seconds": len(audio) / rate,
                         "word_errors": validate_stt.distance(reference.split(), hypothesis.split()),
                         "words": len(reference.split()),
                         "char_errors": validate_stt.distance(reference.replace(" ", ""),
                                                               hypothesis.replace(" ", "")),
                         "chars": len(reference.replace(" ", "")),
                         "native_script": corpus.script_ok(language, output)})
    times = sorted(row["decode_ms"] for row in measured)
    summary = {"count": len(measured),
               "wer": sum(x["word_errors"] for x in measured) / sum(x["words"] for x in measured),
               "cer": sum(x["char_errors"] for x in measured) / sum(x["chars"] for x in measured),
               "mean_decode_ms": statistics.mean(times),
               "p95_decode_ms": times[math.ceil(.95 * len(times)) - 1],
               "rtf": sum(x["decode_ms"] for x in measured) / 1000 /
                      sum(x["audio_seconds"] for x in measured),
               "native_script_count": sum(x["native_script"] for x in measured),
               "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes(),
               "model_and_tokens_bytes": model.stat().st_size + tokens.stat().st_size}
    output = ROOT / f"tools/stt_results/{label or 'cheap'}-{language}-{method}-{len(rows)}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"language": language, "method": method,
                                  "set_size": len(rows), "manifest": str(manifest_path),
                                  "summary": summary,
                                  "model_sha256": corpus.digest(model), "rows": measured},
                                 ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{language} {method} {set_size}: WER={summary['wer']:.2%} CER={summary['cer']:.2%} "
          f"native={summary['native_script_count']}/{len(rows)}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=("ta", "te", "bn", "gu"), required=True)
    parser.add_argument("--method", choices=("baseline", "rms", "energy-vad"), required=True)
    parser.add_argument("--set", type=int, choices=(30, 100), default=30)
    parser.add_argument("--model-dir", type=Path)
    parser.add_argument("--model-file", choices=("model.int8.onnx", "model.fp32.onnx"), default="model.int8.onnx")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--label", help="Unique result prefix; never overwrite the baseline")
    args = parser.parse_args()
    evaluate(args.language, args.method, args.set, args.model_dir, args.manifest, args.label, args.model_file)
