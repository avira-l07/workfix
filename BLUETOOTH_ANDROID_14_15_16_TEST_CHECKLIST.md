# Bluetooth Classic RFCOMM Compatibility Test Checklist: Android 14, 15, and 16

## 1. Overview & Objective
This document defines the physical hardware test protocol and record matrix for validating offline, peer-to-peer Bluetooth Classic (RFCOMM) communication in iTantra across Android 14 (API 34), Android 15 (API 35), and Android 16 (API 36).

### Required Physical Flow
1. **Phone A**: Discoverable / Listening (`startServer()`) -> RFCOMM server (`isServer = true`).
2. **Phone B**: Discovers Phone A (`startDiscovery()`) -> Pairs if required (`ACTION_BOND_STATE_CHANGED`) -> Connects through secure RFCOMM (`connectToDevice()`).
3. **Link Established**: `ConnectionState.CONNECTED` -> Secure Handshake (`X25519` + `AES-GCM-256`) -> Same 4-character SAS code displayed on both phones -> Both operators confirm SAS (`onSasConfirmed`) -> `SECURE_VERIFIED`.
4. **Data Transfer**: Bidirectional offline message exchange with ACK confirmation.

---

## 2. Test Protocol per Device Pair

For every pair in the matrix below, execute the following 20-step procedure (plus 21–23 when Android 16 is involved):

1. **Install APK**: Install the identical debug build APK (`:app:assembleDebug`) on both Phone A and Phone B.
2. **Grant Nearby Devices**: Launch app; grant `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` via system runtime dialog. Verify location permission is NOT requested on Android 12+ (API 31+).
3. **Phone A Listens**: Navigate to Connect screen; tap "Broadcast Discovery Ping" to enter server/listening mode (`startServer()`).
4. **Phone B Scans**: Navigate to Connect screen; tap "Broadcast Discovery Ping" to initiate scan (`startDiscovery()`).
5. **Phone B Discovers Phone A**: Verify Phone A appears under "Discovered Devices" with device name and MAC identifier.
6. **Pair if Required**: If not already bonded, trigger pairing via OS prompt and verify bond transition (`BOND_BONDING` -> `BOND_BONDED`).
7. **Initiate Connect**: Phone B taps "CONNECT" on Phone A's card. Verify active discovery cancels before RFCOMM socket connects.
8. **Verify RFCOMM Connected**: Both phones transition to `ConnectionState.CONNECTED`.
9. **Verify SAS Code**: Both phones display the SAS verification dialog. Confirm the 4-character alphanumeric SAS code is **identical** on both screens.
10. **Confirm SAS**: Both operators tap "CODES MATCH · TRUST".
11. **Verify SECURE_VERIFIED**: Active session transitions to encrypted secure state (`isSecure = true`).
12. **Transmit 10 Messages (A -> B)**: Send 10 consecutive text messages from Phone A to Phone B.
13. **Transmit 10 Messages (B -> A)**: Send 10 consecutive text messages from Phone B to Phone A.
14. **Verify ACKs**: Verify that all 20 sent messages receive delivery acknowledgments (double-check marks / DELIVERED state).
15. **Disconnect**: Tap disconnect or navigate away from active chat to terminate transport.
16. **Reconnect**: Phone B re-initiates connection to Phone A.
17. **Repeat Message Test**: Transmit 1 test message after reconnect.
18. **Verify Fresh Session**: Confirm a brand-new ephemeral key exchange occurred; old session key was not reused.
19. **Bluetooth Toggle**: Toggle system Bluetooth OFF, then back ON on Phone B.
20. **Reconnect After Toggle**: Reconnect and verify clean recovery to `SECURE_VERIFIED`.

### Additional Steps for Android 16 (API 36)
21. **Bond-Loss / Key Missing Scenario**: On Phone A (or Phone B), remove/unpair the Bluetooth bond from Android system settings while the session is running or attempt reconnect with dropped keys.
22. **Verify App Stability**: Verify `ACTION_KEY_MISSING` / `ACTION_BOND_STATE_CHANGED` receiver fires; app must **not** crash.
23. **Verify Security Invalidation**: Verify session transitions to error state showing `"Bluetooth bond lost — re-pair device"`. Verify old secure session is immediately discarded and pending outgoing messages fail rather than reporting false success.

---

## 3. Test Pair Matrix & Results Log

| OS Pair | Phone A Model (OS) | Phone B Model (OS) | Discovery | Pairing | Connect | SAS Match | Msgs Sent / Acked | Reconnect | Failure Reason / Notes | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Android 14 ↔ Android 14** | TBD | TBD | Pending | Pending | Pending | Pending | 0 / 0 | Pending | None | UNTESTED |
| **Android 14 ↔ Android 15** | TBD | TBD | Pending | Pending | Pending | Pending | 0 / 0 | Pending | None | UNTESTED |
| **Android 14 ↔ Android 16** | TBD | TBD | Pending | Pending | Pending | Pending | 0 / 0 | Pending | None | UNTESTED |
| **Android 15 ↔ Android 15** | TBD | TBD | Pending | Pending | Pending | Pending | 0 / 0 | Pending | None | UNTESTED |
| **Android 15 ↔ Android 16** | TBD | TBD | Pending | Pending | Pending | Pending | 0 / 0 | Pending | None | UNTESTED |
| **Android 16 ↔ Android 16** | TBD | TBD | Pending | Pending | Pending | Pending | 0 / 0 | Pending | None | UNTESTED |

> **IMPORTANT**: In accordance with project instructions, percentages and test outcomes must NEVER be fabricated. All rows above remain explicitly marked as **UNTESTED** until executed on physical Android hardware.

---

## 4. Measurable Bluetooth Metric Calculations

Once physical tests are conducted across devices, calculate the key performance indicators using the formulas below:

### 1. Connection Success Rate
$$\text{Connection Success Rate} = \left( \frac{\text{Successful Connection Attempts}}{\text{Total Connection Attempts}} \right) \times 100$$

### 2. Message Delivery Rate
$$\text{Message Delivery Rate} = \left( \frac{\text{ACK-Confirmed Messages}}{\text{Total Messages Sent}} \right) \times 100$$

### 3. Reconnect Success Rate
$$\text{Reconnect Success Rate} = \left( \frac{\text{Successful Reconnects}}{\text{Total Reconnect Attempts}} \right) \times 100$$

---

## 5. Physical Testing Status Declaration

- **Code Compatibility**: Verified via clean unit test suite and compilation under `:app:assembleDebug` with target SDK configuration.
- **Physical Device Compatibility**: **UNTESTED** on physical Android 14, 15, and 16 hardware devices in this development environment. Physical multi-device RFCOMM validation remains to be run on actual test devices.
