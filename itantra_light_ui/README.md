# iTantra — adaptive UI preview

Open `index.html` in a browser. No install, build step, internet, or external font is required.

This is an interactive design prototype for review, **not an updated Android APK**. It uses sample data and simulated actions. It does not access a microphone, Bluetooth, Wi-Fi Direct, GPS, real model downloads, the Android database, or emergency contacts. Reloading the page restores the sample data. Only appearance preferences are persisted; sample messages and hardware states remain in memory.

## Design

- Four coordinated palettes: Ocean (blue/seafoam), Forest (pine/gold), Iris (violet/rose), and Ember (clay/sage).
- Complete Light, Dark, and System modes, available from the sun/moon button, Settings, and More. Brightness and palette are saved in this browser and applied before the initial frame.
- Theme changes animate only colors, borders and shadows; reduced motion suppresses the transitions.
- A high-contrast push-to-talk control and a guarded one-tap language swap. The swap is available only for two installed languages with supported speech and translation directions.
- A labelled crimson SOS control opens the existing emergency-choice screen; selecting and sending an alert still require confirmation.
- Responsive phone navigation and a wider desktop layout; all views work from 320 px upward.
- Five direct phone tabs — Talk, Connect, Messages, Notes and Settings — replace the large More dialog. Language Packs and Diagnostics are one tap from Settings; SOS stays prominent in the header.
- At 390×844, two recent message previews and the circular talk button fit above the bottom navigation. The hub still scrolls to the full controls and shortcuts.
- Reduced-motion support, keyboard access, labelled controls, and native modal focus handling.
- A bounded microphone dock appears only when the microphone scrolls above the viewport. It stays compact on both phone and desktop, includes safe-area spacing, and exposes Finish/Cancel for a locked recording.

## Views and sample interactions

1. **Talk**: choose microphone/target language; PTT hold/release; slide up to lock; finish/cancel; hands-free start/pause; history filters; retry failed messages.
2. **Connect**: Bluetooth/Wi-Fi Direct discovery with several distinguishable sample peers and masked IDs; explicit disconnect before switching peers; peer-specific six-digit code match/reject; verified session state; connection details. Wi-Fi Direct discovers nearby devices directly, not every device on the same Wi-Fi router. Bluetooth RSSI is a sample last-discovery reading; Wi-Fi Direct per-peer signal is unavailable in the current Android implementation.
3. **Messages**: previous-device conversation list, each device's separate sample chat, typed sample messages, voice interaction, sample location, delivery state/retry. Switching peers retains earlier sample chats. A previous device's composer stays disabled until it is reconnected. No audio playback control has been added.
4. **Voice notes**: local transcript rows, native-script text, date groups computed on opening, delete confirmation. Deleting a note preserves the separate message.
5. **Language packs**: ten-language catalog; search; installed/available filters; simulated download/cancel/retry; independent Speak/Receive selection; active mic/target/auto controls; support limitations. Actual byte counts must be supplied by the Android manifests at implementation time.
6. **SOS**: four presets plus critical priority broadcast, explicit send confirmation, disconnected state, and separate simulated device delivery and human acknowledgement. The preview waits for its simulated device acknowledgement before showing Delivered.
7. **Settings**: local display name, labelled sample device ID, direct Language Packs and Diagnostics links, connection details, fixed/unavailable controls clearly described, default reset, two-second hold to wipe sample private data while retaining installed sample packs.
8. **Diagnostics**: connection details and actual design for unmeasured values; no invented accuracy, latency, or resource results. Browser battery and radio fields say that live readings belong in the Android app.

The preview toolbar switches between Connected, Disconnected, Models missing, Download failed, and Empty history states. These scenario controls belong to the review environment, not the proposed Android UI.

## Relationship to the app

Reviewed against the existing screen code and curated ZIP notes. Bluetooth RFCOMM / Wi-Fi Direct peer verification, note deletion, separate microphone/receiving selections, emergency semantics, and model-preserving wipe are represented. The Android Connect screen was also corrected to avoid marking every discovered Wi-Fi Direct peer as verified when only one session is connected. Android Diagnostics now reads Bluetooth and Wi-Fi Direct state, Connect warns when a radio is off, and an unavailable battery reading shows as unknown rather than a fabricated percentage. Android Messages is now a native Compose conversation list backed by the encrypted Room database; it opens the existing Compose chat, whose voice and send actions use the app's existing Sherpa-ONNX and secure transport coordinator. Earlier design folders are untouched.

`UI_OPTION_AUDIT.md` compares every major option in the supplied legacy design breakdown with this preview and the Android source. It lists what is simulated, what routes to an existing screen, and which proposed controls are not supported by the current Android behavior. This is a review draft; the UI is not finalized.

The prototype keeps hardware actions in memory and uses fixed translated sample sentences. It makes no statement about real STT, TTS, translation quality, transport success, or Android performance. There is no account or cloud backend. The Messages inbox behavior has a native Compose counterpart; porting the remaining preview styling remains separate work.

## Verification

`verify.cjs` runs the browser checks with the workstation's bundled Playwright and installed Microsoft Edge. Run `node itantra_light_ui/verify.cjs` from the workspace root. The runtime path is workstation-specific.

`review/verification.json` records the original feature and responsive checks. `verify-themes.cjs` checks the four palettes, light/dark modes, contrast tokens, preference persistence, OS mode updates, reduced motion, and dock geometry. Its report is `review/themes/verification.json`. `review/` also contains rendered screen captures. Full-page phone captures include the fixed navigation at the original viewport boundary; use the interactive preview to scroll naturally.

`verify-connection.cjs` checks multi-peer discovery, peer-specific verification and rejection, switch confirmation, transport-specific signal availability, connection details in Settings and Diagnostics, and narrow-screen layout.

`verify-navigation.cjs` checks all five phone tabs, secondary routes, the bottom-sheet appearance dialog, rendered hash links and action handlers, and phone-width overflow.

`verify-hub.cjs` checks 390×844 first-viewport placement, SOS size and route, and both enabled and disabled swap behavior. Its screenshot is `review/hub-phone-viewport.png`.

Semantic colors are in `themes.css`; base layout is in `styles.css`; appearance controls and visual refinements are in `refinements.css`. `theme.js` applies saved preferences before rendering. View rendering and in-memory interactions are in `app.js`. The preview makes no network requests.
