# STT and TTS workflow audit — 7 October 2026

The audited build is **1.13-speech-workflow (14)**. This is a code and host-test audit. No Android phone was attached. It does not establish recognition accuracy, translation accuracy, voice intelligibility, or performance on a phone.

## Problems fixed

| Route | Problem | Result |
|---|---|---|
| Microphone permission, initialization and read failure | The upstream exception terminated the shared capture without notifying its subscribers. The UI could stay on “Listening…”, and capture could not recover. | Materialize failures before sharing and throw them in each consumer. Recording ends with a visible error; continuous listening stops its microphone service. A subsequent recording can retry. |
| Microphone stop | A blocking native read could delay cancellation and resource release; the source also stayed subscribed for an extra second. | Use nonblocking reads with a short idle delay and release capture when its last subscriber stops. |
| STT model loading | A newly allocated engine was not reachable by cleanup if loading failed or was cancelled. The old source-language state could remain visible. | Release the partially loaded engine under a non-cancellable cleanup context and clear its language state. |
| Record, cancel, record again | Capture fed directly into the shared recognizer. A delayed cancel/reset could interfere with the next utterance or continuous recognition. | Keep capture samples separate. Feed/reset/finalize one isolated utterance under the language-session lock. Serialize record/stop/cancel transitions and reject stale stop IDs. |
| Change language during recognition | Settings could unload the recognizer while the pipeline held it. | Hold the session lock during decoding. A capture whose engine was already replaced asks the user to record again rather than decoding with another language. |
| AUTO STT configuration | A different base-language request reused the previous configuration even though its Whisper translation mode can depend on that language. | Reuse only an identical language, mode and target configuration. |
| AUTO source → translation route | The preset mic language could become a forced translation target even when AUTO detected a different source and no target was selected. | Resolve the destination from the detected language and the target/peer preferences captured for that utterance. |
| Recording while received speech plays | The microphone could capture this phone's speaker output. An old playback cooldown could also resume VAD over a newer activity. | Use half duplex: block a new PTT capture during received playback and cancel capture before starting received audio. Resume VAD only when recording, processing and playback are idle; ignore stale cooldown generations. |
| Continuous VAD lifecycle | Feed/reset/release could overlap. Configuration updates could return without applying the new settings. | Synchronize native VAD operations; restart it when applying new settings and preserve the paused state. |
| Continuous energy fallback | The maximum utterance length was checked only after a silent frame. Uninterrupted speech could grow indefinitely. | Enforce the configured length limit during uninterrupted speech too. Ignore empty audio frames. |
| Very short taps | The silence gate created an error card before the intended short-tap discard. | Discard captures shorter than 300 ms first. |
| Live TTS, alerts and evaluation | Some callers synthesized through a captured voice engine while its language could change or its native engine could be released. | Route synthesis through the locked session. Resolve the native engine inside its own synthesis lock and require the request language to match. STT stays independent of TTS selection. |
| TTS output | Empty output was checked in some routes, but silent, non-finite or incompatible PCM could still be treated as valid. | Use the same finite, audible, mono PCM validation for received messages, replay, evaluation and self-tests; validate voice-load smoke output too. |
| Speaker completion | Cancellation and drain errors were swallowed. Stalled or stopped playback could be reported as completed. | Propagate cancellation; wait for final queued frames using a monotonic deadline; treat stopped or timed-out playback as a failure. Respect audio-focus denial and loss. |
| Missing receive voice | Some languages notified the sender of TTS failure; others only updated local history. | Send playback-failure status for every supported language, while preserving the received text. |
| Emergency reminders | Replay used the currently loaded voice and an English prefix regardless of the message language; an interrupted reminder could leak its audio sink. | Use the displayed text's language, keep the voice locked, release the sink in `finally`, and stop/join reminder playback before another incoming message. |
| Evaluation screens | A previous sentence's audio could remain cached, repeated taps could overlap playback, and a score could be recorded before successful playback. Benchmark capture could share recognizer buffers with normal recording. | Clear cached speech on language/sentence/reset changes; guard playback and score submission; cancel playback on screen cleanup. Benchmark captures also use isolated audio and the locked STT session. |

The shared-flow failure behavior is documented in the official [Kotlin `shareIn` API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/share-in.html). Fixes use the existing Android and coroutine APIs; no speech or translation model was replaced.

## Verification

**535 host tests passed: 0 failures, 0 errors, 0 skipped.** This includes 22 new speech-workflow checks. The offline Android build succeeded. APK archive integrity and v2 signature verification passed; its signing certificate matches version 1.12, allowing an update that preserves app data. The delivered APK is `iTantra-1.13-speech-workflow.apk` (85,986,169 bytes).

The exact test totals, APK hash, application version and signing certificate are in [verification.json](<D:/new itantra/artifacts/speech-workflow-2026-10-07/verification.json>). The full successful build log, JUnit XML, metadata and signature verification are saved beside this report, independently of the reusable build directory.

New checks exercise failed/cancelled STT allocation, model changes during decoding and synthesis, isolated utterances, microphone error propagation and retry, prompt capture cleanup, invalid PCM, final-frame drain/cancellation/timeouts, rapid cancel/re-record, a new capture while an old decode finishes, short taps, speaker-to-mic suppression, AUTO source routing, missing voice reports across all ten languages, and continuous fallback limits/configuration.

## Phone checks still needed

1. Install the new APK over the existing app. Open Language Packs and confirm the intended mic and receive voice packs are installed.
2. In airplane mode, record and replay several messages in each installed language. Verify transcript script, destination language, the first word and the final syllable. Press Play repeatedly, then Stop.
3. Cancel a recording and immediately record another. Only the second utterance should be transcribed or sent. A quick silent tap should leave no error card.
4. Change the mic language while a capture is pending. Finish the capture: it should either complete with its original engine or ask you to record again, never use a replacement language silently.
5. Deny microphone permission or disable microphone access while capturing. Confirm “Listening…” ends; re-enable access and retry.
6. On two connected phones, send speech while the other phone is recording. Received playback should stop the local capture; it must not be transcribed and sent back as an echo. Also try saved-message playback followed by incoming speech.
7. Enable continuous listening, speak without a pause past the configured maximum, then pause, resume, change VAD settings and turn listening off. The microphone indicator should stop when no capture remains active.
8. Interrupt playback through another audio activity or an emergency. The interrupted message must not show completed playback. A new playback must keep listening paused until it finishes.
9. Select another sentence or language in TTS evaluation. Replay must never speak the previous sentence. Record a rating only after listening; a rating is a human judgment, not an automatic accuracy result.

The STT models still need measured WER on representative recordings. TTS voices still need native-speaker review, including names, numbers and mixed-script text. MT remains the existing engine: correcting language routing does not establish that its translations preserve meaning. The earlier Hindi–Gujarati reference review and engine comparison remain necessary.
