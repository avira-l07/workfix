"""Desktop real-speech comparison. No device access. Requires requests, numpy, soundfile, sherpa_onnx.
Fetch: python tools/validate_stt.py fetch
Run: python tools/validate_stt.py benchmark --model tiny|small
Dataset: google/fleurs (CC-BY-4.0), first 20 validation rows per hi_in/en_us.
Acceptance fixed before inference: Hindi WER <=35% and >=25% relative reduction;
English WER <=20% and <= Tiny +3 percentage points. Desktop aggregate RTF <=3.
This small read-speech sample does not certify conversational or Android performance.
"""
import argparse
import hashlib
import json
import time
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / 'tools/stt_models'
REPORT = ROOT / 'tools/stt_results'

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def fetch():
    import requests
    model_dir = ROOT / 'app/build/whisper-small'
    model_dir.mkdir(parents=True, exist_ok=True)
    repo = 'csukuangfj/sherpa-onnx-whisper-small'
    response = requests.get(f'https://huggingface.co/api/models/{repo}/tree/main', timeout=40)
    response.raise_for_status()
    inventory = response.json()
    for entry in inventory:
        name = entry['path']
        if name not in ('small-encoder.int8.onnx', 'small-decoder.int8.onnx', 'small-tokens.txt'):
            continue
        path = model_dir / name
        size = path.stat().st_size if path.exists() else 0
        if size != entry['size']:
            headers = {'Range': f'bytes={size}-'} if size else {}
            with requests.get(f'https://huggingface.co/{repo}/resolve/main/{name}?download=true&offset={size}',
                              headers=headers, stream=True, timeout=(30, 120)) as r:
                r.raise_for_status()
                append = size > 0 and r.status_code == 206
                if append:
                    assert r.headers['Content-Range'].startswith(f'bytes {size}-')
                with path.open('ab' if append else 'wb') as out:
                    for chunk in r.iter_content(1024 * 1024):
                        out.write(chunk)
        assert path.stat().st_size == entry['size'], f'Incomplete: {name}'
        sha = digest(path)
        if 'lfs' in entry:
            assert sha == entry['lfs']['oid'], f'Hash mismatch: {name}'
        else:
            data = path.read_bytes()
            assert hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest() == entry['oid']
        print(json.dumps({'file': name, 'bytes': path.stat().st_size, 'sha256': sha}), flush=True)
    (model_dir / 'publisher-inventory.json').write_text(json.dumps(inventory, indent=2))
    CACHE.mkdir(parents=True, exist_ok=True)
    manifest = []
    for language, config in [('hi', 'hi_in'), ('en', 'en_us')]:
        r = requests.get('https://datasets-server.huggingface.co/first-rows',
                         params={'dataset': 'google/fleurs', 'config': config, 'split': 'validation'}, timeout=60)
        r.raise_for_status()
        for item in r.json()['rows'][:20]:
            row = item['row']
            path = CACHE / f'fleurs-{config}-{item["row_idx"]}.wav'
            if not path.exists():
                response = requests.get(row['audio'][0]['src'], timeout=60)
                response.raise_for_status()
                path.write_bytes(response.content)
            manifest.append({'language': language, 'row': item['row_idx'], 'id': row['id'],
                             'file': str(path.relative_to(ROOT)), 'reference': row['transcription'],
                             'sha256': digest(path), 'source': 'https://huggingface.co/datasets/google/fleurs',
                             'config': config, 'split': 'validation', 'license': 'CC-BY-4.0'})
        print(f'Fetched {config}: 20 real recordings', flush=True)
    (CACHE / 'fleurs-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

def normalize(text):
    return ' '.join(''.join(c.lower() if unicodedata.category(c)[0] in 'LMN' or c.isspace() else ' '
                            for c in unicodedata.normalize('NFC', text)).split())

def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, left in enumerate(a, 1):
        current = [i]
        for j, right in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j-1] + (left != right)))
        previous = current
    return previous[-1]

def peak_memory_bytes():
    import os
    if os.name != 'nt':
        return None
    import ctypes
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ('PeakWorkingSetSize', 'WorkingSetSize',
            'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage', 'QuotaPeakNonPagedPoolUsage',
            'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage')]
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    psapi = ctypes.WinDLL('psapi', use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    value = Counters()
    value.cb = ctypes.sizeof(value)
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(value), value.cb):
        raise ctypes.WinError(ctypes.get_last_error())
    return value.PeakWorkingSetSize

def benchmark(model, label=None, manifest_path=None, model_dir=None, prefix=None, languages=None):
    import numpy as np
    import soundfile as sf
    import sherpa_onnx
    manifest = json.loads((manifest_path or CACHE / 'fleurs-manifest.json').read_text(encoding='utf-8'))
    directory = model_dir or ROOT / ('app/src/main/assets/language_packs/shared/stt' if model == 'tiny' else 'app/build/whisper-small')
    prefix = (model + '-') if prefix is None else prefix
    results, summaries = [], {}
    for lang in (languages or ['hi', 'en']):
        start = time.perf_counter()
        recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(directory / f'{prefix}encoder.int8.onnx'), decoder=str(directory / f'{prefix}decoder.int8.onnx'),
            tokens=str(directory / f'{prefix}tokens.txt'), language=lang, task='transcribe', num_threads=4)
        load_seconds = time.perf_counter() - start
        rows = []
        for record in [r for r in manifest if r['language'] == lang]:
            assert digest(ROOT / record['file']) == record['sha256'], 'Test audio changed'
            audio, rate = sf.read(ROOT / record['file'], dtype='float32')
            assert rate == 16000 and audio.ndim == 1
            stream = recognizer.create_stream()
            stream.accept_waveform(rate, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
            start = time.perf_counter()
            recognizer.decode_stream(stream)
            elapsed = time.perf_counter() - start
            output = stream.result.text.strip()
            ref, hyp = normalize(record['reference']), normalize(output)
            row = {**record, 'output': output, 'seconds': elapsed, 'audio_seconds': len(audio)/rate,
                   'word_errors': distance(ref.split(), hyp.split()), 'words': len(ref.split()),
                   'char_errors': distance(ref.replace(' ', ''), hyp.replace(' ', '')), 'chars': len(ref.replace(' ', ''))}
            rows.append(row)
            print(json.dumps({'model': model, 'lang': lang, 'row': record['row'], 'output': output}), flush=True)
        summaries[lang] = {'count': len(rows), 'wer': sum(r['word_errors'] for r in rows)/sum(r['words'] for r in rows),
                           'cer': sum(r['char_errors'] for r in rows)/sum(r['chars'] for r in rows),
                           'rtf': sum(r['seconds'] for r in rows)/sum(r['audio_seconds'] for r in rows),
                           'load_seconds': load_seconds}
        results.extend(rows)
        del recognizer
    REPORT.mkdir(parents=True, exist_ok=True)
    report = {'model': model, 'label': label or model, 'sherpa_version': sherpa_onnx.__version__,
              'model_directory': str(directory), 'file_prefix': prefix,
              'model_sha256': {name: digest(directory / f'{prefix}{name}')
                               for name in ('encoder.int8.onnx', 'decoder.int8.onnx', 'tokens.txt')},
              'native_module': str(sherpa_onnx.__file__), 'summaries': summaries,
              'peak_process_working_set_bytes': peak_memory_bytes(), 'rows': results}
    (REPORT / f'{label or model}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summaries), flush=True)

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['fetch', 'benchmark'])
    p.add_argument('--model', choices=['tiny', 'small'])
    p.add_argument('--label', help='Distinct result name; preserves baseline reports')
    p.add_argument('--manifest', type=Path)
    p.add_argument('--model-dir', type=Path)
    p.add_argument('--prefix', help='Filename prefix, including trailing hyphen; empty for unprefixed exports')
    p.add_argument('--languages', nargs='+')
    args = p.parse_args()
    if args.action == 'fetch': fetch()
    else: benchmark(args.model, args.label, args.manifest, args.model_dir, args.prefix, args.languages)
