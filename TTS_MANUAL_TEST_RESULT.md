# iTantra TTS Manual Test Result

## Workspace

Target:
D:\new itantra

Reference:
C:\Users\avira\AndroidStudioProjects\iTantra

Reference repository modified:
NO

## Device

Device:
Xiaomi Redmi Note 13 Pro 5G / POCO X6 5G

Model:
23122PCD1I (Board: garnet, ABI: arm64-v8a)

Android:
14 (API 34)

Serial:
7229d2bb (Status: Disconnected from ADB)

## Model Provisioning

### English

Source:
https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/eng/

Model:
model.onnx

Tokens:
tokens.txt

Model size:
114,017,028 bytes (SHA-256: 409bafdb550948dc5c0b216e21b341732ef3cefead8008b512b034f1bdb27132)

Runtime directory:
files/language_packs/en/tts/

Compatible with current Sherpa-ONNX:
YES

### Hindi

Source:
https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/hin/

Model:
model.onnx

Tokens:
tokens.txt

Model size:
114,043,140 bytes (SHA-256: c44b4179e7ff0da4d76eac3929fc1dbd67c51f02da8fca083c445e17450c3f75)

Runtime directory:
files/language_packs/hi/tts/

Compatible with current Sherpa-ONNX:
YES

## English TTS

Model found:
PASS

Model loaded:
PASS

Text accepted:
PASS

PCM generated:
PASS

PCM non-empty:
PASS

AudioTrack:
BLOCKED

Physical speaker:
BLOCKED

Actual synthesized English speech:
BLOCKED

Final:
BLOCKED

## Hindi TTS

Model found:
PASS

Model loaded:
PASS

Text accepted:
PASS

PCM generated:
PASS

PCM non-empty:
PASS

AudioTrack:
BLOCKED

Physical speaker:
BLOCKED

Actual synthesized Hindi speech:
BLOCKED

Final:
BLOCKED

## Regression

VAD:
PASS

English STT:
PASS

Hindi STT:
PASS

Packet:
PASS

Encryption:
PASS

Decryption:
PASS

Decode:
PASS

## Translation

UNAVAILABLE — intentionally untouched.

## Final TTS Status

English TTS = BLOCKED

Hindi TTS = BLOCKED
