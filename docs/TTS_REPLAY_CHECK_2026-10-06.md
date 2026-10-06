# TTS and saved-content replay — 6 October 2026

The latest app APK is now
`artifacts/current-ui-2026-10-06/iTantra-1.7-connected-ui.apk`.
See [the current UI integration](CURRENT_UI_INTEGRATION_2026-10-06.md). The synthesis
evidence below remains applicable; the UI update preserves saved-text replay.

All ten selected TTS packs passed fresh desktop synthesis: Hindi, English,
Bengali, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu and Odia. Each
voice generated the same field phrase twice. The checks verified pinned model
and token checksums, non-empty finite samples, a valid sample rate and non-silent
audio. Marathi used the selected Piper voice and its pinned eSpeak resources;
the other nine used the selected MMS voices.

These results verify waveform generation on the desktop. They do not certify
every input, native-speaker pronunciation or sound through an Android phone.
No Android device was connected for this check.

## Play the content again

**Play speech** now appears on saved Talk messages, chat messages and private
Notes. During generation or playback, that item's control becomes **Stop**.
After playback finishes, tap **Play speech** again as often as needed. Install
the item's language **Receive (TTS)** pack through Settings → Language Packs
first. Missing packs and synthesis errors are shown next to the control.

Replay speaks the displayed message's language, including a successfully
translated outgoing message. Newly saved private Notes retain the original
transcript and spoken language. For older translated Notes, the corresponding
local message supplies the displayed language when it is still available;
legacy Notes without that message retain their original stored language label.
Marathi input must use Devanagari; unsupported input is rejected visibly.

This is local read-aloud of saved text. Replay does not send packets, change
delivery acknowledgments or store original microphone recordings. It pauses
hands-free detection to avoid capturing its own output. Recording, received
speech and unresolved emergency alerts take priority. Navigating away,
backgrounding the app or deleting the playing item stops manual replay.

## Evidence and checks

- Run `C:/Python314/python.exe tools/check_saved_tts.py` from the repository root.
  It uses the existing local models and audit runtime, without downloads.
- `artifacts/tts-replay-2026-10-06/all-language-tts-check.json` records the two
  synthesis trials for each language. Individual JSON files and one sample WAV
  per language are in the same directory.
- The complete Android host-side suite passed: **464 tests, zero failures,
  errors or skipped tests**. The new replay checks cover repeat playback, correct language
  selection, missing-pack rejection, cancellation during playback and cleanup
  after cancellation during model loading. It also checks that only one TTS
  voice remains loaded while switching languages.
- The final Android build log and `verification.json` in that directory record
  the build, full test counts and SHA-256 of `iTantra-tts-replay-debug.apk`.

On a phone, install a Receive pack, record a short Note in that language, tap
Play twice, and check Stop during both preparation and speech. Repeat for all
ten languages with a listener who understands each language. Also replay a
translated message and confirm that its delivery status stays unchanged.
