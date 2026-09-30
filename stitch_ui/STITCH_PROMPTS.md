# 🎛️ Google Stitch UI Prompts & Design Specification: iTantra Tactical Transceiver

Use these structured prompts in the **Stitch web tool** to generate or iterate on each screen of the iTantra application.

---

## 🎨 Global Design System & Theme Directives for Stitch

Copy and paste this design system directive into the **Style / System Prompt** in Stitch:

```text
Design a military-grade, tactical offline disaster-relief mobile transceiver app called "iTantra".
Mobile screen dimensions: 390x844px (iPhone 14 / modern Android viewport).
Theme & Aesthetic:
- Dark Tactical HUD / Cyber-Military Disaster Operations.
- Primary Background: #080C14 (Deepest Tactical Obsidian / OLED Black).
- Card / Surface: #0F172A (Gunmetal Slate 900) with subtle high-tech borders (#1E293B).
- Primary Accent: #00F0FF / #06B6D4 (Tactical Cyan HUD Glow).
- Status Success / Secure: #10B981 (Tactical Emerald Green).
- Status Warning / Standby: #F59E0B (Amber Alert).
- Emergency / Distress: #EF4444 (Crimson Red Pulsing Strobe).
- Typography: "Inter" for clean readability and "JetBrains Mono" / tabular figures for military timestamps (e.g. 14:02:08Z), frequencies, RSSI dBm signal levels, and battery telemetry.
- Tactile Elements: Heavy push-to-talk button, glowing LED status pills, radar sweep circles, and cryptographic verification badges. No sterile corporate whites or soft pastels.
```

---

## 📱 Screen 1: Transceiver Hub & PTT Deck (`00_transceiver_hub`)

### Stitch Prompt:
```text
Screen: Tactical Transceiver Hub & Push-To-Talk Deck
Header:
- Top bar with Operator Callsign "ALPHA-01", Battery indicator pill (87% [4.1V] in green), and status indicator "SECURE MESH ACTIVE".
- Tactical emergency toggle at top: a guarded red button labeled "SOS BEACON" with hazard diagonal stripes.

Sub-header & Session Banner:
- Card showing active RFCOMM channel "TAC-RELIEF-04" and cryptographic session "ENCRYPTED (NO-MITM)".
- Active STT Speech Language selector chips: [AUTO] [HI - हिंदी] [EN - English] with "HI" active in glowing cyan.

Main Communications Feed:
- Vertical list of recent tactical transmissions:
  1. Incoming (RX) transmission from "MEDIC-02" (2 min ago): Hindi speech "सेक्टर 4 में पानी की जरूरत है" with English translation badge "Need water in Sector 4", audio waveform duration "0:04s", and a small speaker replay button.
  2. Outgoing (TX) transmission from "ALPHA-01" (Just now): "Relief convoy en route to grid 34-B", delivery status "DELIVERED ✓✓".
  3. Emergency Alert Card (Crimson outline): "DISTRESS SIGNAL DETECTED - NODE-7229: STRUCTURAL COLLAPSE".

Bottom Control Deck:
- Giant circular Push-To-Talk (PTT) button (diameter 100px) centered at the bottom with a dark metallic knurled texture and animated cyan pulsating glowing ring.
- Button text: "HOLD TO TALK" with a prominent microphone icon.
- Soundwave visualizer lines above the button simulating live audio capture.
- Toggle switch next to it: "VOX (Continuous Listen) [OFF]".
- Persistent Bottom Navigation Bar with 4 icons: RADIO (active), RADAR, COMMS, SETTINGS.
```

---

## 📡 Screen 2: Radar & Peer Connect (`01_connect_radar`)

### Stitch Prompt:
```text
Screen: Tactical Radar & Peer Mesh Discovery
Header:
- Title: "TACTICAL RADAR" with sub-label "Channel: TAC-RELIEF-04 (Bluetooth / Wi-Fi Direct)".
- Transport mode toggle pills at the top: [● BLUETOOTH RFCOMM] [WIFI-DIRECT P2P].

Radar Viewport:
- Circular sonar/radar display taking top 40% of the screen. Dark obsidian background with 3 concentric green rings (10m, 30m, 100m) and an animated 360-degree rotating radar sweep line.
- Blips plotted on the radar showing nearby responder devices with glowing signal dots and callsigns (e.g. "NODE-ALPHA", "MEDIC-02", "RESCUE-05").

Peer List Section:
- Subhead: "DISCOVERED UNITS (3 IN RANGE)".
- Card 1 (Connected & Verified): "MEDIC-02" | Role: Medical Officer | Transport: BT RFCOMM | Signal: -58 dBm (Strong, 4 green bars) | Status badge: "SECURE CONNECTED ✓" in emerald. Action: [CHAT] button.
- Card 2 (Available): "RESCUE-05" | Role: Field Scout | Signal: -78 dBm (2 yellow bars) | Status: "AVAILABLE". Action: [CONNECT] button.
- Card 3 (Unverified): "NODE-7229" | Signal: -84 dBm | Status: "HANDSHAKING...".

Modal Popup / Card Overlay (Cryptographic SAS Verification):
- Modal title: "CONFIRM IDENTITY (SAS CODE)".
- Prominent 6-digit cryptographic security code: "8 4 1 · 9 2 0" in large glowing cyan monospace font.
- Explanatory note: "Compare this exact number with peer's screen. If matching, tap Confirm to establish zero-trust encrypted mesh."
- 30-second circular countdown timer bar.
- Two large action buttons: [CONFIRM IDENTICAL] (Green) and [REJECT / ABORT] (Red outline).
```

---

## 💬 Screen 3: 1-on-1 Tactical Chat (`02_tactical_chat`)

### Stitch Prompt:
```text
Screen: Tactical 1-on-1 Encrypted Comms & Chat
Header:
- Back arrow icon, Peer Avatar with tactical callsign "MEDIC-02 (Col. Sharma)", green online dot, and active language pair badge "HI ➔ EN".
- Quick action icons: GPS Location Pin, Call/PTT, More Options.

Message Timeline:
- Date pill separator: "TODAY • 14:00 UTC".
- Message 1 (Incoming Audio Memo): Dark slate bubble with playable audio waveform, play/pause button, duration "0:06s", transcribed text: "आपातकालीन टीम साइट पर पहुँच गई है।", translated text in italics: "Emergency team has reached the site.", timestamp "14:02", double checkmark "✓✓".
- Message 2 (Outgoing Text): Cyan tinted bubble on right: "Understood. Maintaining radio silence on channel 4.", timestamp "14:03", "DELIVERED ✓✓".
- Message 3 (Incoming GPS Card): Tactical map preview box with coordinates "30.3165° N, 78.0322° E (Dehradun Sector 2)", and button [OPEN IN OFFLINE MAP].

Bottom Input Deck:
- Text field with placeholder "Type tactical message..." and dark gunmetal container.
- Quick action buttons: Paperclip (Attach coordinates/file), Quick Mic button for 1-tap Whisper STT voice recording, and Send arrow button.
```

---

## ⚙️ Screen 4: Tactical Settings & Emergency Zeroize (`03_settings`)

### Stitch Prompt:
```text
Screen: Tactical Settings & Device Security
Header:
- Title: "SETTINGS & TELEMETRY" with back button.
- Device Callout: "ID: IT-4921-X9" | Status: "AIRPLANE MODE READY".

Sections:
1. Operator Profile:
   - Editable text input: Operator Callsign ("ALPHA-LEAD").
   - Unit Designation ("14th Disaster Response Battalion").

2. Audio & Speech AI Settings:
   - Default Mic Language: Dropdown showing "Hindi (vasista22 Small Model - 13.8% WER)".
   - VAD Sensitivity: Slider from Low to High with visual threshold indicator.
   - Offline Language Packs: Shortcut button with storage badge "472 MB Used (2 of 10 Packs Installed)".

3. Hardware & Telemetry:
   - Live telemetry stats grid: CPU: 12% | RAM: 184MB | Temp: 34.2°C | Battery: 87% (4.12V).

4. Zero-Trust Security & Emergency Wipe:
   - Heavy danger card with red border and warning hazard icon.
   - Title: "EMERGENCY ZEROIZE (CRYPTO WIPE)".
   - Description: "Instantly destroys all SQLite cipher databases, private session keys, audio caches, and resets device identity."
   - Slide-to-unlock red slider button: ">>> SLIDE TO WIPE ALL DATA >>>".
```
