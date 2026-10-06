# Customized Android UI release — 6 October 2026

**Latest app:** [1.6-custom-ui](CURRENT_UI_INTEGRATION_2026-10-06.md) applies the
customized layouts to the remaining active screens. This document records the
preceding 1.5 build.

Install `artifacts/adaptive-ui-2026-10-06/iTantra-1.5-adaptive-ui.apk` on the phone
where the old UI is visible. The app version is **1.5-adaptive-ui**, version code
**6**, package `com.example.itantra`. Previous builds used version code 5 and
the identical `1.4-four-ctc` label even after UI changes.

## What changed

The Talk screen now follows the customized `itantra_light_ui` design with the
iTantra wave mark, appearance toggle, filled SOS control, nearby-device card,
recent-message previews above the speaking controls, labelled language cards,
guarded swapping, segmented speaking modes and a circular microphone with rings.
The persistent five-tab bar is compact. Global typography, rounded shapes and
the Ocean, Forest, Iris and Ember palettes now use the design reference's colors
in both light and dark appearances. Settings retains Light, Dark and System.

The native screens use actual connection, language-pack and message state.
There is no sample connected peer or pretend installed speech pack. Missing
models open Language Packs from the microphone control. Existing recording,
finish/cancel, slide-up locking, SOS confirmation and verification controls
continue to use the app's coordinator.

Recent messages offer compact Play/Stop, copy, retry, acknowledgment and deletion
where those actions apply. Full saved history remains below the controls with
original recording dates and date groups. Notes, seven-day restoration and
saved-text read-aloud remain integrated. Saved entries contain text transcripts;
Play synthesizes their text rather than playing original microphone recordings.

## Correct APK path

The conventional `app/build/outputs/apk/debug/app-debug.apk` was still a 4 October
APK. `app/build` now points to the current build directory, so that conventional
path and the named release APK have the same SHA-256. The release has the same
signing certificate as the preceding TTS replay APK, with an increased version
code for an ordinary app update.

Transfer the named APK to the other phone, open it and choose **Update** if
iTantra is already installed. Open iTantra after installation. The Talk screen
should show the recent-message panel above **Speak & translate** and the ringed
microphone. The version at the bottom of Settings must read **1.5-adaptive-ui**.
If models are absent, the main action correctly reads **Get models**.

## Preserved source

All **21** files in `old ui/snapshot-2026-10-06.json` still match their recorded
SHA-256 values. The partial native port immediately before this refinement is
also copied under `artifacts/adaptive-ui-2026-10-06/before/`.

## Verification

The Android build and complete host-side test suite passed: **464 tests, zero
failures, errors or skipped tests**. This includes the eight light/dark palette
contrast checks and existing recording, storage, recycle-bin, language and
saved-speech regression checks. The APK's ZIP integrity and v2 signing verify.
The build log and `verification.json` are beside the named APK.

The connected Xiaomi test phone rejected USB installation with
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. Installation was not
retried. Native rendering and gestures on a phone were not verified in this
release check, and this task did not install anything on the user's other phone.
Speech accuracy measurements remain the existing evaluation snapshot.
