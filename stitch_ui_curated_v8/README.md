# iTantra UI references curated from Stitch ZIP (8)

This folder contains **one selected concept per app view**. Each view has the original `code.html` and its matching `screen.png`, copied without modification from `stitch_app_ui_design_concepts (8).zip`. Open `index.html` to browse them.

| View | Selected source folder in ZIP (8) | Status |
| --- | --- | --- |
| Hub | `transceiver_hub_reality_complete` | Useful layout; needs corrections below |
| Connect | `connect_radar_peer_discovery` | Useful layout; needs corrections below |
| Chat | `1_on_1_tactical_chat_reality_complete` | Useful layout; needs corrections below |
| Voice Notes | `voice_notes_local_logs_clean_reality` | Best available note layout; needs corrections below |
| Language Packs | `language_packs_model_vault` | Useful ten-language catalog; needs corrections below |
| SOS | `sos_emergency_flow` | Useful preset layout; needs corrections below |
| Settings | `tactical_settings_wipe_data` | Useful wipe flow; needs corrections below |
| Diagnostics | `system_diagnostics_inspection` | Useful section layout; needs corrections below |

These are **visual references, not implemented Android screens**. The HTML may rely on online fonts or Tailwind for rendering. A button in a Stitch prototype does not establish that the Android app has that feature.

## Corrections before implementation

- **Hub:** hide prototype connection-state controls in the user-facing version. The SOS confirmation must address the connected peer, not “all reachable RF nodes.” Remove the actionable Play Synth control unless real playback is wired. Show disconnected, connecting, SAS, link-lost, and model-missing states as separate designs.
- **Connect:** remove mesh/BLE wording and the incorrect blanket claim that Android 12+ Bluetooth discovery requires location. Add permission-denied, no-peer, timeout, and reconnect states.
- **Chat:** remove the audio playback control, which the current chat screen does not provide. Keep typed Send, PTT, location actions, translation status, and Retry.
- **Voice Notes:** remove search unless it is implemented. Notes contain transcript text, language, and time; no audio is stored for note playback. Keep the five date groups and delete confirmation.
- **Language Packs:** add per-language Speak (Mic) and Receive (TTS) selection. Show independent STT, TTS, and ML Kit translation readiness, progress, cancel, and retry. Read sizes and storage from actual app state.
- **SOS:** separate sending, peer transport ACK, human acknowledgement, failure, and retry. Do not claim physical radio-chip delivery or guaranteed peer volume.
- **Settings:** keep press-and-hold wipe and the fact that downloaded models remain. Remove hardcoded channel, storage, and signal values.
- **Diagnostics:** display “Not yet measured” for unavailable metrics. Remove fabricated hardware and integrity readings.

The ZIP's duplicate older Hub, Chat, and Voice Notes folders were omitted. Both bundled `DESIGN.md` files were omitted because the old one describes unsupported mesh/radio behavior and the newer one still includes unsupported telemetry assumptions. Existing workspace files under `stitch_ui/` were not changed.
