"""Check the actual APK's required static and manifest-derived asset paths."""
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent
apk = out / 'iTantra-1.7-connected-ui.apk'
languages = ('hi', 'en', 'bn', 'gu', 'mr', 'kn', 'ml', 'ta', 'te', 'or')

def check_wav(data):
    # Python's wave module rejects IEEE float; Android's tested reader accepts it.
    assert data[:4] == b'RIFF' and data[8:12] == b'WAVE'
    end = 8 + struct.unpack_from('<I', data, 4)[0]
    assert end <= len(data)
    offset, fmt, samples = 12, None, None
    while offset + 8 <= end:
        name, size = struct.unpack_from('<4sI', data, offset)
        start = offset + 8
        assert start + size <= end
        if name == b'fmt ':
            fmt = struct.unpack_from('<HHIIHH', data, start)
        elif name == b'data':
            samples = data[start:start + size]
        offset = start + size + size % 2
    assert fmt and samples
    encoding, channels, rate, _, alignment, bits = fmt
    assert channels == 1 and rate == 16000 and (encoding, bits) in ((1, 16), (3, 32))
    assert alignment == bits // 8 and len(samples) % alignment == 0

with zipfile.ZipFile(apk) as archive:
    checked = set()

    def asset(name):
        data = archive.read('assets/' + name)
        assert data, name
        checked.add(name)
        return data

    # Literal references, including diagnostics, VAD and benchmark fallback manifests.
    for source in (root / 'app/src/main/java').rglob('*.kt'):
        for name in re.findall(r'assets\.open\("([^"$]+)"\)', source.read_text(encoding='utf-8')):
            asset(name)

    for code in languages:
        manifest = json.loads(asset(f'language_packs/{code}_dev_manifest.json'))
        assert manifest['languageCode'] == code
        for key in ('sttModel', 'ttsModel'):
            component = manifest[key]
            assert component['downloadUrl'].startswith('https://'), (code, key)
            assert set(component['files']) == set(component['checksumsSha256']), (code, key)
            assert all(re.fullmatch(r'[0-9a-f]{64}', digest) for digest in component['checksumsSha256'].values())
        asset(f'benchmark/{code}_benchmark.json')

    rows = json.loads(asset('benchmark/five_self_test/manifest.json'))
    assert {row['language'] for row in rows} == set(languages)
    for row in rows:
        data = asset(f"benchmark/five_self_test/{row['language']}.wav")
        assert hashlib.sha256(data).hexdigest() == row['sha256']
        check_wav(data)

    english = json.loads(asset('benchmark/en_benchmark.json'))
    assert len(english) == 24 and len({row['id'] for row in english}) == 24
    for row in english:
        data = asset(f"benchmark/audio/{row['id']}.wav")
        check_wav(data)

    asset('language_packs/mr/piper-tokens.txt')
    assert 'lib/arm64-v8a/libsherpa-onnx-jni.so' in archive.namelist()

report = dict(apk=str(apk), required_asset_paths_checked=len(checked),
    ten_language_manifests='PASS', ten_bundled_self_test_audio_hashes='PASS',
    english_benchmark_recordings=24, pcm_audio_format='PASS', native_speech_library='PASS',
    missing_required_packaged_assets=[],
    downloaded_model_files='Installed and checksum-validated on the phone when provisioned; not bundled in full',
    live_remote_download_availability='Not retested by this local APK path check')
(out / 'asset-path-verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
