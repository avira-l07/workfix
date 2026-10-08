# iTantra 1.12 — message languages and chat GPS

Install `iTantra-1.12-message-languages.apk` as an update. It includes the earlier connectivity and input fixes. The APK uses the same signing certificate as version 1.11. Installed packs and saved messages are retained by an ordinary update.

## Share GPS in chat

1. Connect and verify the peer on both phones, then open its chat.
2. Tap the **pin beside the microphone**. Allow Location and enable GPS.
3. The app shares a satellite fix containing coordinates, accuracy and the fix time. The receiving location bubble has **Open in Maps** and **Copy coordinates**. A map app may need downloaded maps for offline viewing.
4. If GPS is disabled, unavailable indoors, or permission is denied, the message shows the error. No location is invented. Retries preserve the original location packet and coordinates.

This is an individual location message, not continuous location tracking. The chat pin and encrypted GPS route already existed; this update keeps them connected and adds the shared message menu to location bubbles.

## Choose text and voice languages for one message

1. Tap **⋮ → Translate & listen** on a chat bubble or saved-message card.
2. Select one or several language chips. Each selected language has its own text result, **Copy**, and **Play speech / Stop speaking**.
3. Tap Play again whenever you want to hear that version again. Only one voice plays at a time. Closing the sheet or deleting the message stops its playback.
4. Selections belong to that message's menu, without changing the conversation's Receive language or microphone language.

The menu also provides **Play displayed text**, **Copy text/coordinates**, eligible **Retry sending**, and **Move to recycle bin**. Chat deletion requests confirmation; existing history deletion and emergency acknowledgement protections remain. The shared `MessageActionsButton` is the place to add future message actions.

These conversions are local views: they do not resend the message, alter delivery state, or overwrite saved history. Each chosen result uses the same known source. For a local translated message, that source is its retained original transcript. Older received-message history does not retain the original text's language, so the known displayed text/language is used instead of guessing.

## Language availability

The picker lists the app's ten languages. Cross-language translation depends on the engine and installed offline translation models. The current ML Kit engine supports Hindi, English, Bengali, Gujarati, Marathi, Kannada, Tamil and Telugu. Unsupported pairs, failed inference and missing models show an explanation and cannot be played as a successful conversion. A same-language choice needs no translation model.

Voice playback additionally requires that language's **Receive (TTS)** pack. A missing voice leaves the translated text usable and shows which pack is needed. This feature uses the existing neural translation engine; it does not hardcode ordinary phrases or improve the engine's underlying translation accuracy.

## Verification

`verification.json` records the full host test result, APK hash, ZIP integrity, signature and certificate continuity. `test-results/` contains the saved JUnit evidence; `build-verification.log` contains the full build result.

Final result: **513 tests passed; zero failures, errors or skipped tests**. APK assembly, ZIP integrity and signature verification passed. The signing certificate matches version 1.11.

Eight new `MessageLanguagesTest` cases check independent source routing, unchanged history/preferences, same-language bypass, failed-result rejection, chosen voice/text and repeat synthesis, missing voice behavior, deletion during translation, and exclusion of GPS/unknown/unfinished inputs. Existing GPS codec, encrypted send/receive, permission and lifecycle tests also run.

No phones were attached. Actual GPS fixes, Compose taps/layout and audible native voice output have not been tested on a phone. Translation/TTS unit tests use controlled model engines and do not measure translation or speech quality.
