"""Stage the locally repaired Python module, or package its Android counterpart.
Never installs globally or touches a phone. See tools/STT_REPAIR.md for provenance.
"""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / 'tools/stt_models'

def desktop():
    original = Path(importlib.util.find_spec('sherpa_onnx').origin).parent
    destination = CACHE / 'patched-python/sherpa_onnx'
    for file in original.rglob('*.py'):
        target = destination / file.relative_to(original)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, target)
    lib = destination / 'lib'
    lib.mkdir(parents=True, exist_ok=True)
    extensions = list((CACHE / 'build-windows').rglob('_sherpa_onnx*.pyd'))
    assert len(extensions) == 1, extensions
    shutil.copy2(extensions[0], lib / extensions[0].name)
    for file in (CACHE / 'build-windows/_deps/onnxruntime-src/lib').glob('*.dll'):
        shutil.copy2(file, lib / file.name)
    print(destination.parent)

def android():
    source = CACHE / 'sherpa-onnx-1.13.8.aar'
    expected = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
    with source.open('rb') as f:
        assert hashlib.file_digest(f, 'sha256').hexdigest() == expected
    native = CACHE / 'build-android/lib/libsherpa-onnx-jni.so'
    assert native.stat().st_size > 0
    destination = ROOT / 'app/libs/sherpa-onnx.aar'
    staged = destination.with_suffix('.aar.tmp')
    replacements = {
        'jni/arm64-v8a/libsherpa-onnx-jni.so': native,
        'jni/arm64-v8a/libonnxruntime.so': CACHE / 'build-android/_deps/onnxruntime-src/jni/arm64-v8a/libonnxruntime.so'
    }
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as out:
        assert replacements.keys() <= set(original.namelist())
        for info in original.infolist():
            data = replacements[info.filename].read_bytes() if info.filename in replacements else original.read(info.filename)
            out.writestr(info, data)
    staged.replace(destination)
    print(destination)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=['desktop', 'android'])
    args = parser.parse_args()
    desktop() if args.target == 'desktop' else android()
