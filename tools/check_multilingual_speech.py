"""Run the bundled STT against one short synthetic phrase per installed TTS language."""
from pathlib import Path
import sys
import json
import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parents[1]
PACKS = ROOT / 'app/src/main/assets/language_packs'
PHRASES = {
    'hi': 'मैं कौन हूँ', 'en': 'who am I', 'bn': 'আমি কে', 'gu': 'હું કોણ છું',
    'mr': 'मी कोण आहे', 'kn': 'ನಾನು ಯಾರು', 'ml': 'ഞാൻ ആരാണ്', 'ta': 'நான் யார்',
    'te': 'నేను ఎవరు', 'or': 'ମୁଁ କିଏ',
}
stt = PACKS / 'shared/stt'
for code, phrase in PHRASES.items():
    tts_dir = PACKS / code / 'tts'
    if not (tts_dir / 'model.onnx').exists():
        continue
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=str(tts_dir / 'model.onnx'), tokens=str(tts_dir / 'tokens.txt'), noise_scale=0, noise_scale_w=0), num_threads=2)))
    audio = tts.generate(phrase)
    recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(stt / 'tiny-encoder.int8.onnx'), decoder=str(stt / 'tiny-decoder.int8.onnx'),
        tokens=str(stt / 'tiny-tokens.txt'), language=code, task='transcribe', num_threads=2)
    stream = recognizer.create_stream()
    stream.accept_waveform(audio.sample_rate, np.concatenate([audio.samples, np.zeros(int(audio.sample_rate * .1), dtype=np.float32)]))
    recognizer.decode_stream(stream)
    print(json.dumps({'expected': code, 'phrase': phrase, 'output': stream.result.text, 'detected': stream.result.lang}, ensure_ascii=False), flush=True)
