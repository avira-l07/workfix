"""Stage the validated Hindi model in APK assets; verify hashes on both sides."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'tools/stt_models/hindi-small'
report = json.loads((ROOT / 'tools/stt_results/hindi-small-final.json').read_text(encoding='utf-8'))
assert report['summaries']['hi']['count'] >= 30
assert report['summaries']['hi']['wer'] <= 0.35
assert report['summaries']['hi']['rtf'] <= 3
assert all(row['split'] == 'test' for row in report['rows'])
inventory = json.loads((source / 'inventory.json').read_text())
assert inventory['revision'] == '1f6243ec6dffc4de2db84fe1446576d1ff52c252'
assert report['model_sha256'] == {entry['file']: entry['sha256'] for entry in inventory['files']}
destination = ROOT / 'app/src/main/assets/language_packs/shared/stt-hi-v1'
destination.mkdir(parents=True, exist_ok=True)
for entry in inventory['files']:
    src = source / entry['file']
    with src.open('rb') as file:
        assert hashlib.file_digest(file, 'sha256').hexdigest() == entry['sha256']
    dst = destination / entry['file']
    shutil.copy2(src, dst)
    with dst.open('rb') as file:
        assert hashlib.file_digest(file, 'sha256').hexdigest() == entry['sha256']
(destination / 'model-provenance.json').write_text(json.dumps({**inventory,
    'source_model': 'vasista22/whisper-hindi-small', 'license': 'Apache-2.0',
    'language': 'hi', 'task': 'transcribe', 'validated_report': 'tools/stt_results/hindi-small-test.json'}, indent=2))
print('Verified and staged Hindi model assets:', destination)
