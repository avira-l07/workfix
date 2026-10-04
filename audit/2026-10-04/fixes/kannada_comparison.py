"""Fresh identical-corpus Kannada comparison; no phone validation."""
import hashlib
import json
from pathlib import Path
import statistics
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
import benchmark_five_language_stt as benchmark

if '--ctc' in sys.argv:
    benchmark.REPORTS = OUT
    benchmark.benchmark('kn')
else:
    import numpy as np
    import sherpa_onnx
    import soundfile as sf
    manifest = json.loads((benchmark.DATA / 'manifest-kn-30.json').read_text(encoding='utf-8'))
    folder = ROOT / 'app/src/main/assets/language_packs/shared/stt'
    engine = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(folder / 'tiny-encoder.int8.onnx'), decoder=str(folder / 'tiny-decoder.int8.onnx'),
        tokens=str(folder / 'tiny-tokens.txt'), language='kn', task='transcribe', num_threads=4, tail_paddings=-1)
    rows = []
    for row in manifest[:30]:
        path = ROOT / row['file']
        assert benchmark.digest(path) == row['sha256']
        samples, rate = sf.read(path, dtype='float32')
        assert rate == 16000 and samples.ndim == 1
        stream = engine.create_stream()
        stream.accept_waveform(rate, np.concatenate([samples, np.zeros(1600, dtype=np.float32)]))
        start = time.perf_counter()
        engine.decode_stream(stream)
        ms = (time.perf_counter() - start) * 1000
        ref = benchmark.validate_stt.normalize(row['reference']).split()
        text = stream.result.text.strip()
        hyp = benchmark.validate_stt.normalize(text).split()
        rows.append(dict(output=text, decode_ms=ms, words=len(ref),
                         errors=benchmark.validate_stt.distance(ref, hyp), native=benchmark.script_ok('kn', text)))
        print(f'Tiny {len(rows)}/30', flush=True)
    result = dict(scope='Fresh Windows desktop, 30 clean FLEURS recordings; phone Not verified',
                  wer=sum(r['errors'] for r in rows) / sum(r['words'] for r in rows),
                  mean_ms=statistics.mean(r['decode_ms'] for r in rows),
                  native_count=sum(r['native'] for r in rows), rows=rows)
    (OUT / 'kn-tiny-30.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f"Tiny WER={result['wer']:.2%}, native={result['native_count']}/30", flush=True)
