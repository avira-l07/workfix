from pathlib import Path
import json, time
import numpy as np
import sherpa_onnx

root = Path(__file__).resolve().parents[3]
folder = root / 'tools/stt_models/indicconformer-candidates'
engine = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
    model=str(folder / 'kn/model.int8.onnx'), tokens=str(folder / 'tokens.txt'), num_threads=4)
rows = []
for seconds in (1, 2, 5):
    stream = engine.create_stream()
    stream.accept_waveform(16000, np.zeros(seconds * 16000 + 1600, dtype=np.float32))
    start = time.perf_counter()
    engine.decode_stream(stream)
    rows.append({'seconds': seconds, 'output': stream.result.text.strip(),
                 'decode_ms': (time.perf_counter() - start) * 1000})
report = {'scope': 'Direct desktop engine, all-zero PCM only; production VAD and phone noise not tested',
          'rows': rows}
(Path(__file__).resolve().parent / 'kannada-silence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=True, indent=2))
