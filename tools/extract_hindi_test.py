"""Extract the first 30 held-out FLEURS Hindi test rows, before candidate inference."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / 'tools/stt_models'
sys.path.insert(0, str(CACHE / 'validation-deps'))
import pyarrow.parquet as pq

manifest = []
table = pq.ParquetFile(CACHE / 'fleurs-hi-test.parquet')
rows = next(table.iter_batches(batch_size=30)).to_pylist()
assert len(rows) == 30
for i, row in enumerate(rows):
    path = CACHE / f'fleurs-hi-test-{i}.wav'
    path.write_bytes(row['audio']['bytes'])
    manifest.append({'language':'hi', 'row':i, 'id':row['id'],
                     'file':str(path.relative_to(ROOT)), 'reference':row['transcription'],
                     'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                     'source':'https://huggingface.co/datasets/google/fleurs',
                     'config':'hi_in', 'split':'test', 'license':'CC-BY-4.0',
                     'speaker_id':row.get('speaker_id'), 'gender':row.get('gender')})
(CACHE / 'fleurs-hi-test-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print('Extracted 30 held-out Hindi recordings; no sample selection by hypothesis.')
