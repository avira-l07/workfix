# Native UI route and action audit

Source audit: 6 October 2026. Update: **1.7-connected-ui**, version code **8**,
package `com.example.itantra`. Install
`artifacts/current-ui-2026-10-06/iTantra-1.7-connected-ui.apk` as an update.

## Routes reviewed

All nine `AppDestination` entries have active `MainActivity` screen branches.
The five bottom tabs directly open Talk, Connect, Messages, Notes and Settings.
Chat keeps its selected device and returns to Connect or Messages. Language Packs
and Diagnostics return to their originating screen; the bin returns to Talk,
Notes or Settings. Return destinations and selected chat identity use saved state.

| Screen / shared control | Reviewed path to actual behavior |
|---|---|
| Talk | Connect, chats, notes, packs and bin shortcuts; microphone/target selection through AppGraph and the repository observer; guarded language swap; hold/release, slide-lock, finish/cancel; continuous start/pause/resume; incoming emergency acknowledgment |
| Talk history | Live coordinator messages; local/remote/error/critical filters; original dates and date groups; Copy to clipboard; guarded Retry and human ACK; local TTS Play/Stop; confirmed move-to-bin and Undo |
| Shared header | Persistent light/dark setting, Settings route, emergency choices and confirm dialog; sending requires a verified connected peer |
| Connect | Live Bluetooth discovery, bonding/connection, errors and permissions; Wi-Fi Direct discovery/connect/disconnect; exact-device SAS comparison/rejection; exact-device chat; real local profile ID/name |
| Messages | Room-backed verified peers and messages; connected status; isolated conversation opening; Find devices and empty-state Talk actions |
| Chat | Exact peer identity, encrypted-session send guard, typed-message UTF-8 size check, microphone capture, receive-language selection, local Play/Stop, location permissions/send, coordinate copying and Maps fallback |
| Notes | Actual Room transcripts/languages/dates; Play/Stop; confirmed deletion, bin route and empty-state Talk action |
| Recycle bin | Both deleted messages and private notes; original dates; exact seven-day restore deadline; confirmed permanent deletion; restore does not resend |
| Settings | Real operator preference, copied local ID, outgoing target language, packs/diagnostics/bin routes, confirmed preference reset, persisted three brightness modes/four palettes, external model credits, guarded private-data wipe |
| Language Packs | Real repository and view model; search/filter, staged Send/Receive selections, Apply/provision, active microphone selection, missing-model/retry/cancel, translation provisioning and outgoing target/reset |
| Diagnostics | Live app/device/link summaries, saved benchmark results, real ten-language phone self-test, English STT benchmark, EN→HI translation provisioning and document-provider JSON export |

Verified encrypted profiles are persisted by the coordinator, so the conversation
list does not depend on first opening Chat. Model management uses app-private
storage and validates model checksums. Full model weights are provisioned on the
phone; required application assets are packaged in the APK. Field evidence uses
Android's document picker, rather than a hardcoded computer path.

The HTML prototype is not the runtime application. Optional preview hooks and
unavailable-feature descriptions are not treated as functioning app controls.
The production Settings screen receives its real view model, and all visible
navigation controls receive actual callbacks from MainActivity.

## Gaps fixed

- A cancelled chat press used to follow the same submission path as release.
  Chat and Talk now cancel interrupted gestures/capture when leaving or
  backgrounding the screen. Explicit release or Finish still transcribes.
  Capture indicators follow actual message state, including the 60-second limit.
- A stale capture ID could let later cancellation change an already completed
  message. Cancellation now requires an active capture job and wipes its audio.
- After failed hands-free startup, tapping the microphone could call pause on
  an OFF engine. It now retries the service-backed start and reports actual
  OFF/ERROR/PAUSED state in the microphone label.
- Bluetooth connection/discovery cannot run through an active Wi-Fi transport.
  Connect offers an explicit Wi-Fi disconnect, disables conflicting Bluetooth
  actions and checks the actual transport again in their callbacks. Bluetooth
  permission/connection failures are surfaced through the existing error flow.
- English diagnostics now show running/error states, block concurrent tests and
  require a disconnected idle session. A private benchmark recognizer is unloaded
  on completion/cancellation. Missing/empty recordings fail visibly instead of
  silently shrinking the benchmark. Leaving Diagnostics cancels its jobs; the
  ten-language self-test restores the preceding speech session even when cancelled.

## Evidence and remaining phone checks

The Android build and all **467 host tests passed**, with zero failures, errors
or skips. **59 required packaged asset paths** passed, including all ten language
manifests/self-test recordings and all 24 English benchmark recordings. APK
integrity/signature checks passed; its signing certificate matches the preceding
build, and the ordinary build-output APK matches the named update.

`artifacts/current-ui-2026-10-06/` contains the build/test log, signed APK,
`verification.json`, `asset-path-verification.json`, route/action inventory and
runnable packaging checks. The original 21-file UI backup and preceding 25-file
snapshot retain their recorded SHA-256 values.

Host tests and packaged-asset checks cannot prove touch rendering, microphone
permissions, audible playback, Bluetooth discovery or two-phone Wi-Fi Direct
behavior on the user's other phone. No phone installation or field pass is
claimed by this source audit.

On that phone, check these sequences after installing the update:

1. Talk → packs → Back; Settings → packs/diagnostics/bin → Back; Messages →
   selected chat → Back; Notes → bin → Back. Confirm the expected return screen.
2. Hold/release, slide-lock/Finish, Cancel, move away while recording and let a
   recording reach its limit. Interrupted capture must not send a message.
3. Deny and then grant microphone/Nearby permissions. Retry hands-free startup
   and Bluetooth discovery after granting them.
4. Pair two phones, compare their six-digit codes, exchange text/voice/location
   and reopen the saved conversation. Disconnect Wi-Fi before changing to Bluetooth.
5. Play a saved transcript twice and Stop during preparation/speech. Delete and
   restore it; its original date must remain. Check permanent-delete confirmation.
6. Run a diagnostic, try the second test button, then leave the screen. Reopen
   Talk and confirm the preceding microphone/voice selection is restored.
7. Export the field JSON, change brightness/palette, restart the app and confirm
   preferences and saved history remain.
