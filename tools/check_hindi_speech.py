"""Offline synthetic Hindi smoke test; requires numpy and sherpa-onnx.

This tests real inference, not a replacement for recording on the target phone.
Example: python tools/check_hindi_speech.py --model-dir app/build/whisper-small --prefix small
"""
import argparse
import json
from pathlib import Path

import numpy as np
import sherpa_onnx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--prefix', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    tts_dir = root / 'app/src/main/assets/language_packs/hi/tts'
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=str(tts_dir / 'model.onnx'), tokens=str(tts_dir / 'tokens.txt'),
            noise_scale=0, noise_scale_w=0), num_threads=2)))
    phrases = ['मैं कौन हूँ', 'नमस्ते आप कैसे हैं', 'मुझे पानी चाहिए']
    audio = [(text, tts.generate(text)) for text in phrases]
    results = []
    for task in ['transcribe', 'translate']:
        recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(args.model_dir / f'{args.prefix}-encoder.int8.onnx'),
            decoder=str(args.model_dir / f'{args.prefix}-decoder.int8.onnx'),
            tokens=str(args.model_dir / f'{args.prefix}-tokens.txt'),
            language='hi', task=task, num_threads=2)
        for reference, sample in audio:
            stream = recognizer.create_stream()
            waveform = np.concatenate([sample.samples, np.zeros(int(sample.sample_rate * 0.1), dtype=np.float32)])
            stream.accept_waveform(sample.sample_rate, waveform)
            recognizer.decode_stream(stream)
            row = {'model': args.prefix, 'task': task, 'input': reference, 'output': stream.result.text}
            results.append(row)
            print(json.dumps(row), flush=True)
    if args.output:
        args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
