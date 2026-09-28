# iTantra user guide

iTantra is an offline, peer-to-peer voice and text communicator. It is designed for two nearby Android devices to communicate over Bluetooth or Wi-Fi Direct when mobile service or the internet is unavailable.

The app has four main operating areas:

- **Hub** — speak, listen, choose languages, send emergency codes and view current link status.
- **Connect** — find another nearby phone, establish the secure link and open its chat.
- **Language** — download language packs and choose the microphone, receiving voice and translation languages.
- **Diagnostics** — measure speech, translation, link and device performance.

Settings is opened with the gear icon in the Hub.

## Before using the app

Install iTantra on both phones. The current build packages the native speech engine for 64-bit ARM phones. Keep both phones charged, unlock the screen during first setup and place the phones within normal Bluetooth or Wi-Fi Direct range.

On the first launch:

1. Allow microphone access. The microphone is required for push-to-talk and continuous listening.
2. Allow nearby-device/Bluetooth access when Android asks. On older Android versions, allow location access if Android requests it for discovery.
3. Allow notifications if you want emergency alerts while the app is in the background.
4. Wait for the app to finish preparing its local speech files. Speech and translation are offline only after the required models are installed.

If a permission was denied, use the permission banner at the top of the Hub and tap **REQUEST PERMISSIONS**. Android may require you to enable a denied permission from the system Settings screen.

## The first setup on each phone

### 1. Choose the language pack

From the Hub, tap **Language**. Each language card shows its installed state and three readiness indicators:

- **STT** means speech-to-text: the phone can understand that language from the microphone.
- **TTS** means text-to-speech: the phone can read received text aloud in that language.
- **MT** means machine translation. **MT: BYPASS** means the app keeps the original text for that language instead of claiming that a translation is available.

Use the controls on a language card as follows:

- **DOWNLOAD PACK** downloads the selected offline speech pack. Do this while internet is available. The size is shown on the button.
- **CANCEL DOWNLOAD** stops an in-progress download.
- **RETRY PROVISION** retries a failed download or installation.
- **Speak (Mic)** selects or deselects that language for microphone input.
- **Receive (TTS)** selects or deselects that language for spoken playback.
- **SET AS ACTIVE MIC ENGINE** makes that installed pack the active speech-recognition engine.
- **SET AS TRANSLATION TARGET** makes that language the outgoing translation target.
- **RESET TO AUTO** removes the fixed translation target and returns to automatic/same-language behavior.

The Hub also has quick selectors. **MIC LANGUAGE** has Hindi, English and **More**. Hindi or English changes the microphone language immediately; **More** opens the complete Language screen. **TRANSLATE TO** has **Auto**, Hindi and English, with **More** for other configured languages.

For a simple first test, install and select Hindi or English for **Speak (Mic)** and **Receive (TTS)** on both phones. Set **TRANSLATE TO: Auto** until the direct voice path is working.

### 2. Connect the two phones

Do this on both phones:

1. Open **Connect** from the Hub.
2. Select the same transport on both phones: **BLUETOOTH** for a normal nearby Bluetooth link, or **WI-FI DIRECT** for a local Wi-Fi peer link.
3. On Bluetooth, tap **BROADCAST DISCOVERY PING** or use the listed nearby device. On Wi-Fi Direct, tap **DISCOVER WI-FI DIRECT PEERS**.
4. On the phone that will initiate the connection, tap **CONNECT** next to the other phone.
5. Wait for **CONNECTING…** and then **WAITING FOR SECURE HANDSHAKE…**.
6. Both operators will see **VERIFY SAS**. Read the six-digit short authentication code aloud or compare it face-to-face. Continue only when both codes match.
7. Tap the confirmation action labelled **CODES MATCH · TRUST** on both phones. If the codes do not match, tap **REJECT / CANCEL**, disconnect and start again.
8. When the peer shows **SECURE CONNECTED**, tap **OPEN CHAT** if you want a dedicated conversation view.

The Hub status cards explain the connection:

- **PEER: DISCONNECTED / OFFLINE** — no active peer link.
- **PEER: LISTENING… / LINKING** — the phone is waiting or negotiating.
- **PEER: CONNECTED / ACTIVE** — a transport link is present.
- **ENCRYPTED (SECURE SESSION ACTIVE)** — the key exchange and SAS verification are complete.

Do not treat **CONNECTED** alone as proof that the peer identity was verified; wait for the encrypted secure-session indicator.

## Sending a normal voice message

### Push-to-talk mode

1. On the Hub, select **PUSH-TO-TALK (PTT)** in the mode switch.
2. Confirm that the language beside **MIC LANGUAGE** is correct.
3. Touch and hold the large centre microphone button.
4. Speak clearly while holding it.
5. Release the button to finish and transmit the recording.

The centre button shows **HOLD TO TALK**, then **TRANSMITTING** while the recording is being processed. The Hub message list shows the outgoing message and its delivery state.

For a longer message, begin holding the button and slide your finger upward. The button changes to **TAP TO SEND** and **RECORD LOCKED**. Speak without holding the screen. Tap the red **RECORDING LOCKED — TAP TO SEND** button when finished. Tap the centre button again to cancel a locked recording before sending it.

### Continuous VAD mode

1. Select **CONTINUOUS VAD** in the mode switch.
2. The centre control shows **LISTENING…** and **HANDS-FREE VAD**.
3. Speak normally. Silero voice activity detection starts and stops segments automatically.
4. During speech the status changes to **VOICE ACTIVE**; after speech it changes to **PROCESSING** and **TRANSMITTING**.
5. Tap the centre control once to pause. It shows **PAUSED** and **TAP TO RESUME**. Tap again to resume.

Use PTT in noisy or unpredictable environments. Use Continuous VAD when the phone is stationary and hands-free operation is more important than manual control.

## Receiving a message

When a peer sends a message, iTantra stores the text and shows its status in the Hub or dedicated chat. If the receiving language has a TTS pack installed and selected, the message is spoken aloud. If spoken output is unavailable, use the displayed text; the message is not silently replaced with an invented translation.

The translation target is an instruction for outgoing messages. To control what you hear, select the receiving language on the Language screen with **Receive (TTS)**.

In a dedicated chat, use the back arrow to return to Connect, then the back arrow again to return to the Hub. A message’s language and delivery state are part of its history, so wait for the delivery acknowledgement before assuming the peer received it.

## Sending a location

When the location action is available in the Hub or chat, grant the location permission and enable device location. The app packages the current coordinates, accuracy and timestamp for the encrypted peer message. The receiver can view the coordinate, copy it or open it in a map application.

Check the timestamp and accuracy before acting on a location. A coordinate with poor accuracy or an unverified time should be treated accordingly. A map application may create a copy outside iTantra’s storage controls.

## Emergency quick codes

The Hub contains **EMERGENCY QUICK CODES**. These are priority messages and require a confirmation dialog. Use them only when the situation warrants it:

- **EVACUATE — CODE-E1**: request or announce evacuation.
- **MEDICAL SOS — CODE-M2**: request medical help.
- **ROUTE BLOCKED — CODE-B3**: report an unusable route.
- **ASSISTANCE REQ. — CODE-A4**: request operational assistance.
- **TRIGGER CRITICAL PTT BROADCAST**: send a critical priority voice broadcast.

To send one, tap the preset, read the confirmation carefully and authorize it. If you chose the wrong preset, cancel the confirmation dialog. After sending, watch the message state and the emergency alert. Transport acknowledgement means the peer received the packet; it does not necessarily mean a human operator has acted on it. Use the peer’s human acknowledgement or **ALL_CLEAR** workflow when the incident is resolved.

The current app is a direct peer communicator. The emergency broadcast label does not create a multi-hop mesh or a many-device command network.

## Diagnostics

Open **Diagnostics** from the Hub. The screen is divided into:

- **AI · Speech Models** — Hindi WER, English reference WER, character error rate, real-time factor and active model.
- **Translation · Hindi ↔ English** — shows whether the English-to-Hindi offline model is ready. **PROVISION EN→HI OFFLINE MODEL (WHILE ONLINE)** downloads it while internet is available.
- **Communication · Link Health** — latency, packet loss, retries and delivery ratio.
- **Device · Resource Use** — CPU, memory, battery and temperature.

Tap **RUN COMPREHENSIVE ON-DEVICE DIAGNOSTICS** after connecting a peer and selecting the desired speech pack. If Continuous VAD is active, the button changes to **STOP CONTINUOUS LISTENING FIRST**; pause continuous listening before running diagnostics. A value of **Not yet measured** means the measurement has not run, not that the value is zero.

## Settings

Open Settings with the gear icon on the Hub.

- **Operator identity** stores the display name used by the device profile.
- **Transport** explains the available encrypted Bluetooth and Wi-Fi Direct paths.
- **Receiving language** controls the default language used for spoken incoming messages.
- **VAD Sensitivity** changes how easily Continuous VAD detects speech. Lower is safer in noisy areas; higher suits quiet speech.
- **Dynamic Noise Suppression Level** controls suppression strength. Aggressive suppression can remove quiet speech.
- **Critical Evacuation & SOS Confirmation** controls the emergency confirmation behavior.
- **Reset Settings to Field Default** restores settings and VAD calibration defaults after a confirmation dialog.
- **Wipe all data** permanently removes private messages, emergency records, identity, settings, diagnostic files, database keys and caches. It deliberately keeps downloaded language packs and model files. Open it only when you intend to erase local app data; press and hold **Hold to wipe** in the confirmation dialog.

After a wipe, the app restarts in a clean first-run state. If storage cannot be decrypted after a restore or device change, iTantra shows **Stored data cannot be decrypted** and keeps the source files rather than replacing them silently.

## A recommended first exercise

Use two phones in a quiet room:

1. Install the same Hindi or English speech pack on both.
2. Grant microphone, nearby-device and notification permissions.
3. Connect over Bluetooth.
4. Compare the SAS codes and confirm the secure session on both phones.
5. Leave **MIC LANGUAGE** and **TRANSLATE TO: Auto** selected.
6. Send a short PTT message and wait for its delivery state.
7. Switch both phones to Continuous VAD, pause it once, resume it, then send another short sentence.
8. Open Diagnostics and run the comprehensive check after stopping Continuous VAD.
9. Test one emergency preset only in a controlled exercise, then resolve it with the available acknowledgement workflow.
10. Test location sharing outdoors and compare the displayed accuracy with the phone’s map position.

Record which language packs were installed, which transport was used and whether the peer was verified. Repeat the exercise with the phones locked and at the intended operating distance before relying on the app operationally.

## Troubleshooting

**The peer does not appear.** Keep both Connect screens open, select the same transport, enable Bluetooth/Wi-Fi and nearby-device permissions, and retry discovery. On older Android versions, also grant the location permission requested for discovery.

**The connection stays at “WAITING FOR SECURE HANDSHAKE”.** Keep both phones awake and nearby. If it does not advance, disconnect and reconnect. Both operators must complete SAS verification.

**The codes do not match.** Reject/cancel the verification, disconnect both phones and start a fresh connection. Never trust a mismatched code.

**Speech is unavailable.** Open Language, check the selected pack’s STT and TTS indicators, download the pack while online if necessary, and use **SET AS ACTIVE MIC ENGINE**.

**Translation is unavailable.** Set **TRANSLATE TO: Auto** for same-language communication, or open Diagnostics and provision the required offline translation model while online. Malayalam and Odia routes can intentionally remain text-only/original-text fallback paths.

**Continuous VAD triggers too often or misses speech.** Adjust VAD sensitivity and noise suppression in Settings. Use PTT when the environment is loud or when every utterance must be manually delimited.

**The emergency alert repeats.** A transport ACK is not a human acknowledgement. Confirm the receiving operator has seen and acknowledged the alert, or use the resolution/ALL_CLEAR workflow when appropriate.

**The app says stored data cannot be decrypted.** The device’s Android Keystore key may be unavailable after a restore or device change. Close and retry on the original device. If the data cannot be recovered, use Wipe all data to start clean; the app does not silently destroy the retained source files.

**The wipe does not finish.** Leave the app open until the isolated wipe screen completes. If it reports a failure, tap **Retry wipe**. Do not reinstall or manually delete files while the app is still attempting recovery.

## Important operating limits

The current build provides direct one-to-one Bluetooth/Wi-Fi Direct communication. It does not yet provide store-and-forward mesh routing, multi-hop relays, true group broadcast to many connected phones, a shared offline map, photo/file transfer or a web dashboard. Range, battery life, noisy-environment accuracy, reboot recovery and locked-screen field operation must be tested on the actual phones and Android versions intended for deployment.
