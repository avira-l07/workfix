# App function audit — 8 October 2026

Build: **1.14-functions-audit (15)**. This audit extends the earlier connectivity, message-language and speech-workflow fixes. Verification is through code review, host regression tests and an APK build; hardware checks are listed separately below.

## Confirmed problems fixed

| Function | Failure before this change | Behavior after the fix |
|---|---|---|
| Operator name | Settings saved the operator name, but no caller updated the device profile used by the other phone. | Settings updates the persisted profile and sends an encrypted profile update when a session is verified. Unrelated settings changes do not resend it. Clearing/resetting the name restores the default while preserving the device ID. Control characters are removed before publishing. |
| Typed-message source language | With no STT engine loaded, the legacy active-language value could come from a saved-message TTS voice, or default to English despite the selected source. | Use the active mic language, falling back to the selected source. A replay voice cannot determine the typed translation route. Recipient and target preferences are captured when Send is requested. |
| Retry sending | Retrying a HIGH-priority message called the send method with its default NORMAL priority. Its status could continue saying “Retrying delivery” after delivery. | Keep the original priority and clear the obsolete transport status. Existing original-peer and verified-session restrictions remain enforced. GPS retries retain the original binary location and fix timestamp. |
| History after restart | A GPS request persisted as PACKET_ENCODING was not included among interrupted local states. It could remain “Acquiring satellite fix” forever and could not be trashed. | Settle that interrupted request as an error after restart. It can be moved to the bin without sending a packet. |
| GPS listener lifecycle | Registration was marked complete only after the platform call returned. Cancellation or an exception during partial registration could leave the listener registered. | Release the listener in the surrounding finally block after registration returns, including cancellation and partial-registration failures. Callback completion is serialized. |
| GPS freshness | The timeout fallback accepted any last-known fix; a future wall-clock timestamp also passed the fast-path age check. | Reuse only a fix younger than 30 seconds. Prefer the Android monotonic fix clock when available; reject future or missing timestamps in the wall-clock fallback. No satellite fix produces a visible failure rather than silently sending old coordinates. |
| GPS validation | The receiving side rejected invalid coordinates, but the sending side could transmit invalid sensor results. | Apply the same finite-value, latitude, longitude and nonnegative-accuracy checks to both directions. Invalid local fixes remain local errors. |
| Saved language selections | Actions used StateFlow UI defaults until the flows had a subscriber. Applying or retrying too early could restore Hindi/English selections or request an unwanted mic model. | Read the repository's current selections for staging, apply and retry. A listen-only selection remains listen-only. |

## Coverage

Reviewed the live Settings → device profile → verified peer route; chat Send/Retry and GPS permission/provider/send/receive paths; message persistence after restart; recycle-bin deletion, seven-day expiry, restore and permanent deletion; language-pack staging/provision/cancel actions; screen navigation and disposal; and the private-data wipe implementation. The full existing regression suite also checks connectivity, encryption, message isolation, emergencies, STT/TTS lifecycles, private storage and provisioning.

New regression checks are in `AppFunctionsAuditTest` (6), `GpsFunctionsAuditTest` (6), and two added checks in `SelectiveLanguageProvisioningTest` (14 new checks total). GPS age and coordinate checks exercise the production validation functions. Adapter tests exercise lifecycle handling, including cancellation during registration; they do not simulate satellite reception or Android's native permission UI.

Verification completed: **549 host tests passed; zero failures, errors or skips**. The offline debug APK build succeeded. APK CRC and Android v2 signature verification passed, and the signing certificate matches the previous 1.13 build. The saved APK is 85,682,417 bytes; SHA-256 is `aae3896c34175ca55807da49c35ddca110b8b93b10c06ca9f9850210cf5ccdf7`.

Build/test/signature results are recorded in `verification.json`, the build log, saved JUnit XML files and the signature log. The verification script checks the saved APK and test evidence independently of the temporary build directory. The initial run's log is retained: its sole failure was a new test counting the automatic connection profile exchange as a name-change update; that test now waits for the initial profile/capabilities exchange and verifies subsequent encrypted profile contents with a valid test device ID. No phone appeared in the read-only ADB inventory.

## Phone checks still needed

1. Install this APK on both test phones. Connect and verify them, then change the operator name: the other phone should update without reconnecting. Clear the name and check that the device ID stays the same.
2. Change the mic/source language, play a saved message in another language, then send typed text. Confirm the source/target languages and the translated meaning separately.
3. Disconnect and retry a HIGH-priority message after reconnecting its original peer. Confirm it keeps priority and no longer displays the previous connection failure after delivery.
4. Start GPS sharing and close the app during acquisition. Reopen it: the card should report interruption and allow deletion. Outdoors, obtain a fresh fix, then disable location/revoke permission and retry; stale coordinates must not be silently sent as a current fix.
5. Reopen Language packs with custom selections and immediately use Apply or retry a listen-only pack. Check that the selections survive and only the requested speech components download.
6. Delete and restore a note/message, restart both apps and check dates, history and the bin. Restoration must not resend a message.

The wipe route was reviewed and is covered by host storage tests; no private data was wiped on a phone. This audit does not establish STT accuracy, translation quality, voice intelligibility, radio/GPS behavior or battery performance. The Hindi–Gujarati human reference review and translation-engine comparison remain separate work.
