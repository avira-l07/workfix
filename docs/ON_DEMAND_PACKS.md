# On-demand language packs

The default **1.4-four-ctc** debug APK is about **87 MB**. It includes the app,
speech runtime and small VAD model, but no STT or TTS language model weights.
The earlier 1.4 GB APK bundled every language. The proposed 35–40 MB base size
was too low for the actual app and native runtime.

To use speech, connect to the internet once, open **Language Packs**, select
languages under **Speak (Mic)** and/or **Receive (TTS)**, then tap **Apply &
provision selected languages**. The selected role downloads only the STT and/or
TTS files it needs. Check the **STT**, **TTS** and **MT** readiness indicators.
After provisioning, select the installed **Mic language** explicitly. Auto
still uses the separate multilingual Tiny model. Allow extra free storage
while downloads are staged and installed.

| First-use selection | STT download | TTS download | Approximate total |
|---|---:|---:|---:|
| Hindi | 197.7 MB CTC | 114.0 MB | 311.7 MB |
| English | 174.6 MB CTC | 114.0 MB | 288.6 MB |
| Tamil | 197.7 MB CTC | 114.0 MB | 311.7 MB |
| Telugu | 197.7 MB CTC | 114.0 MB | 311.7 MB |
| Odia candidate | 197.7 MB CTC | 114.0 MB | 311.7 MB |
| Another supported language, after shared Tiny is installed | Reuses Tiny | About 114 MB | About 114 MB |

The installer size and later model downloads are separate. Hindi plus English
requires about **600 MB** of model files in internal app storage. All four voice
languages require about **1.224 GB** of speech files; adding Odia uses about
**312 MB** more, plus translation models, in
addition to the APK and temporary download space. A Hindi-plus-English bundled
demo APK would be substantially larger than the suggested 400–500 MB. Use the
small default APK unless the demonstration must work before any download.

Offline **translation** is a separate component. Its **MT READY** badge must be
checked for the relevant languages; STT and TTS readiness alone do not imply
translation readiness. Odia STT has a dedicated on-demand CTC candidate with
small desktop evidence; Android performance is unverified. Other language
accuracy and phone performance still require device testing by the owner.

To switch packaging modes locally, run
`python tools/configure_model_bundle.py minimal` (default), `demo` (Hindi and
English), or `all`, then rebuild. The optional modes need their verified model
files already in the ignored `models/bundled/language_packs` cache; the default
minimal build does not. To build the default APK:

```powershell
python tools/configure_model_bundle.py minimal
./gradlew.bat testDebugUnitTest assembleDebug
```

The four established languages and the Odia candidate use dedicated CTC models
only when explicitly selected as the mic language; Auto still needs the shared
Tiny model. Four-way voice translation
requires device testing. See `docs/FOUR_LANGUAGE_VOICE.md` for setup and
`docs/FOUR_LANGUAGE_MODEL_REPORT.md` for accuracy, speed, storage and licensing
limits.

The app verifies SHA-256 checksums before activating downloaded models. A
download needs network access; after installation, STT and TTS do not.
