"""Select which verified speech models are stored inside the APK assets.

Moves local model files to an ignored workspace backup; does not delete models.
Modes: minimal (all downloaded), demo (Hindi/English speech bundled), all.
"""
import argparse
import hashlib
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/language_packs'
BACKUP = ROOT / 'models/bundled'
PINNED_TOKEN = 'b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126'

def checksum(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def relocate(relative, bundle):
    asset = ASSETS / relative
    backup = BACKUP / relative
    assert asset.resolve().is_relative_to(ASSETS.resolve())
    assert backup.resolve().is_relative_to(BACKUP.resolve())
    source, destination = (backup, asset) if bundle else (asset, backup)
    if not source.exists():
        if not destination.exists():
            if bundle:
                raise FileNotFoundError(f'Model missing from both locations: {relative}')
            return  # A fresh checkout needs no local model files for minimal mode.
        return
    if destination.exists():
        assert checksum(source) == checksum(destination), f'Conflicting model copies: {relative}'
        source.unlink()
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source), str(destination))

def tiny_tokens(bundle):
    asset = ASSETS / 'shared/stt/tiny-tokens.txt'
    backup = BACKUP / 'shared/stt/tiny-tokens.txt'
    if asset.exists() and checksum(asset) != PINNED_TOKEN:
        legacy = BACKUP / 'legacy/tiny-tokens.txt'
        legacy.parent.mkdir(parents=True, exist_ok=True)
        if legacy.exists():
            assert checksum(legacy) == checksum(asset)
            asset.unlink()
        else:
            shutil.move(str(asset), str(legacy))
    if bundle and not backup.exists():
        candidate = ROOT / 'tools/stt_models/hindi-small/tokens.txt'
        if not candidate.exists() or checksum(candidate) != PINNED_TOKEN:
            raise FileNotFoundError('Run the pinned Hindi model fetch; its token file matches the hosted Tiny token file')
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(candidate, backup)
    if bundle or asset.exists() or backup.exists():
        relocate(Path('shared/stt/tiny-tokens.txt'), bundle)

def main(mode):
    if mode not in ('minimal', 'demo', 'all'):
        raise ValueError(mode)
    tiny = mode != 'minimal'
    hindi = mode != 'minimal'
    for name in ('tiny-encoder.int8.onnx', 'tiny-decoder.int8.onnx'):
        relocate(Path('shared/stt') / name, tiny)
    tiny_tokens(tiny)
    for name in ('encoder.int8.onnx', 'decoder.int8.onnx', 'tokens.txt'):
        relocate(Path('shared/stt-hi-v1') / name, hindi)
    for code in ('hi', 'en', 'bn', 'gu', 'mr', 'kn', 'ml', 'ta', 'te', 'or'):
        relocate(Path(code) / 'tts/model.onnx', mode == 'all' or (mode == 'demo' and code in ('hi', 'en')))
    print(f'Model bundle: {mode}; ignored backup: {BACKUP}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['minimal', 'demo', 'all'])
    main(parser.parse_args().mode)
