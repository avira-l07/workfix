# iTantra - Offline Multilingual Voice Communication

Android project submitted for internship review. This page includes the installable APK and the project source code.

## What I built

- A Kotlin / Jetpack Compose Android interface for voice communication and chat.
- Speech-to-text, translation and repeatable text-to-speech playback using installed language packs.
- Encrypted peer-to-peer messaging over Bluetooth and Wi-Fi Direct, with a verification code for pairing.
- GPS location sharing, saved transcripts, message timestamps, and a recycle bin with seven-day restoration.
- User-selected removal of messages, voice notes, diagnostics, or all private data while keeping downloaded language models.
- Security hardening for message receipts, incoming queue limits, SOS ownership, release logging and message retention during disconnects.

## Try the Android demo

1. Download **iTantra-1.15-internship-demo.apk** under Assets below.
2. Install on an **Android 8.0+ ARM64 phone**. Android may ask you to allow installation from your browser or file manager.
3. Open Language packs and install the supported speech/translation packs you want to demonstrate. Pack downloads need Internet access; supported installed workflows can then run offline.
4. Try speech transcription and Play speech on one phone. For peer messaging, install on two compatible phones, choose Bluetooth or Wi-Fi Direct, and confirm the matching verification code on both phones.
5. Explore saved messages, location sharing, the recycle bin and Settings > Manage private data.

## Verification and project links

- Version: **1.15-security**, version code **16**; APK built and checked on 8 October 2026.
- **570 host tests passed** with no failures, errors or skipped tests.
- Local source and APK security scans completed; documented dependency warnings and device checks remain.
- [Project source code](https://github.com/avira-l07/workfix)

This is a **development-signed review/demo build**, not a production store release. Physical two-phone verification remains separate from host tests. Translation quality varies by language pair and installed models; no universal accuracy percentage is claimed.

APK SHA-256: **226186b709a9eefb57bedc26ff1ab71670ee172fad6beb53ce4d69710a687e57**
