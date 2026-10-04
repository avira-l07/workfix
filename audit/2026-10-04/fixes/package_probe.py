"""Fresh APK/AAR inventory and ELF alignment inspection, no device installation."""
import hashlib,json,struct,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]; OUT=Path(__file__).resolve().parent
rows=[]
paths=list((OUT.parent/'build/app/outputs/apk').rglob('*.apk'))+[ROOT/'app/libs/sherpa-onnx.aar']
for path in paths:
    with path.open('rb') as f: digest=hashlib.file_digest(f,'sha256').hexdigest()
    with zipfile.ZipFile(path) as archive:
        entries=archive.infolist(); libs=[]
        for entry in entries:
            if entry.filename.endswith('.so'):
                data=archive.read(entry)
                aligns=[]
                if data[:5]==b'\x7fELF\x02' and data[5]==1:
                    offset=struct.unpack_from('<Q',data,32)[0]
                    size,count=struct.unpack_from('<HH',data,54)
                    for index in range(count):
                        pos=offset+size*index
                        if struct.unpack_from('<I',data,pos)[0]==1:
                            aligns.append(struct.unpack_from('<Q',data,pos+48)[0])
                libs.append({'path':entry.filename,'bytes':entry.file_size,'compressed_bytes':entry.compress_size,
                             'elf_load_segment_alignments':aligns})
        rows.append({'path':str(path),'bytes':path.stat().st_size,'sha256':digest,
                     'onnx_weight_assets':[e.filename for e in entries if e.filename.startswith('assets/language_packs/') and e.filename.endswith('.onnx')],
                     'bundled_benchmark_wav_count':sum(e.filename.startswith('assets/benchmark/') and e.filename.endswith('.wav') for e in entries),
                     'native_libraries':libs})
(OUT/'packages.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
print(json.dumps([{'path':r['path'],'bytes':r['bytes'],'sha256':r['sha256'],'onnx_weights':len(r['onnx_weight_assets']),
                  'native_libraries':len(r['native_libraries']),
                  'alignment_below_16k':[l['path'] for l in r['native_libraries'] if any(a<16384 for a in l['elf_load_segment_alignments'])]} for r in rows],indent=2))
