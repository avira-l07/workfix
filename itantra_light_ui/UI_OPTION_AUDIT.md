# iTantra UI options and route audit — review draft

This checks the supplied screen inventory against the current HTML design preview and the Android source. The pasted inventory is a description of an earlier design; it is not proof that every action is implemented. This preview remains a simulation and is **not** the Android app or a finished APK.

| Area | Preview route and interaction | Android source / actual capability | Review finding |
|---|---|---|---|
| Talk hub | `#hub`: mic/target language pickers, guarded one-tap swap, PTT, hands-free mode, message filters, retry, notes/packs shortcuts, connection and prominent SOS links | `TransceiverHubScreen.kt`, `MainActivity.kt` | At 390×844 two message previews and the PTT fit before bottom navigation. Preview microphone and delivery are simulated. Its sample feed does not yet show every Android message state or every transcript/translation label. |
| Connect | `#connect`: Bluetooth or Wi-Fi Direct discovery, separate peer rows, masked IDs, switch confirmation, peer-specific sample code, connection details | `ConnectScreenContent.kt`, `WifiDirectConnectionManager.kt`, secure session classes | Route works. Preview scan/code values are samples. Android provides live discovery and a real six-digit verification step. The Android peer-list bug that marked every Wi-Fi Direct row secure was corrected. |
| Messages | `#chat`: previous-device list then peer-specific chat with typed sample text, simulated voice note, sample GPS card, copy and map explanation, retry | `MessagesScreen.kt`, `ConversationList.kt`, `DedicatedChatScreen.kt`, `PeerDao.kt` | Native Compose inbox observes encrypted Room messages and verified peers; the existing chat uses the coordinator's Sherpa-ONNX and secure Bluetooth/Wi-Fi Direct transport path. Saved offline chats are readable but cannot send until that peer reconnects. The preview remains simulated. |
| Voice notes | `#notes`: local transcript samples, date groups, delete confirmation | `VoiceNotesScreen.kt`, `VoiceNoteGrouping.kt` | Route works. Preview notes live in memory; Android uses local database rows. |
| Language packs | `#packs`: search/filter, download/cancel/retry simulation, independent Speak/Receive selections, active mic, target and Auto | `LanguagePacksScreen.kt` | Route works from Talk and Settings. Preview does not download models or report real bytes; the Android repository/storage determine readiness. |
| SOS | `#sos`: four emergency presets plus critical priority broadcast, confirmation, simulated device-delivery stage | `TransceiverHubScreen.kt` emergency bottom sheet | The Android app exposes **four** preset codes plus a critical broadcast entry. The inventory's eight codes and two-second critical hold are not present. The preview now follows the existing four-preset set and does not claim that a person has responded when the device acknowledges. |
| Settings | `#settings`: local operator name, sample device ID, connection details, packs and diagnostics links, fixed VAD note, reset and hold-to-wipe | `SettingsScreen.kt`, `WipeDataAction.kt` | Operational destinations are direct. The real device ID comes from Android; the preview labels its ID as a sample. Android VAD sensitivity is fixed in this build. Wipe **retains** downloaded language packs/models, contrary to the supplied inventory. |
| Diagnostics | `#diagnostics`: sample connection facts, honest unavailable metrics, route to packs | `DiagnosticsScreen.kt`, `MainActivity.kt`, `BatteryIndicator.kt`, `RadioHardwareStatus.kt` | Preview does not measure hardware. Android shows actual battery when available and live Bluetooth/Wi-Fi Direct hardware status, with unavailable values labelled. Transport loss/retry values remain unavailable until instrumented. |

## Navigation roots

- On phones the five visible destinations are Talk `#hub`, Connect `#connect`, Messages `#chat`, Notes `#notes`, and Settings `#settings`. This replaces the large “Your space” More dialog. SOS and appearance remain in the header. Language Packs and Diagnostics are direct links in Settings and elsewhere where relevant.
- The preview's `href="#..."` links route through the matching `views` entry in `app.js`. Its `data-action` controls route through named click handlers. `verify-navigation.cjs` checks all rendered routes/actions on every screen at 320 and 390 px, plus the five tabs and Settings/SOS shortcuts. `verify-hub.cjs` checks SOS, swap and the first viewport at 390×844.
- Android navigation is in `MainActivity.kt` using `AppDestination` and each Compose screen. The HTML links do **not** call Android `AppDestination` or connect to Bluetooth, Wi-Fi Direct, models, Room, or GPS. Integrating the approved design with Compose remains a separate implementation step.

## Inventory claims intentionally not copied

- Bluetooth RFCOMM is Bluetooth Classic transport, not Bluetooth Low Energy. The app is a direct peer connection, not a general mesh network.
- Phones sharing a Wi-Fi router do not automatically appear as Wi-Fi Direct peers. Wi-Fi Direct discovery finds nearby devices with that mode available.
- A moving radar sweep, distance rings, responder-role badges, and 1–4 Wi-Fi Direct signal bars would imply measurements the current app does not provide. Bluetooth RSSI is only a last-discovery snapshot; per-peer Wi-Fi Direct RSSI is unavailable.
- Eight emergency codes, battalion field, editable VAD slider, chat clear-history menu, and hold-to-send critical alert are not supported by the inspected Android screens. These need separate behavior decisions before they can be truthful controls.
- The Android Settings text identifies its callsign as local and not transmitted over Bluetooth RFCOMM. The preview no longer says editing that name changes what the peer sees.

## Still to decide before final UI approval

- Whether to add a real Wi-Fi Direct peer signal measurement at the transport layer. Until then the UI should show “unavailable.”
- Whether the Android app should add the inventory's extra emergency codes, radar, organization field, or chat overflow actions. The current preview deliberately does not invent them.
- Whether to port this design into Compose after the preview is approved. No final UI sign-off or APK integration is implied by this audit.
