"""Evaluate bounded Malayalam CTC inference; never modifies Android model selection.

  python tools/evaluate_malayalam_chunking.py --self-test
  python tools/evaluate_malayalam_chunking.py

Fixed before evaluation: <=12 s chunks, quietest 100 ms window in the last 4 s.
No overlap, dropped samples, reference-driven boundaries or text substitutions.
"""

import argparse
import json
import math
import statistics
import time

import benchmark_five_language_stt as corpus
import validate_stt


def ranges(audio, rate=16000, maximum_seconds=12):
    import numpy as np

    maximum, search, window, step = maximum_seconds * rate, (maximum_seconds - 4) * rate, rate // 10, rate // 50
    start = 0
    while len(audio) - start > maximum:
        candidates = range(start + search, start + maximum - window // 2 + 1, step)
        end = min(candidates, key=lambda c: float(np.mean(audio[c-window//2:c+window//2] ** 2)))
        yield start, end
        start = end
    if start < len(audio):
        yield start, len(audio)


def self_test():
    import numpy as np

    for seconds in (0, .5, 12, 12.01, 47.64):
        audio = np.ones(int(seconds * 16000), dtype=np.float32)
        parts = list(ranges(audio))
        assert sum(b-a for a,b in parts) == len(audio)
        assert all(0 < b-a <= 12*16000 for a,b in parts)
        assert all(parts[i][1] == parts[i+1][0] for i in range(len(parts)-1))
        if parts:
            assert parts[0][0] == 0 and parts[-1][1] == len(audio)
    audio = np.ones(25*16000, dtype=np.float32)
    audio[10*16000:10*16000+1600] = 0
    assert 10*16000 <= list(ranges(audio))[0][1] <= 10*16000+1600
    print("Chunk boundaries: complete coverage, <=12 s, quiet pause selected")


def benchmark(set_size=30, mode="quiet12"):
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    manifest = (corpus.DATA / "manifest-ml-30.json" if set_size == 30 else
                corpus.ROOT / "tools/stt_models/five-language-test/manifest-ml-100.json")
    items = json.loads(manifest.read_text(encoding="utf-8"))
    model, tokens = corpus.MODELS / "ml/model.int8.onnx", corpus.MODELS / "tokens.txt"
    assert model.stat().st_size == corpus.NEW_MODELS["ml"][0]
    assert corpus.digest(model) == corpus.NEW_MODELS["ml"][1]
    assert corpus.digest(tokens) == "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
    assert len(items) == set_size
    engine = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(model), tokens=str(tokens), num_threads=4, decoding_method="greedy_search")
    rows = []
    for item in items:
        path = corpus.ROOT / item["file"]
        assert corpus.digest(path) == item["sha256"]
        audio, rate = sf.read(path, dtype="float32")
        assert rate == 16000 and audio.ndim == 1
        parts = ([(0, len(audio))] if mode == "whole" else
                 list(ranges(audio, rate, maximum_seconds=10 if mode == "context10" else 12)))
        outputs, times = [], []
        for first, last in parts:
            decode_first = max(0, first-rate) if mode == "context10" else first
            decode_last = min(len(audio), last+rate) if mode == "context10" else last
            stream = engine.create_stream()
            stream.accept_waveform(rate, np.concatenate([audio[decode_first:decode_last], np.zeros(1600, dtype=np.float32)]))
            start = time.perf_counter()
            engine.decode_stream(stream)
            times.append((time.perf_counter() - start) * 1000)
            if mode == "context10":
                result = stream.result
                assert len(result.tokens) == len(result.timestamps)
                outputs.append("".join(token for token, timestamp in zip(result.tokens, result.timestamps)
                    if first <= decode_first + round(timestamp*rate) < last))
            else:
                outputs.append(stream.result.text.strip())
            del stream
        output = ("" if mode == "context10" else " ").join(text for text in outputs if text).strip()
        ref, hyp = validate_stt.normalize(item["reference"]), validate_stt.normalize(output)
        rows.append({"row": item["row"], "output": output, "decode_ms": sum(times),
                     "audio_seconds": len(audio)/rate, "chunk_ranges_samples": parts,
                     "chunk_decode_ms": times,
                     "word_errors": validate_stt.distance(ref.split(), hyp.split()), "words": len(ref.split()),
                     "char_errors": validate_stt.distance(ref.replace(" ", ""), hyp.replace(" ", "")),
                     "chars": len(ref.replace(" ", "")), "native_script": corpus.script_ok("ml", output)})
        print(f"ml {item['row']+1}/{set_size}", flush=True)
    times = sorted(r["decode_ms"] for r in rows)
    summary = {"count": len(rows),
        "wer": sum(r["word_errors"] for r in rows)/sum(r["words"] for r in rows),
        "cer": sum(r["char_errors"] for r in rows)/sum(r["chars"] for r in rows),
        "decode_ms_mean": statistics.mean(times),
        "decode_ms_p95_nearest_rank": times[math.ceil(.95*len(times))-1],
        "rtf": sum(times)/1000/sum(r["audio_seconds"] for r in rows),
        "native_script_count": sum(r["native_script"] for r in rows),
        "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes()}
    result = corpus.REPORTS / f"ml-indicconformer-{mode}-{set_size}.json"
    result.write_text(json.dumps({"scope": "Windows desktop, FLEURS; paired for identical set sizes",
        "manifest": str(manifest),
        "model_sha256": corpus.digest(model), "model_bytes": model.stat().st_size+tokens.stat().st_size,
        "chunking": {"quiet12": "<=12 s, quietest 100 ms in last 4 s; no overlap",
                     "context10": "<=10 s core with 1 s acoustic context each side; token timestamps select core",
                     "whole": "whole utterance"}[mode],
        "summary": summary, "rows": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--set", type=int, choices=(30, 100), default=30)
    parser.add_argument("--mode", choices=("quiet12", "context10", "whole"), default="quiet12")
    args = parser.parse_args()
    self_test() if args.self_test else benchmark(args.set, args.mode)
