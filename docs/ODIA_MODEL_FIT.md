# Odia speech model fit — desktop review, 2 October 2026

For iTantra's offline Android STT → small text packet → TTS loop, the current
**IndicConformer CTC INT8 STT** is the best *tested fit in this project*. The
current **MMS-VITS Odia TTS** is the smallest already-integrated voice candidate.
Neither can be called best for field accuracy or listening quality yet. The
[SIH26173 statement](https://sih.gov.in/sih2026PS) weights accuracy 40%,
efficiency 20% and latency 20%, without publishing a pass threshold for these
models. Desktop model time is only part of end-to-end latency. Model downloads
consume local storage and first-use internet, but speech model weights are not
sent over the peer link; per-message packet efficiency must be measured from
the actual text frame, independently of these model sizes.

| Criterion | Current Odia STT | Current Odia TTS |
|---|---|---|
| Model/runtime | [AI4Bharat IndicConformer CTC INT8 export](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx), sherpa-onnx | [Meta MMS-VITS Odia ONNX export](https://huggingface.co/willwade/mms-tts-multilingual-models-onnx), sherpa-onnx |
| Downloaded files | 197,652,533 bytes | 114,046,707 bytes |
| Desktop accuracy / quality | 21.37% WER and 5.93% CER on 20 clean FLEURS Odia validation clips; all 20 yielded Odia-script output | Three generated phrases produced nonzero PCM; **no native-speaker intelligibility or naturalness score** |
| Desktop speed | 0.056 aggregate decode real-time factor, 696 ms mean decode per clip, four threads | Three phrases, five runs each: **2.14 s mean synthesis / 1.02 mean RTF** at one thread, matching the app setting. With two threads: **1.16 s / 0.53 RTF** on this PC only. |
| Desktop model load | 0.995 s in the isolated STT process | 1.557 s in the isolated one-thread TTS process |
| Desktop memory | 529,027,072-byte peak Windows process working set in the separate STT run | 301,842,432-byte peak in the one-thread TTS run; not an Android memory number |
| Distribution | Exporter card identifies the Indic source as MIT; verify notices for the exported files | [Meta's source checkpoint is CC BY-NC 4.0](https://huggingface.co/facebook/mms-tts-ory); not an unrestricted commercial-release choice |

The [STT method, hashes and raw rows](ODIA_STT_CANDIDATE.md) are preserved.
The [TTS benchmark script](../tools/benchmark_odia_tts.py), [one-thread
results](../tools/stt_results/odia-tts-desktop.json), [two-thread
results](../tools/stt_results/odia-tts-desktop-2threads.json) and
[generated WAV files](../tools/stt_results/odia-tts-samples/) allow a listener
check. On those three synthesized phrases, feeding the audio into the selected
Odia STT gave 4 word errors in 12 reference words (33.3% WER). This is a
**gross-error proxy, not a TTS quality score**: either model can cause an error,
and the sample is tiny. The [round-trip rows](../tools/stt_results/odia-tts-roundtrip.json)
show exactly what was recognized. The MMS character vocabulary omits the
sentence-ending `।`, which sherpa-onnx skipped during these runs; long sentences
may lose natural pauses.

## Other candidates considered

- [AI4Bharat's 600M multilingual IndicConformer](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual) supports Odia and lists MIT terms, but its files are gated and it has no same-audio iTantra benchmark or current sherpa-onnx integration. Its larger parameter count is a storage/memory risk, not proof of higher accuracy here.
- [OpenVoiceOS's Odia ONNX export](https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-or-onnx) derives from the same 120M AI4Bharat checkpoint as the selected STT. An alternative export is not evidence of a better transcript; it would need the same-corpus and Android comparison.
- [AI4Bharat IndicF5](https://huggingface.co/ai4bharat/IndicF5) supports Odia under MIT but is a 0.4B-parameter, reference-audio-conditioned voice model. It has no ready replacement path in the app's sherpa-onnx VITS engine.
- [AI4Bharat Indic Parler-TTS](https://huggingface.co/ai4bharat/indic-parler-tts) supports Odia under Apache 2.0 and publishes an Odia native-speaker score, but is a 0.9B-parameter generative model with a different inference stack. That score is not directly comparable to the unscored MMS samples, and there is no phone latency/footprint result for iTantra.
- [Meta MMS-1B ASR](https://huggingface.co/facebook/mms-1b-all) is a much larger, CC BY-NC 4.0 model. It has no same-audio advantage established here.

**Decision:** retain the selected Odia STT and TTS for a controlled two-phone
prototype test; do not switch on model-card promises alone. The app's Odia TTS
load check now synthesizes an Odia phrase rather than English `Hello`. Two TTS
threads are an Android experiment, not a shipped speed claim. First have Odia
speakers rate the supplied samples for word intelligibility, pronunciation,
pauses and naturalness. Then measure both models on the target low- and
mid-range phones: clean/noisy live STT WER, model load and peak RAM, warm/cold
TTS time, first audible playback, and end-to-end peer delay. Record failed
utterances as failures. Odia cross-language translation is still unavailable in
the current ML Kit route; this model review does not change that.
