"""Read-only public artifact availability checks; no weights are downloaded."""
import concurrent.futures,json,re
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[2]; OUT=Path(__file__).resolve().parent
jobs=[]
for path in sorted((ROOT/'app/src/main/assets/language_packs').glob('*_dev_manifest.json')):
    manifest=json.loads(path.read_text(encoding='utf-8'))
    for kind in ('sttModel','ttsModel'):
        spec=manifest.get(kind)
        if spec and spec.get('downloadUrl'):
            name=spec['files'][0]
            jobs.append({'language':manifest['languageCode'],'component':kind,'url':spec['downloadUrl'].rstrip('/')+'/'+name})
source=(ROOT/'app/src/main/java/com/itantra/core/inference/AdditionalSttModel.kt').read_text()
base_url=re.search(r'baseUrl\s*=\s*"([^"]+)"',source).group(1)
for lang in ('hi','en','ta','te','or','bn','gu','mr','ml'):
    jobs.append({'language':lang,'component':'Manual CTC','url':f'{base_url}/{lang}/model.int8.onnx'})
def check(item):
    try:
        response=requests.head(item['url'],allow_redirects=True,timeout=(15,25))
        return dict(item,status=response.status_code,advertised_bytes=response.headers.get('Content-Length'),
                    note='HEAD only; a successful complete download/checksum/resume/cancellation is not proven')
    except Exception as error: return dict(item,error=type(error).__name__+': '+str(error).split('(Caused by')[0])
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: rows=list(pool.map(check,jobs))
(OUT/'download-endpoints.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
print(json.dumps([{'language':r['language'],'component':r['component'],'status':r.get('status'),'error':r.get('error')} for r in rows],indent=2))
