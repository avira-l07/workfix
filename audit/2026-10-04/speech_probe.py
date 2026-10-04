"""Fresh offline desktop probes, not phone validation or an accuracy benchmark."""
import argparse, hashlib, json, math, statistics, subprocess, sys, time, unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
LANGUAGES = ['hi', 'en', 'ta', 'te', 'or', 'bn', 'gu', 'mr', 'ml', 'kn']
PHRASES = {
    'hi': ['मदद चाहिए', 'पानी भेजिए', 'डॉक्टर को बुलाइए'],
    'en': ['We need help', 'Send clean water', 'Call a doctor'],
    'ta': ['உதவி தேவை', 'தண்ணீர் அனுப்புங்கள்', 'மருத்துவரை அழைக்கவும்'],
    'te': ['సహాయం కావాలి', 'నీరు పంపండి', 'వైద్యుడిని పిలవండి'],
    'or': ['ସାହାଯ୍ୟ ଆବଶ୍ୟକ', 'ପାଣି ପଠାନ୍ତୁ', 'ଡାକ୍ତରଙ୍କୁ ଡାକନ୍ତୁ'],
    'bn': ['সাহায্য দরকার', 'পানি পাঠান', 'ডাক্তার ডাকুন'],
    'gu': ['મદદ જોઈએ છે', 'પાણી મોકલો', 'ડોક્ટરને બોલાવો'],
    'mr': ['मदत हवी आहे', 'पाणी पाठवा', 'डॉक्टरांना बोलवा'],
    'ml': ['സഹായം വേണം', 'വെള്ളം അയയ്ക്കൂ', 'ഡോക്ടറെ വിളിക്കൂ'],
    'kn': ['ಸಹಾಯ ಬೇಕು', 'ನೀರು ಕಳುಹಿಸಿ', 'ವೈದ್ಯರನ್ನು ಕರೆಸಿ'],
}
RANGES = {'hi': (0x900,0x97f), 'mr': (0x900,0x97f), 'bn': (0x980,0x9ff),
          'gu': (0xa80,0xaff), 'or': (0xb00,0xb7f), 'ta': (0xb80,0xbff),
          'te': (0xc00,0xc7f), 'kn': (0xc80,0xcff), 'ml': (0xd00,0xd7f)}

def sha(p):
    with p.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def normalize(text):
    return ''.join(c if unicodedata.category(c)[0] in 'LMN' else ' '
                   for c in unicodedata.normalize('NFC', text).lower()).split()

def distance(a,b):
    prev=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        cur=[i]
        for j,y in enumerate(b,1): cur.append(min(cur[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
        prev=cur
    return prev[-1]

def native(lang,text):
    letters=[c for c in text if unicodedata.category(c)[0] in 'LM']
    if not letters: return False
    if lang=='en': return sum(c.isascii() and c.isalpha() for c in letters)>len(letters)/2
    lo,hi=RANGES[lang]
    return sum(lo<=ord(c)<=hi for c in letters)>len(letters)/2

def probe(lang):
    import numpy as np, sherpa_onnx, soundfile as sf
    result={'language':lang,'scope':'Windows desktop; 1 clean recording, 1 silence probe, 3 TTS phrases; phone NOT VERIFIED',
            'runtime':sherpa_onnx.__version__}
    rows=json.loads((ROOT/'app/src/main/assets/benchmark/five_self_test/manifest.json').read_text(encoding='utf-8'))
    row=next((r for r in rows if r['language']==lang),None)
    if row:
        wav=ROOT/'app/src/main/assets'/row['asset']; reference=row['reference']
    else:
        row=json.loads((ROOT/'tools/stt_models/five-language-validation/manifest-kn-30.json').read_text(encoding='utf-8'))[0]
        wav=ROOT/row['file']; reference=row['reference']
    assert sha(wav)==row['sha256'], 'Input audio checksum mismatch'
    samples,rate=sf.read(wav,dtype='float32'); assert rate==16000 and samples.ndim==1
    modelroot=ROOT/'tools/stt_models/indicconformer-candidates'
    if lang=='kn':
        root=ROOT/'app/src/main/assets/language_packs/shared/stt'
        files=[root/'tiny-encoder.int8.onnx',root/'tiny-decoder.int8.onnx',root/'tiny-tokens.txt']
        start=time.perf_counter()
        engine=sherpa_onnx.OfflineRecognizer.from_whisper(encoder=str(files[0]),decoder=str(files[1]),
            tokens=str(files[2]),language='kn',task='transcribe',num_threads=4,tail_paddings=-1)
        result['stt_model']='Active fallback: Whisper Tiny INT8'
    else:
        files=[modelroot/lang/'model.int8.onnx',modelroot/('en/tokens.txt' if lang=='en' else 'tokens.txt')]
        start=time.perf_counter()
        engine=sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=str(files[0]),tokens=str(files[1]),num_threads=4)
        result['stt_model']='Manual language: IndicConformer CTC INT8'
    result['stt_load_ms']=(time.perf_counter()-start)*1000
    result['stt_files']=[{'path':str(f),'bytes':f.stat().st_size,'sha256':sha(f)} for f in files]
    def decode(audio):
        stream=engine.create_stream(); stream.accept_waveform(16000,np.concatenate([audio,np.zeros(1600,dtype=np.float32)]))
        start=time.perf_counter(); engine.decode_stream(stream)
        return stream.result.text.strip(),(time.perf_counter()-start)*1000
    text,elapsed=decode(samples); ref,hyp=normalize(reference),normalize(text)
    result['stt']={'reference':reference,'output':text,'native_script_majority':native(lang,text),
        'decode_ms':elapsed,'audio_seconds':len(samples)/16000,'rtf':elapsed/1000/(len(samples)/16000),
        'single_recording_wer':distance(ref,hyp)/len(ref),'word_errors':distance(ref,hyp),'reference_words':len(ref)}
    text,elapsed=decode(np.zeros(16000,dtype=np.float32))
    result['silence']={'output':text,'decode_ms':elapsed}
    del engine
    model=(ROOT/f'app/src/main/assets/language_packs/{lang}/tts/model.onnx'
           if lang in ('hi','en') else ROOT/f'models/bundled/{lang}/tts/model.onnx')
    tokens=ROOT/f'app/src/main/assets/language_packs/{lang}/tts/tokens.txt'
    manifest=json.loads((ROOT/f'app/src/main/assets/language_packs/{lang}_dev_manifest.json').read_text(encoding='utf-8'))
    expected=manifest['ttsModel']['checksumsSha256']
    assert sha(model)==expected['model.onnx'] and sha(tokens)==expected['tokens.txt'], 'TTS checksum mismatch'
    vocab={line.rsplit(' ',1)[0] for line in tokens.read_text(encoding='utf-8').splitlines()}
    start=time.perf_counter()
    tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model),tokens=str(tokens)),num_threads=1)))
    result['tts_load_ms']=(time.perf_counter()-start)*1000
    tts.generate(PHRASES[lang][0])
    result['tts_files']=[{'path':str(f),'bytes':f.stat().st_size,'sha256':sha(f)} for f in [model,tokens]]
    result['tts']=[]
    for i,phrase in enumerate(PHRASES[lang]):
        start=time.perf_counter(); audio=tts.generate(phrase); elapsed=(time.perf_counter()-start)*1000
        pcm=np.asarray(audio.samples)
        entry={'phrase':phrase,'synthesis_ms':elapsed,'sample_rate':audio.sample_rate,'samples':len(pcm),
               'peak_amplitude':float(np.max(np.abs(pcm))) if len(pcm) else 0,
               'finite':bool(np.isfinite(pcm).all()),
               'missing_token_letters':[f'U+{ord(c):04X}' for c in sorted(set(phrase))
                                        if unicodedata.category(c)[0] in 'LM' and c not in vocab]}
        result['tts'].append(entry)
        sf.write(OUT/f'tts-{lang}-{i}.wav',pcm,audio.sample_rate,subtype='PCM_16')
    (OUT/f'speech-{lang}.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f"{lang}: STT {result['stt']['decode_ms']:.0f}ms native={result['stt']['native_script_majority']}; TTS {statistics.mean(r['synthesis_ms'] for r in result['tts']):.0f}ms; phone Not verified",flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--language',choices=LANGUAGES)
    args=parser.parse_args()
    if args.language: probe(args.language)
    else:
        for lang in LANGUAGES:
            run=subprocess.run([sys.executable,__file__,'--language',lang])
            if run.returncode: print(f'{lang}: FAILED process exit {run.returncode}',flush=True)
