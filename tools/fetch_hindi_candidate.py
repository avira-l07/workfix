"""Fetch a Hindi-only candidate and held-out FLEURS test data. No device access."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import requests

CACHE = Path(__file__).resolve().parent / 'stt_models'
REPO = 'ippocode/indic-asr-onnx'
REVISION = '1f6243ec6dffc4de2db84fe1446576d1ff52c252'

def download(url, path, size, sha=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or path.stat().st_size != size:
        with requests.get(url, stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            with path.open('wb') as out:
                for chunk in response.iter_content(1024 * 1024):
                    out.write(chunk)
    assert path.stat().st_size == size
    with path.open('rb') as file:
        actual = hashlib.file_digest(file, 'sha256').hexdigest()
    if sha:
        assert actual == sha
    print(path.name, size, actual, flush=True)
    return actual

def main():
    revision = REVISION
    r = requests.get(f'https://huggingface.co/api/models/{REPO}/tree/{revision}/models/whisper-small-hi', timeout=60)
    r.raise_for_status()
    inventory = r.json()
    target = CACHE / 'hindi-small'
    target.mkdir(parents=True, exist_ok=True)
    def model(entry):
        path = target / Path(entry['path']).name
        sha = download(f'https://huggingface.co/{REPO}/resolve/{revision}/{entry["path"]}',
                       path, entry['size'], entry.get('lfs', {}).get('oid'))
        if 'lfs' not in entry:
            content = path.read_bytes()
            assert hashlib.sha1(f'blob {len(content)}\0'.encode() + content).hexdigest() == entry['oid']
        return {'file': path.name, 'bytes': entry['size'], 'sha256': sha}
    def dataset():
        r = requests.get('https://datasets-server.huggingface.co/parquet?dataset=google%2Ffleurs', timeout=60)
        r.raise_for_status()
        entry = next(x for x in r.json()['parquet_files'] if x['config']=='hi_in' and x['split']=='test')
        sha = download(entry['url'], CACHE / 'fleurs-hi-test.parquet', entry['size'])
        (CACHE / 'fleurs-test-provenance.json').write_text(json.dumps({**entry, 'sha256': sha}, indent=2))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        audio = pool.submit(dataset)
        files = list(pool.map(model, inventory))
        audio.result()
    (target / 'inventory.json').write_text(json.dumps({'repo': REPO, 'revision': revision, 'files': files}, indent=2))

if __name__ == '__main__':
    main()
