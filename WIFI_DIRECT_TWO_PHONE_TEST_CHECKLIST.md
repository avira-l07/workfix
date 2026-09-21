# Wi-Fi Direct Two-Phone Physical Test Checklist

**Device Under Test Setup:**
* **Phone A**: Android 14 / 15 / 16 test device (Wi-Fi ON, Mobile Data OFF, Wi-Fi Router Disconnected)
* **Phone B**: Android 14 / 15 / 16 test device (Wi-Fi ON, Mobile Data OFF, Wi-Fi Router Disconnected)
* **Transport**: Android Wi-Fi Direct (`android.net.wifi.p2p.WifiP2pManager`) + TCP Sockets (Port 8988)
* **Security Protocol**: ECDH Ephemeral Key Exchange + 6-digit SAS Verification + AES-256-GCM Framing

---

## Pre-Test Device Verification
- [ ] Both devices have Wi-Fi radio turned **ON**.
- [ ] Both devices have Mobile Data turned **OFF**.
- [ ] Both devices are disconnected from any local Wi-Fi router / access point (NO INTERNET).
- [ ] Nearby Wi-Fi Devices permission (`android.permission.NEARBY_WIFI_DEVICES`) granted on API 33+.
- [ ] Location permission (`android.permission.ACCESS_FINE_LOCATION`) granted if testing on API <= 32.

---

## TEST A — Basic Connection (P2P Group Formation & TCP Handshake)

| Step | Action | Expected Behavior | Observed Result | Status |
|:----:|:-------|:------------------|:----------------|:------:|
| 1 | Open iTantra on Phone A and Phone B | Both apps reach Transceiver Hub | | [ ] |
| 2 | Open Connect screen on both phones | Connect screen displays top-level tabs: `BLUETOOTH` and `WI-FI DIRECT` | | [ ] |
| 3 | Select `WI-FI DIRECT` tab on both phones | Status shows `WI-FI DIRECT READY` | | [ ] |
| 4 | Phone A taps **DISCOVER WI-FI DIRECT PEERS** | Status updates to `SEARCHING FOR WI-FI DIRECT PEERS…` | | [ ] |
| 5 | Observe peer discovery on Phone A | Phone B device name and masked address appear in peer list | | [ ] |
| 6 | Phone A taps **CONNECT** on Phone B's card | Status updates to `NEGOTIATING P2P LINK…` on Phone A; Phone B accepts connection invitation | | [ ] |
| 7 | Observe P2P group formation | Status transitions to `P2P GROUP FORMED` on both devices | | [ ] |
| 8 | Record Group Owner role | Group Owner is: `[ ] Phone A` / `[ ] Phone B` (TCP Server on Port 8988) | | [ ] |
| 9 | Observe TCP socket connection | Status transitions to `CONNECTED · TCP 8988` on both phones | | [ ] |
| 10 | Ephemeral ECDH handshake | Initiator (Client) sends `SECURE_HELLO`; Responder (Server) replies; SAS code computed | | [ ] |
| 11 | SAS Verification Dialog appears on both phones | Both phones render the exact same 6-digit numeric SAS code | Phone A: ______<br>Phone B: ______ | [ ] |
| 12 | Both users tap **CODES MATCH · TRUST** | Local and remote SAS confirmations exchange via `SECURE_VERIFY` | | [ ] |
| 13 | Secure verification confirmation | Both phones enter `SECURE_VERIFIED`; **OPEN CHAT** button becomes active | | [ ] |

---

## TEST B — Message Delivery: Phone A -> Phone B (10 Messages)

Send 10 text messages sequentially from Phone A to Phone B over Wi-Fi Direct TCP:

| Msg # | Sent Text (Phone A) | Received Text (Phone B) | ACK Received (Phone A) | RTT Latency (ms) | Duplicate? |
|:-----:|:-------------------|:------------------------|:----------------------:|:----------------:|:----------:|
| 1 | "Tactical test alpha 1" | | [ ] | | [ ] No / [ ] Yes |
| 2 | "Tactical test alpha 2" | | [ ] | | [ ] No / [ ] Yes |
| 3 | "Tactical test alpha 3" | | [ ] | | [ ] No / [ ] Yes |
| 4 | "Tactical test alpha 4" | | [ ] | | [ ] No / [ ] Yes |
| 5 | "Tactical test alpha 5" | | [ ] | | [ ] No / [ ] Yes |
| 6 | "Tactical test alpha 6" | | [ ] | | [ ] No / [ ] Yes |
| 7 | "Tactical test alpha 7" | | [ ] | | [ ] No / [ ] Yes |
| 8 | "Tactical test alpha 8" | | [ ] | | [ ] No / [ ] Yes |
| 9 | "Tactical test alpha 9" | | [ ] | | [ ] No / [ ] Yes |
| 10 | "Tactical test alpha 10" | | [ ] | | [ ] No / [ ] Yes |

**Summary Test B:**
* Messages Sent: `___ / 10`
* Messages Received: `___ / 10`
* ACKs Received: `___ / 10`
* Duplicates: `___`
* Failures: `___`
* Mean Latency: `___ ms`

---

## TEST C — Message Delivery: Phone B -> Phone A (10 Messages)

Send 10 text messages sequentially from Phone B to Phone A over Wi-Fi Direct TCP:

| Msg # | Sent Text (Phone B) | Received Text (Phone A) | ACK Received (Phone B) | RTT Latency (ms) | Duplicate? |
|:-----:|:-------------------|:------------------------|:----------------------:|:----------------:|:----------:|
| 1 | "Bravo response 1" | | [ ] | | [ ] No / [ ] Yes |
| 2 | "Bravo response 2" | | [ ] | | [ ] No / [ ] Yes |
| 3 | "Bravo response 3" | | [ ] | | [ ] No / [ ] Yes |
| 4 | "Bravo response 4" | | [ ] | | [ ] No / [ ] Yes |
| 5 | "Bravo response 5" | | [ ] | | [ ] No / [ ] Yes |
| 6 | "Bravo response 6" | | [ ] | | [ ] No / [ ] Yes |
| 7 | "Bravo response 7" | | [ ] | | [ ] No / [ ] Yes |
| 8 | "Bravo response 8" | | [ ] | | [ ] No / [ ] Yes |
| 9 | "Bravo response 9" | | [ ] | | [ ] No / [ ] Yes |
| 10 | "Bravo response 10" | | [ ] | | [ ] No / [ ] Yes |

**Summary Test C:**
* Messages Sent: `___ / 10`
* Messages Received: `___ / 10`
* ACKs Received: `___ / 10`
* Duplicates: `___`
* Failures: `___`
* Mean Latency: `___ ms`

---

## TEST D — True Offline / Zero Internet Verification

| Step | Action | Expected Behavior | Observed Result | Status |
|:----:|:-------|:------------------|:----------------|:------:|
| 1 | Verify Airplane Mode is ON with Wi-Fi manually toggled ON (or Mobile Data + Router OFF) | Zero WAN / cellular / router connectivity on both phones | | [ ] |
| 2 | Verify neither phone can access public internet | Ping 8.8.8.8 fails; browsing fails | | [ ] |
| 3 | Connect Wi-Fi Direct between Phone A and Phone B | Direct P2P link forms without default gateway or internet DNS | | [ ] |
| 4 | Send text message "Zero WAN operational test" | Message delivers with AES-256-GCM verification and ACK confirmation | | [ ] |

---

## TEST E — Disconnect & Reconnect (Session Invalidation & Re-Keying)

| Step | Action | Expected Behavior | Observed Result | Status |
|:----:|:-------|:------------------|:----------------|:------:|
| 1 | Tap **DISCONNECT WI-FI DIRECT** on Phone A | P2P group removed; TCP socket closed; previous session keys invalidated | | [ ] |
| 2 | Verify UI reflects disconnect | Status returns to `WI-FI DIRECT READY` / `AVAILABLE` | | [ ] |
| 3 | Initiate Wi-Fi Direct reconnection from Phone A to Phone B | New P2P group negotiated; new TCP connection established on Port 8988 | | [ ] |
| 4 | Verify Ephemeral Key Exchange | A fresh ECDH keypair is generated; old Bluetooth or previous Wi-Fi keys are NOT reused | | [ ] |
| 5 | Verify new SAS Code | A **NEW** 6-digit SAS code is generated (distinct from previous session) | Old SAS: ______<br>New SAS: ______ | [ ] |
| 6 | Verify SAS confirmation on both phones | Tapping Confirm transitions session to `SECURE_VERIFIED` | | [ ] |
| 7 | Send message "Post-reconnect verification" | Message delivers successfully with fresh session keys | | [ ] |

---

## TEST F — Screen Off / Background Durability

| Step | Action | Expected Behavior | Observed Result | Status |
|:----:|:-------|:------------------|:----------------|:------:|
| 1 | Phone A and Phone B connected via Wi-Fi Direct | Status is `CONNECTED · TCP 8988` | | [ ] |
| 2 | Turn receiver phone (Phone B) screen OFF | Foreground Service with WakeLock maintains network interface | | [ ] |
| 3 | Phone A sends message "Background survivability test" | Phone A sends frame and waits for ACK | | [ ] |
| 4 | Check Phone B status | Phone B receives packet; ACK is returned to Phone A | [ ] Delivered / [ ] Dropped | [ ] |
| 5 | Turn Phone B screen back ON | Message appears in chat history; state is `DELIVERED` | | [ ] |

*Note: If OEM battery optimization (e.g. Samsung sleeping apps, Xiaomi battery saver) tears down Wi-Fi P2P when screen is off, record the exact OEM behavior; do not falsely mark PASS.*

---

## MEASURE RESULTS

The following metrics are calculated exclusively from physical dual-phone test executions.

### 1. Wi-Fi Direct Connection Success Rate
$$\text{Success Rate} = \frac{\text{Successful Group + TCP Connections}}{\text{Total Connection Attempts}} \times 100$$
* Attempts: `___`
* Successes: `___`
* **Result: `___ %`**

### 2. Message Delivery Rate
$$\text{Delivery Rate} = \frac{\text{ACK-Confirmed Messages}}{\text{Total Messages Sent}} \times 100$$
* Sent: `___`
* Confirmed: `___`
* **Result: `___ %`**

### 3. Reconnect Success Rate
$$\text{Reconnect Rate} = \frac{\text{Successful Reconnects}}{\text{Total Reconnect Attempts}} \times 100$$
* Reconnect Attempts: `___`
* Successful Reconnects: `___`
* **Result: `___ %`**

### 4. Duplicate Rate
$$\text{Duplicate Rate} = \frac{\text{Duplicate Rendered Messages}}{\text{Total Received Messages}} \times 100$$
* Received: `___`
* Duplicates: `___`
* **Result: `___ %`**

---

### Verification Summary
* **Implementation Status**: Compiled and unit-tested in build environment.
* **Physical Two-Phone Test Execution**: Pending physical testing with two live Android devices per this checklist.
