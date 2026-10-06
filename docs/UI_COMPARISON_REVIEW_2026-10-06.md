# Customized UI versus archived and current Android UI

**Follow-up:** The native UI integration and seven-day recycle bin were implemented
after this assessment. See [UI_INTEGRATION_2026-10-06.md](UI_INTEGRATION_2026-10-06.md)
for the resulting behaviour, backup and verification. Findings below describe
the code before that integration.

Reviewed 6 October 2026. Scope: `old ui/`, `itantra_light_ui/`, current native
Compose screens and their navigation/transport callbacks. No production code,
model, APK or archived UI was changed in this review.

## Assessment

The customized preview is a useful design direction: five visible phone tabs,
prominent labelled SOS, a clear microphone control, readable cards, separate
conversations, model-management routes and appearance options. Keep that
direction. The app currently has its palette/mode settings, but the remaining
HTML layout and behaviour have not all been implemented in Compose.

All 21 Kotlin files in the archived UI have current counterparts. No named
function from those files disappeared in the source comparison. This is an
inventory check, not proof of behavioural parity. Existing secure pairing,
encrypted conversations, local notes, model management and emergency sending
should be retained while integrating the layout.

## Current checks

Fresh executions of all five local browser checks passed:

| Check | Result |
|---|---|
| `verify.cjs` | Eight screens at six widths from 320 to 1440 px; no horizontal overflow or browser errors; recording simulation, notes, escaped text, downloads, SOS confirmation, wipe and dock checks passed |
| `verify-navigation.cjs` | Five phone tabs and eight routes; no missing rendered action handlers at 320/390 px |
| `verify-themes.cjs` | 128 screen/palette/mode/viewport combinations; tested text/status contrast pairs, persistence, System mode and reduced-motion checks passed |
| `verify-connection.cjs` | Multiple sample peers, peer-specific code verification, rejection and switch confirmation passed |
| `verify-hub.cjs` | At 390 x 844, microphone and two recent messages fit above navigation; SOS and guarded language-swap checks passed |

Rendered captures of all eight preview destinations were inspected. Full-page
captures place the fixed navigation at its original viewport boundary; that
does not mean all content below it is inaccessible when scrolling. Fresh
captures and browser reports are in `itantra_light_ui/review/`.

These browser tests exercise sample state. They do not prove actual microphone,
Bluetooth, Wi-Fi Direct, downloads, translation, Android rendering or playback.
No phone/emulator was run for this review; native layout conclusions below
come from source. Existing Android unit/build results were not rerun here.

## Remaining work, in priority order

1. **Implement the approved navigation/layout in Android.**
   `MainActivity.kt:751` hosts the existing destination switch, with no common
   five-tab navigation. `TransceiverHubScreen.kt:321` still uses a hub overflow
   menu. The five-tab design, Settings shortcuts, language swap and presentation
   of the preview cannot be treated as an already-shipped APK feature.
   Reuse the current Compose screens and real callbacks; do not turn the
   simulated HTML actions into a production WebView.

2. **Make the native hub Retry action real.**
   `TransceiverHubScreen.kt:1702` displays Retry with `.clickable { }`, so tapping
   it does nothing. The preview's simulated retry works, which hides this gap.
   Retry must validate the original recipient, secure session and retryable
   failure, and send the preserved message rather than a failed recognition
   placeholder. Existing `sendVoiceMessage` is an implementation building
   block, not authorization to resend every failed row indiscriminately.

3. **Show accurate, distinct message states.**
   `TransceiverHubScreen.kt:187` maps most states to SENDING; the non-failure
   footer at line 1714 uses a success-coloured double check even during
   processing/recording/waiting. Acknowledged or playback-confirmed messages can
   also receive the SENDING label. Use the actual enum for recording,
   processing, awaiting confirmation, sending, sent, device-delivered,
   playback and human acknowledgment. A device ACK must not imply a human
   response. The existing chat already has some state-specific icons.

4. **Update the preview's language capability information.**
   `itantra_light_ui/app.js:107` hardcodes Odia STT as unavailable and disables
   its microphone controls. Current `AdditionalSttModel.kt:68` provides a
   manually selected Odia CTC candidate; native pack readiness is based on the
   manifest/repository. Its device quality remains a separate test.
   Production labels must come from actual model and translation capabilities,
   with separate STT/TTS/MT readiness. Do not equate downloading one file with
   all three engines being ready. Malayalam/Odia cross-language translation
   limitations remain distinct from Odia STT availability.

5. **Make microphone controls accessible without a hold gesture.**
   Native microphone Boxes use `pointerInput` at hub lines 1135/2043 and chat
   line 270, with no semantic click/start/stop action on those controls.
   A microphone icon description alone does not provide an accessible action.
   Add semantic Start/Finish/Cancel actions and a keyboard/TalkBack path while
   preserving press/slide behaviour. Verify focus order and announced state
   on an Android device. Include font scaling and touch-target checks.

6. **Finish the native dark-mode colour conversion.**
   `TransceiverHubScreen.kt:2041` gives the idle continuous-listening microphone
   a fixed pale-green background (`#F0FDF4`), while its dark-theme success icon
   uses `#6EE7B7`. The calculated contrast is approximately 1.46:1. Use the
   semantic SuccessContainer/OnSuccessContainer pair. The successful browser
   theme checks and native token tests do not cover this fixed native colour.

7. **Carry over navigation convenience and selection consistency.**
   Current Settings has no Language Packs/Diagnostics shortcut callbacks;
   those are available from the hub. Its target-language row offers only
   Auto/Hindi/English, while other screens offer the wider supported catalog.
   Reuse the same language picker and connect Settings directly to the real
   pack and diagnostic destinations. Keep destructive wipe at the end of the
   page, as in the customized design.

8. **Remove obsolete engine wording and verify state restoration.**
   `DedicatedChatScreen.kt:251` says “Whisper STT” even when a dedicated CTC
   model is active. Use “Recording voice” or the actual active model.
   `MainActivity.kt:734` and subsequent remembered conversation state are not
   saved navigation state. Check rotation/recreation, Back navigation and
   keyboard appearance before shipping. Do not silently resume microphone
   capture after recreation.

## Additions that would improve actual use

| Addition | Benefit | Minimal implementation direction |
|---|---|---|
| Offline setup checklist | Helps users prepare before connectivity disappears | Show microphone permission, required STT/TTS/MT files, selected languages and peer verification; route each missing item to its existing screen |
| Optional transcript review/edit | Lets users correct recognition errors before transmission | User-selectable PTT review mode with Edit/Send/Discard; preserve original transcript, recheck UTF-8 bounds and translate corrected text before sending; retain fast default and existing emergency safeguards |
| Quick phrases | Useful in noise or when speaking is difficult | A small local list of editable text phrases sent through the existing secure message path; keep routine phrases separate from critical SOS presets |
| Conversation/note search | Makes saved information easier to find | Filter existing local conversation/notes data; retain peer isolation and local-only storage |

The first three provide the strongest practical value. Keep the prominent
microphone and SOS controls uncluttered. Palette choices, reconnect links,
copy/GPS cards, model status and evidence export already exist in some form;
do not rebuild them as duplicate features.

## Completion order

First close Retry, state-label, capability and accessibility gaps. Then port
the navigation and layout while retaining the existing backend. Add the offline
checklist and optional review mode. Build a fresh APK and inspect all eight
destinations on phones with light/dark modes, large text, denied permissions,
missing packs, disconnect/reconnect, keyboard and screen-off transitions.
Verify two-phone recording, secure delivery, receiver playback and SOS
acknowledgment before calling the design complete.

The Tamil training comparison is independent: keep the existing source model
because all four pilots regressed on the same DEV subset. A visual redesign
does not change recognition quality.
