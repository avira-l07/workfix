"""Check published Marathi MMS vocabulary; never download weights or send private data."""
from pathlib import Path
import json, requests
out = Path(__file__).resolve().parent
report = {}
for filename in ('vocab.json', 'tokenizer_config.json'):
    url = f'https://huggingface.co/facebook/mms-tts-mar/resolve/main/{filename}'
    response = requests.get(url, timeout=(15, 25))
    response.raise_for_status()
    data = response.json()
    (out / ('marathi-' + filename)).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    report[filename] = {'url': url, 'resolved_url': response.url,
        'loanword_vowel_present': 'ॉ' in data if filename == 'vocab.json' else None}
(out / 'marathi-published-vocabulary.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
