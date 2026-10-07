"""Fetch a pinned candidate, verify its files, and record actual model outputs.

No lookup table, per-example correction, or expected-answer substitution.
"""
import hashlib
import json
import os
import time
import urllib.request
from pathlib import Path

import ctranslate2
import sentencepiece

root = Path(__file__).resolve().parent
info = json.loads((root / 'm2m-model-info.json').read_text(encoding='utf-8-sig'))
assert info['id'] == 'jncraton/m2m100_418M-ct2-int8'
revision = info['sha']
assert len(revision) == 40 and all(c in '0123456789abcdef' for c in revision)
directory = Path(os.environ['TEMP']) / 'itantra-translation-20261006' / 'm2m-int8'
directory.mkdir(parents=True, exist_ok=True)
files = []
for row in info['siblings']:
    name = row['rfilename']
    if name in {'.gitattributes', 'README.md'}:
        continue
    assert Path(name).name == name
    path = directory / name
    if not path.exists():
        temporary = directory / (name + '.download')
        # Ask for a fresh CDN redirect; the cached link previously named a retired host.
        url = f"https://huggingface.co/{info['id']}/resolve/{revision}/{name}?download=true&check={time.time_ns()}"
        with urllib.request.urlopen(url, timeout=45) as source, temporary.open('wb') as destination:
            while chunk := source.read(1024 * 1024):
                destination.write(chunk)
        assert temporary.stat().st_size == row['size'], name
        for attempt in range(8):
            try:
                temporary.replace(path)
                break
            except PermissionError:
                if attempt == 7:
                    raise
                time.sleep(0.25 * (attempt + 1))
    assert path.stat().st_size == row['size'], name
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if row.get('lfs'):
        assert digest == row['lfs']['sha256'], name
    else:
        data = path.read_bytes()
        assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == row['blobId'], name
    files.append({'name': name, 'bytes': path.stat().st_size, 'sha256': digest})
    print('Verified', name, flush=True)

translator = ctranslate2.Translator(str(directory), device='cpu', compute_type='int8', inter_threads=1, intra_threads=4)
tokenizer = sentencepiece.SentencePieceProcessor(model_file=str(directory / 'sentencepiece.bpe.model'))
print((directory / 'config.json').read_text(), flush=True)
report = {'model': info['id'], 'revision': revision, 'files': files,
          'canned_answers': False, 'device': 'Windows desktop CPU; not a phone benchmark', 'results': []}
for case in json.loads((root / 'cases.json').read_text(encoding='utf-8')):
    start = time.perf_counter()
    source = [f"__{case['source']}__", *tokenizer.encode(case['text'], out_type=str), '</s>']
    result = translator.translate_batch([source], target_prefix=[[f"__{case['target']}__"]], beam_size=5, max_decoding_length=256)[0]
    output = tokenizer.decode(result.hypotheses[0][1:])
    row = {**case, 'output': output, 'latency_seconds': round(time.perf_counter() - start, 3)}
    report['results'].append(row)
    (root / 'm2m-model-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(row, ensure_ascii=False), flush=True)
