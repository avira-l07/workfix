# Customized UI in the current Android app

The customized design replaces the layouts in the active Android source. The
current app version is **1.7-connected-ui**, version code **8**. The package remains
`com.example.itantra`.

The named APK is `artifacts/current-ui-2026-10-06/iTantra-1.7-connected-ui.apk`.
The ordinary build output is `app/build/outputs/apk/debug/app-debug.apk`.
Install this new APK as an update on the phone that still shows the older UI.

## Active screens

`MainActivity.kt` opens the updated native screens. The main Talk implementation
is in the canonical `ui/screens/hub/TransceiverHubScreen.kt` source file. The
separate `AdaptiveHubScreen.kt` filename is no longer an active source file.

- Talk, Connect, Messages, Notes and Settings share the branded iTantra header,
  appearance switch and confirmed SOS actions. Sending an SOS still requires an
  actually connected, verified peer.
- Connect has the customized hero, rounded transport selector, nearby-device
  cards, connection details and pairing guidance. Discovery, verification and
  peer-specific chat actions still call the real transport code. The local ID
  and device name come from the live device profile, and the initial transport
  follows the active Bluetooth or Wi-Fi Direct transport.
- Messages uses the customized conversation layout, actual saved peers,
  connection status, latest messages and timestamps. Find devices remains
  available within the content.
- Notes has the customized transcript layout, actual language labels, complete
  readable text, original dates, date groups, Play/Stop and Recycle bin.
- Settings has the customized profile, voice, local-data and appearance cards,
  actual device-ID copying, three brightness choices and four palette previews.
  Existing translation, model management, diagnostics, reset, credits and wipe
  actions remain connected.
- Language Packs and Diagnostics use the matching page headings, spacing and
  theme. Their model controls and measured/unmeasured statistics remain intact.
  The bin uses matching surfaces and restores items to their original dates.

The original microphone recording is not stored. Saved speech entries are
transcripts, and Play reads their text aloud through the installed TTS pack.
The HTML design remains a reference prototype; the APK runs native Compose
controls and actual app state.

## Preserved UI

The original **21-file** `old ui/snapshot-2026-10-06.json` remains unchanged.
The immediately preceding current UI is separately preserved in
`old ui/before-full-integration-2026-10-06/`, with **25 files** and a SHA-256
inventory named `snapshot.json`. This includes the UI, MainActivity and build
configuration before the full screen update.

## Verification

The follow-up [route and action audit](UI_PATH_AUDIT_2026-10-06.md) covers all nine
destinations and their real services. It fixes cancelled-recording submission,
hands-free retry, conflicting Bluetooth/Wi-Fi Direct connection attempts and
diagnostic progress/error reporting. Cancelling a test releases its recognizer
and restores the previous speech session.

The build log, signing output and machine-readable `verification.json` are in
`artifacts/current-ui-2026-10-06/`. The release check verifies the full Android
host-side suite, APK version/signature/ZIP integrity, current-screen labels and
both source backups. The appearance regression includes selected-container
text contrast across all eight light/dark palette combinations.

Phone rendering and gestures are not verified by host-side checks. The earlier
USB installation attempt on the connected test phone was refused; this build
does not retry it or claim installation on the other phone. Speech benchmark
values are unchanged by this UI update.
