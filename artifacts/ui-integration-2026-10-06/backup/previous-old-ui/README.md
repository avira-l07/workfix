# Present UI Archive (Backup)

This directory contains a complete snapshot of the **present Android Jetpack Compose UI** from `app/src/main/java/com/example/itantra/ui/` and `MainActivity.kt`, backed up before transitioning to the new light UI architecture.

---

## 📁 Directory Structure & File Map

### 1. Root Activity
- **`MainActivity.kt`**: Main application scaffold, navigation destination state machine (`AppDestination`), top-level app bars, and dialog hosts.

### 2. Components (`components/`)
- **`BatteryIndicator.kt`**: Live Android battery indicator using `BatteryManager` and sticky broadcasts.
- **`MessageFilterChipsRow.kt`**: Filter chips row (`ALL`, `SENT`, `RECEIVED`, `FAILED`).
- **`RadioHardwareStatus.kt`**: Live hardware status monitors for Bluetooth adapter and Wi-Fi Direct state.

### 3. Screens (`screens/`)
- **`hub/TransceiverHubScreen.kt`**: Main transceiver hub with PTT deck, emergency SOS bottom sheet, audio waveforms, and feed.
- **`chat/DedicatedChatScreen.kt`**: 1-on-1 peer conversation screen, message bubbles, GPS card formatting, and delivery status icons.
- **`chat/DateUtils.kt`**: 12-hour timestamp formatting and relative time calculations.
- **`connect/ConnectScreenContent.kt`**: Bluetooth RFCOMM & Wi-Fi Direct discovery list, SAS 6-digit confirmation modal, peer cards.
- **`diagnostics/DiagnosticsScreen.kt`**: Hardware and connection diagnostics screen, live battery and radio status.
- **`language/LanguagePacksScreen.kt`**: 10-language pack management, download triggers, progress bars.
- **`language/TranslationReadiness.kt`**: Offline translation engine readiness evaluation and status helpers.
- **`messages/MessagesScreen.kt`**: Multi-peer conversation list overview.
- **`messages/ConversationList.kt`**: Conversation preview rows and unread badges.
- **`settings/SettingsScreen.kt`**: Profile editing, hardware ID, data wipe action, links to diagnostics and packs.
- **`settings/SettingsViewModel.kt`**: ViewModel handling user preferences and profile state.
- **`settings/WipeDataAction.kt`**: 2-second hold confirmation logic for local data wiping (preserving downloaded models).
- **`voicenotes/VoiceNotesScreen.kt`**: Private local voice notes list with WhatsApp-style date separators and delete dialog.
- **`voicenotes/VoiceNoteGrouping.kt`**: Date bucket clustering logic (`Today`, `Yesterday`, etc.).

### 4. Theme Tokens (`theme/`)
- **`Color.kt`**: Original corporate and tactical color tokens (`ITantraColors`, `CanvasBg`, `Primary`, etc.).
- **`Theme.kt`**: Compose `MaterialTheme` configuration and color scheme setup.
- **`Type.kt`**: Typography styles using system and monospace font families.
