# Four selectable offline voice languages

Version **1.4-four-ctc** supports Hindi, English, Tamil and Telugu as explicit
microphone languages. Each uses its own on-demand, checksum-verified CTC STT
model. Each has an on-demand MMS-VITS TTS pack. A connected peer receives text
and synthesizes audio in its selected receive language. Different-language
routes use local ML Kit translation models; the code permits all 12 directed
cross-language routes, but two-phone translation quality is not yet measured.

In **Language Packs**, choose each language under **Speak (Mic)**, **Receive
(TTS)**, or both, then tap **Apply & provision selected languages** while online.
The app downloads only the selected speech direction for that language and
prepares its translation model separately. If MT is missing after speech is
ready, tap **Prepare offline translation** on the language card. The **STT**,
**TTS**, and **MT** indicators show each component separately. For voice input,
select the installed language explicitly under **Mic language**. **Auto** still
uses the older shared multilingual Whisper model and requires that separate
download; the four measured CTC models do not perform language detection.

On the receiving phone, select the desired **Receive language**, install its
TTS pack, and prepare the relevant translation models. Then test on two
connected phones and repeat with mobile data and Wi-Fi off. First-time
downloads need internet; prepared speech and translation run on device.

| Language | Manual STT model | STT files | TTS model | TTS files |
|---|---|---:|---|---:|
| Hindi | IndicConformer CTC INT8 | 197.7 MB | MMS-VITS Hindi | 114.0 MB |
| English | NeMo FastConformer CTC INT8 | 174.6 MB | MMS-VITS English | 114.0 MB |
| Tamil | IndicConformer CTC INT8 | 197.7 MB | MMS-VITS Tamil | 114.0 MB |
| Telugu | IndicConformer CTC INT8 | 197.7 MB | MMS-VITS Telugu | 114.0 MB |

Installing both speech directions for all four occupies **1,223,743,009
bytes** (1.224 GB decimal) before ML Kit's translation models, app data, or
temporary download space. The small default APK contains none of these weights.
Old Whisper packs do not count as ready for the new manual CTC models; update
users must download their selected language models once.

Detailed measurements and limitations: [four-language model report](FOUR_LANGUAGE_MODEL_REPORT.md).
