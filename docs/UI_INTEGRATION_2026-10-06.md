# Native UI integration and seven-day recycle bin

**Latest APK:** [1.7-connected-ui release](CURRENT_UI_INTEGRATION_2026-10-06.md).
That follow-up integrates all customized screens and audits their actions and
navigation. The verification below describes the earlier build.

Implemented 6 October 2026 in the Android Compose app. The customized HTML preview
remains a design reference; the app uses native controls and the existing speech,
translation, secure connection and storage implementations.

## Backup

Before editing, all 21 current UI Kotlin files, including MainActivity, were copied
to `old ui/`. `old ui/snapshot-2026-10-06.json` records their SHA-256 hashes; all
backup hashes were rechecked successfully. The earlier contents of `old ui/` were
preserved in `artifacts/ui-integration-2026-10-06/backup/previous-old-ui/`.

## Integrated behaviour

- Native Talk screen with language selectors, guarded swapping, push-to-talk,
  slide-up recording lock, finish/cancel controls and hands-free listening.
- A compact microphone action remains reachable when scrolling through history.
  The main microphone also has an accessible click action and keyboard activation.
- Persistent five-tab navigation: Talk, Connect, Messages, Notes and Settings.
  Chat context and navigation return destinations survive activity recreation.
- Settings links to Language Packs, Diagnostics and the Recycle Bin. All catalog
  target languages are available. Existing appearance modes/palettes are retained.
- Rounded cards across the existing connection, conversation, notes, packs,
  diagnostics and settings screens. Existing operational controls are retained.
- Home message history and private voice notes are grouped into Today, Yesterday,
  This week, This month and older months, with the full local date and time.
- Messages distinguish recording, transcription, sending, device delivery,
  playback and human acknowledgment. Retry reconnects the original verified peer.
  Measured frame savings are shown only when actual frame and PCM counts exist.
- SOS confirmation, received emergency acknowledgment and alarm-silencing controls
  use the existing coordinator. Unresolved critical or active recording/sending
  items cannot be moved to the bin.

## Deletion and restoration

Use the delete icon in Talk history or Notes, then confirm **Move to bin**.
Open **Recycle bin** from Talk, Notes or Settings. **Restore** returns an item to
its original recording date without resending it or starting playback. The home
delete action also offers **Undo**. Permanent deletion requires confirmation.

Retention is seven elapsed days from deletion, rather than seven calendar dates.
Restoration stops working at the deadline. Expired records are physically removed
when the app next opens and during periodic cleanup while it is running. The bin
shows deletion time and remaining days. Existing history is preserved through the
additive encrypted Room database migration from version 5 to 6.

Deletion affects only local history. A peer's copy is not recalled. Private notes
and chat messages are separate records, each with its own deletion/restore action.
Items permanently deleted before this feature was installed cannot be recovered.

**Voice-content scope:** saved voice entries are text transcripts. Original
microphone audio remains in memory; original recording storage and playback were
not introduced by this change. A subsequent update adds Play/Stop to read saved
text aloud with TTS; see [the TTS replay check](TTS_REPLAY_CHECK_2026-10-06.md) for
its newer APK and verification. Cancel discards an unfinished recording before
transcription or sending.

## Verification

The full Android host-side suite passes: **459 tests, zero failures, zero errors,
zero skipped**. New checks cover the exact seven-day deadline, protection of active
and unresolved critical messages, durable deletion across coordinator restart,
restoration without sending, original date preservation, expired-record cleanup,
permanent deletion, late delivery updates, and Room 5-to-6 row preservation and
voice-note SQL behaviour. Database schema version 6 is exported with the source.

The verified debug APK is
`artifacts/ui-integration-2026-10-06/iTantra-new-ui-debug.apk`. Build logs and
`verification.json` are in the same folder. The existing
browser preview checks passed before integration, but they test sample data rather
than Android behaviour. No Android device was attached and no runnable emulator
was available, so native screen rendering, microphone gestures, Bluetooth/Wi-Fi
and real speech playback still require verification on a phone.

On a phone, verify a local Talk transcript's date, move it to the bin, restore it,
and confirm it returns to the same date. Repeat for a private note, then check
recording finish/cancel, scrolling microphone access, theme changes and connecting
a verified peer before sending.
