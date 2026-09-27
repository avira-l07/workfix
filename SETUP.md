# iTantra Setup Guide

## TTS Asset Setup (Required for Debug Builds)

Before running a debug build, TTS will not work until you run `python models/download_tts_models.py` and copy `staging_tts/<lang>/` into `app/src/main/assets/language_packs/<lang>/tts/` for each language. See `models/staging_tts/download_summary.json` for verified checksums.
