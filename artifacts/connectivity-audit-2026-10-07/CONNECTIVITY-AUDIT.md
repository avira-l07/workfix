# iTantra Bluetooth and Wi-Fi Direct audit — 7 October 2026

This audit traces discovery, first-time pairing, incoming and outgoing connections, server and client roles, security verification, chat entry, application packets, retries, cancellation, radio loss, activity recreation, background operation, transport changes, and shutdown. It fixes confirmed code defects and adds executable fault tests. It is not a claim that all hardware or vendor-specific failures have been eliminated.

## Delivered behavior

The update is **1.11-connectivity-audit**, version code **12**. It includes the earlier input and mutual security-confirmation fixes. Both users still have to compare and accept the same six digits. Plain traffic, an old verification token, or an old connection cannot establish trust in a new session.

Install the new APK on **both phones**, as an update to the existing app. Do not uninstall or clear storage just to install it. Model packs and saved messages are not replaced by this audit.

## Confirmed problems fixed

| Route | Defect and resulting fix | Verification |
|---|---|---|
| Bluetooth outgoing connection | Automatic listener maintenance could cancel an outgoing connection or bonding attempt. A pending or connected link now prevents listener restart; repeated Connect taps cannot replace it. | Code reviewed; native pairing must be tested on phones |
| Bluetooth listener cancellation | A listener coroutine could start after Stop/leave-screen. State is published before launching; socket registration is fenced by connection identity. Cleanup uses the transport's own scope, so disposing the screen cannot cancel cleanup. | State lifecycle tests; native accept/stop still requires phones |
| Bluetooth first-time pairing | Late bond completion could revive a cancelled request. Bond callbacks retain their request generation; a 60-second pairing timeout prevents indefinite CONNECTING. Receiver reattachment checks an already-completed bond. | Code reviewed; first-time pairing/cancellation/recreation are phone tests |
| Bluetooth RFCOMM connect | Coroutine timeout around blocking `connect()` did not actually unblock Android IO. The pending socket is registered before connect; an independent deadline closes it. | Blocking-write test demonstrates socket-close cancellation; native connect timeout is a phone test |
| Bluetooth reader | An obsolete reader's EOF/cancellation could close a replacement socket. Each reader owns its stream and generation; obsolete completions are ignored. | `BluetoothSocketLifecycleTest` |
| Bluetooth writer | A queued send could use a replacement peer's output stream, or an old write error could close that peer. Writes are tied to the original socket generation. | Bluetooth and TCP stale-writer tests |
| Bluetooth radio/bond loss | Bluetooth OFF, bond loss and key-loss events now invalidate the matching link; delayed bond errors cannot close a newer link. | Code reviewed; actual broadcasts are phone tests |
| Initial frames | Early HELLO could disappear before upper collectors attached. Bounded replay exists at both byte and decoded-packet layers, and terminal disconnect clears it. | Late-subscriber tests at both layers |
| Fast reconnect | A bare state flow could conflate CONNECTED → DISCONNECTED → CONNECTED and preserve old trust. State snapshots include socket generation and explicitly expose a reset boundary. | Slow-observer regression and real TCP reconnect |
| Wi-Fi TCP setup | An initial/internal DISCONNECTED event could erase a pending P2P group and selected peer. Only a previously connected TCP link is treated as a lost link. | `WifiManagerLifecycleTest` |
| Wi-Fi cancellation and callbacks | Delayed group/connect/discovery callbacks could revive or overwrite a cancelled/newer operation. Generation/request checks discard obsolete callbacks; socket setup and teardown are serialized. | Cancelled-group and repeated server-cancellation regressions; actual Android callbacks are phone tests |
| Wi-Fi duplicate group events | Repeated connection info could tear down an already connected socket. Matching group/role callbacks remain idempotent. Missing client owner addresses fail before TCP setup. | Manager lifecycle tests with real localhost TCP |
| Wi-Fi radio/channel loss | UI could say OFF/ERROR while the old socket/session remained alive. Terminal cleanup now cancels timers/setup, closes the socket, removes the group and restores the default transport. | Manager radio-off cleanup test; actual radio/channel behavior is a phone test |
| Wi-Fi discovery while connected | Location/discovery errors could overwrite an active connection's UI state. Discovery is blocked during an active attempt; peer-list location errors do not demote an active link. | Code reviewed; phone UI test |
| Android 17 local access | The app targets SDK 37 but omitted the local-network runtime permission. Manifest, request flow and Wi-Fi permission checks now include it. Empty cancelled permission results are not treated as approval. | Build/manifest verified; runtime grant/deny/revoke is a phone test |
| Incoming Wi-Fi chat entry | A receiver with no selected/discovered sender could have no Open chat route. A verified incoming link now has its own chat entry. | Code reviewed/build verified; phone UI test |
| Transport handoff | Old asynchronous handoff/restore callbacks could race. Handoffs are serialized and check current link identity; an existing Bluetooth connection is not silently replaced by an incoming Wi-Fi link. | Transport-switch regressions; two-radio phone test |
| Confirmation delivery | Local verification must not cancel its final outgoing confirmation. Retries continue until authenticated peer traffic proves delivery. | Both acceptance orders over actual localhost sockets; suspended/failed final-write tests |
| Initial security exchange | A server with no initial HELLO had no deadline; the initial NO_SESSION emission could also cancel its timeout. Both roles now retain the initial exchange deadline and malformed key exchange closes the link. | `InitialHandshakeTimeoutTest`, existing handshake/security suites |
| Session encryption | Session reset could race encryption/decryption and counter/replay state. Key/counter/replay operations and reset now share synchronization. | Existing crypto concurrency/replay tests |
| Delayed translation/GPS | A result finishing after reconnect could be sent to a different connection or old chat's recipient. Operations retain their original session generation and socket token, and check the selected peer. | `MessageRoutingAuditTest` |
| Incoming message attribution | A newly received message could be filed under whichever old conversation happened to be open; queued translation could read a later peer profile. Incoming identity is captured when queued and retained through translation/playback. | Verified-sender/old-chat regression |
| Playback and receipt controls | Delayed ACK/TTS status packets could be sent through a newer session. Controls are bound to their reception generation; old-chat human acknowledgement is not transmitted to another peer. | Routing generation guards, existing receipt/TTS suites |
| Pending acknowledgements | Remote disconnect left sends waiting for timeout. Link loss now cancels pending ACK waits immediately and clears stale packet replay. | `ConnectionReceiptAuditTest` |
| Duplicate sends | Overlapping retries of one message ID could replace/remove each other's ACK waiter. A second concurrent send for the same ID is rejected without overwriting the first waiter. | Overlapping-send regression |
| Very fast acknowledgements | A measured RTT rounded to 0 ms was treated as no ACK, causing unnecessary SOS retries and false failure text. Any measured acknowledgement counts as delivery. | Zero-millisecond SOS acknowledgement regression |
| Manual retry | A SENT message with no delivery confirmation could not be retried; GPS retry became ordinary TEXT. Eligible SENT/ERROR messages can be retried to the original verified peer; GPS keeps its LOCATION packet, coordinates and timestamp. | SENT retry, wrong-peer rejection, GPS payload regression |
| Stalled writes | Blocking socket writes had no deadline. A 12-second write deadline closes the matching socket, allowing the writer to exit; obsolete timeout/errors cannot close replacements. | Blocking-stream deadline regression |
| Idle watchdog | Wall-clock adjustment could distort authenticated inactivity timing. The watchdog uses a monotonic clock and captures its observed transport. Unauthenticated traffic cannot refresh it. | Liveness/security tests |
| Background connection | A verified connection had no connected-device foreground service. The existing operational service now maintains an active peer notification and bounded renewable wake lock, without requesting microphone type unless continuous voice is active. | Service-type tests/build; real screen-off/Doze behavior is a phone test |
| Shutdown | Cleanup launched in a scope that was immediately cancelled could leave sockets open. Cleanup now finishes before cancelling that scope. | Socket shutdown regression |

## Automated coverage and its limits

The signed APK's `verification.json` records the final full-suite counts, package/version, file hash, signature verification, and certificate continuity. `build-verification.log` contains the complete build/test result.

Final result: **505 host tests passed; 0 failures, 0 errors, 0 skipped tests**, including **28 new audit regressions**. APK assembly, ZIP integrity and v2 signature verification passed. The signing certificate matches the previous APK. `test-results/` preserves the JUnit evidence independently of temporary build outputs. The phone inventory is saved in `device-inventory.log`.

New audit suites: `BluetoothSocketLifecycleTest`, `WifiSocketAuditTest`, `WifiManagerLifecycleTest`, `ConnectionReceiptAuditTest`, `ConnectionServiceTypeTest`, `MessageRoutingAuditTest`, and `InitialHandshakeTimeoutTest`.

The TCP tests use actual localhost sockets, including byte fragmentation, multiple frames in one write, malformed/oversized lengths, truncated bodies, cancellation, delayed writers, mutual verification, encrypted messages in both directions and reconnects. Bluetooth tests execute the production stream/reader/writer code with blocking streams. Manager tests drive the production manager and TCP lifecycle with controlled connection-info callbacks. **These tests do not run Android Bluetooth pairing, Wi-Fi P2P discovery/role negotiation, permission dialogs, Compose interactions, or OEM power management.**

No Android devices were attached during final verification. No two-phone results or measured connection-success percentage are claimed.

## Two-phone acceptance checklist

Use the new APK on both phones. Start with Wi-Fi/Bluetooth enabled, Location enabled for Wi-Fi Direct discovery, required permissions granted, and no VPN. Keep Connect open on both phones. For Bluetooth, one phone taps Connect while the other waits. Compare the six digits in person.

For **each transport**, run these with phone A initiating, then with phone B initiating:

1. Fresh discovery and connection. Bluetooth: test both an already-bonded pair and a first-time pair.
2. A confirms first, B confirms second; reconnect and reverse confirmation order. Both must leave the verification dialog and show the same verified peer.
3. Reject the code on either phone. Neither phone may remain verified or send application messages.
4. Cancel while connecting/pairing. Wait a minute; the cancelled outgoing attempt must not revive itself. Explicitly reconnect afterward.
5. Send typed text, spoken text, SOS and a GPS location in both directions. Check the receiving phone, original peer identity, delivery state and GPS map/coordinates. Test replay separately from transport delivery.
6. Retry an unacknowledged message; the receiver must avoid duplicate history/playback. Retry GPS and check that it remains a location message. Open an old disconnected conversation and confirm that sending cannot reach the current peer.
7. Disconnect from A, then B, and reconnect at least five times. Every new link must require fresh code acceptance; old confirmation state must not carry forward.
8. Turn the active radio OFF on each phone. Both must lose verified state, stop sending, and permit a fresh connection after restoring the radio.
9. Walk out of range and return. No false Delivered status is allowed; the authenticated idle deadline is 180 seconds when the OS does not report loss sooner.
10. Rotate/recreate the activity during pairing, during code verification and while connected. Operator acceptance must remain backend state; returning to Connect must show the real connection and peer.
11. Leave the app for another app, then lock each screen for at least five minutes. Check peer notification and bidirectional receive/SOS; repeat under battery saver/Doze. Vendor restrictions still require device testing.
12. Change Bluetooth → Wi-Fi and Wi-Fi → Bluetooth explicitly. A second active peer must not silently replace an existing verified connection. Chat entry must work for the Wi-Fi receiver even if its discovery list is empty.

Also test denied/revoked Nearby permissions, disabled Location for discovery, Android pairing rejection, a peer that has no iTantra listener, and both phones tapping Bluetooth Connect at once. Simultaneous Bluetooth initiation is not an automatic conflict-resolution protocol: use one initiator, allow the bounded failure/cancel path to finish, then retry from one phone. Test Android 17 local-network grant/deny if such a device is available.

Do not uninstall, wipe history, or delete installed model packs to troubleshoot these tests. Force-stopping/killing the app intentionally ends its connection; automatic restoration without fresh verification is not promised.

## Platform references

Socket cancellation follows Android's documented requirement to close a blocking Bluetooth socket from another thread: [Bluetooth connections](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices) and [BluetoothSocket](https://developer.android.com/reference/android/bluetooth/BluetoothSocket).

The Wi-Fi permission/location checks and connection-info reconciliation follow [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifi-direct). SDK 37 local access follows [local-network permission](https://developer.android.com/privacy-and-security/local-network-permission).

System Bluetooth receivers follow [Android broadcast receiver guidance](https://developer.android.com/develop/background-work/background-tasks/broadcasts). Background peer work uses the [connected-device foreground service type](https://developer.android.com/develop/background-work/services/fgs/service-types).
