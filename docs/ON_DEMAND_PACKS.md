# On-demand language packs

The default **1.2-on-demand** debug APK is about **87 MB**. It includes the app,
speech runtime and small VAD model, but no STT or TTS language model weights.
The earlier 1.4 GB APK bundled every language. The proposed 35–40 MB base size
was too low for the actual app and native runtime.

To use speech, connect to the internet once, open **Language Packs**, select the
language, and tap **Prepare missing models**. Wait for both **STT READY** and
**TTS READY**. After the download completes, speech recognition and synthesis
use local files. For Hindi, explicitly choose **Hindi** under **Mic language**;
Auto uses the shared multilingual Tiny engine instead. Download English as well
before testing English speech. Allow extra free storage while downloads are
staged and installed.

| First-use selection | STT download | TTS download | Approximate total |
|---|---:|---:|---:|
| Hindi | 375.4 MB | 114.0 MB | 489 MB |
| English | 103.6 MB shared Tiny | 114.0 MB | 218 MB |
| Another supported language, after shared Tiny is installed | Reuses Tiny | About 114 MB | About 114 MB |

The installer size and later model downloads are separate. Hindi plus English
therefore requires about **707 MB** of model files in internal app storage, in
addition to the APK and temporary download space. A Hindi-plus-English bundled
demo APK would be substantially larger than the suggested 400–500 MB. Use the
small default APK unless the demonstration must work before any download.

Offline **translation** is a separate component. Its **MT READY** badge must be
checked for the relevant languages; STT and TTS readiness alone do not imply
translation readiness. Odia STT is currently unsupported. Other language
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

The app verifies SHA-256 checksums before activating downloaded models. A
download needs network access; after installation, STT and TTS do not.
