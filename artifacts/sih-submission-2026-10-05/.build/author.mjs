import fs from 'node:fs/promises';
import { importRuntimeModule } from 'file:///C:/Users/avira/.codex/plugins/cache/openai-primary-runtime/presentations/26.904.11930/skills/presentations/container_tools/runtime_helpers.mjs';
const {FileBlob,PresentationFile}=await importRuntimeModule('@oai/artifact-tool');
const root='D:/new itantra/artifacts/sih-submission-2026-10-05';
const p=await PresentationFile.importPptx(await FileBlob.load('C:/Users/avira/OneDrive/Desktop/iTantra_final_submission.pptx'));
const records=(await fs.readFile(root+'/.build/source-inspect.ndjson','utf8')).trim().split('\n').map(JSON.parse);
function edit(slide,name,text){
 const r=records.find(x=>x.slide===slide && x.name===name && x.kind==='textbox');
 if(!r) throw new Error(`Missing ${slide} ${name}`);
 const s=p.resolve(r.id); s.text=text;
}
const changes={
2:{
'TextBox 5':'iTANTRA turns speech into text for a constrained link, then reconstructs speech on the receiving Android device.',
'TextBox 41':'10 SELECTABLE LANGUAGE PACKS — DESKTOP WER SAMPLES',
'TextBox 43':'Hindi 9.1%', 'TextBox 45':'Gujarati 18.54%', 'TextBox 47':'Marathi 15.70%', 'TextBox 49':'Kannada 17.23%', 'TextBox 51':'Malayalam 28.91%',
'TextBox 53':'Tamil 21.8%', 'TextBox 55':'Telugu 22.7%', 'TextBox 57':'Odia 19.3%', 'TextBox 59':'Bengali 14.36%', 'TextBox 61':'English 6.9%',
'TextBox 63':'Benchmark scope:',
'TextBox 64':'30 clean clips per language; lower WER is better.',
'TextBox 65':'Phone recognition and native-listener TTS checks remain pending.',
'TextBox 15362':'Local STT/TTS after model download. Direct Bluetooth / Wi-Fi Direct text transport; optional MT has language-pair limits.'
},
3:{
'TextBox 7':'Kotlin / Compose + sherpa-onnx: local speech processing and encrypted direct peer links.',
'TextBox 19':'Selected pack', 'TextBox 31':'Selected voice',
'TextBox 32':'PROTOTYPE FEATURES',
'TextBox 34':'Local speech', 'TextBox 35':'STT/TTS after model provisioning',
'TextBox 37':'Text transport', 'TextBox 38':'Compact messages instead of audio',
'TextBox 40':'Speech modes', 'TextBox 41':'PTT and pause-delimited segments',
'TextBox 43':'Emergency alert', 'TextBox 44':'Four compact SOS codes + playback',
'TextBox 46':'Encrypted peers', 'TextBox 47':'ECDH / SAS / AES-GCM session',
'TextBox 49':'Local history', 'TextBox 50':'Room / SQLCipher storage',
'TextBox 52':'Pending validation: two-phone delivery • audible latency • Android RAM/CPU/battery • listener-rated TTS',
'TextBox 64':'Speech accuracy\nReview transcripts and repeat errors; Tamil/Telugu improvements are still under evaluation.',
'TextBox 65':'Short range\nDirect Bluetooth / Wi-Fi links with retry. Mesh and external radio gateways are future work.',
'TextBox 66':'Language packs\nDownload and load the selected pack. Model weights are separate from the APK.',
'TextBox 67':'Device resources\nVAD limits speech processing. Low/mid-range phone memory, CPU and battery tests are pending.'
},
4:{
'TextBox 1':'MEASURED STATUS + RELEASE GATES',
'TextBox 2':'CURRENT EVIDENCE — AND WHAT REMAINS',
'TextBox 10':'10 PACKS', 'TextBox 12':'454 TESTS', 'TextBox 14':'PROTOTYPE',
'TextBox 15':'454 unit tests passed in the latest recorded run. Recorded two-phone offline demonstration is pending.',
'TextBox 16':'DESKTOP BENCHMARK RESULTS',
'TextBox 18':'Hindi WER 9.1%', 'TextBox 20':'English WER 6.9%', 'TextBox 22':'Tamil WER 21.8%',
'TextBox 24':'Telugu WER 22.7%', 'TextBox 26':'Marathi TTS 181 ms', 'TextBox 28':'Malayalam WER 28.91%',
'TextBox 30':'MEASUREMENT SCOPE',
'TextBox 31':'WER: 30 clean clips/language. TTS: desktop mean synthesis time. Phone latency and listener ratings pending.',
'TextBox 33':'Accuracy 40%', 'TextBox 34':'WER + listener checks',
'TextBox 35':'Efficiency 20%', 'TextBox 36':'APK / RAM / CPU tests',
'TextBox 37':'Latency 20%', 'TextBox 38':'Speech + audible delay',
'TextBox 39':'Demo target', 'TextBox 40':'Two-phone offline demo'
},
5:{
'TextBox 17':'Selected\nSTT pack', 'TextBox 29':'Selected\nTTS voice',
'TextBox 40':'Speech segments after pauses',
'TextBox 43':'Priority alert playback',
'TextBox 47':'VALIDATION & RELEASE PLAN',
'TextBox 50':'GPU pilot', 'TextBox 52':'Train on train / dev', 'TextBox 54':'Locked WER tests',
'TextBox 56':'ONNX / INT8 checks', 'TextBox 58':'Two-phone demo', 'TextBox 64':'SIGNED RELEASE'
},
6:{'TextBox 17410':'REFERENCES & RELEASE GATES'}
};
for(const [slide,map] of Object.entries(changes)) for(const [name,text] of Object.entries(map)) edit(Number(slide),name,text);
for(const [name,width] of [['Rounded Rectangle 63',120],['TextBox 58',110]]) {
 const r=records.find(x=>x.slide===5&&x.name===name);
 p.resolve(r.id).position={width};
}
const ref=p.resolve('sh/vq5cve1s');
ref.text=[
'• Official problem SIH26173: sih.gov.in/sih2026PS',
'• Speech runtime: sherpa-onnx (offline Android STT/TTS)',
'• STT: IndicConformer / English FastConformer',
'• TTS: MMS-VITS + Marathi Piper; licences vary',
'• Desktop WER: FLEURS, 30 clean clips per language',
'• Tamil data: Kathbath, 4,428 train / 555 dev clips',
'• GPU setup passed; baseline retry and training pending',
'• Release gates: phone tests, licence review, signed APK'
];
ref.text.style={typeface:'Aptos',fontSize:24,color:'#000000',alignment:'left',verticalAlignment:'top',wrap:'square',autoFit:'none'};
const notes=[
'Identity and six-slide SIH template retained from the user-provided source. Problem statement confirmed at https://sih.gov.in/sih2026PS (SIH26173). Team ID 165027 and GIT BIT retained as supplied. All project status is as of 5 October 2026. This is a prototype submission, not a certification of field readiness.',
'Primary requirement: https://sih.gov.in/sih2026PS. Architecture and results: D:/new itantra/docs/LANGUAGE_BENCHMARKS.md and docs/QUICK_STAT_SUMMARY.md. WER values are separate clean desktop 30-clip FLEURS samples per language; they do not establish Android accuracy or performance on noisy field speech. Kannada uses the latest corrected result. Dataset: https://huggingface.co/datasets/google/fleurs. Offline use requires initial model provisioning. Optional ML Kit translation cannot cover Odia/Malayalam pairs. Template photograph retained from supplied deck; no claim of a tested rescue deployment.',
'Implementation evidence: local Android source and docs/QUICK_STAT_SUMMARY.md. Runtime: https://k2-fsa.github.io/sherpa/onnx/index.html. Native Kotlin/Compose UI, Room/SQLCipher history, sherpa-onnx STT/TTS, Bluetooth RFCOMM and Wi-Fi Direct TCP. Peer verification uses ECDH, SAS and AES-GCM. These are implemented features; the complete two-phone offline audible demo and real-device resources remain unverified. Mesh, satellite and an external radio gateway are not implemented. Maximum-volume/non-interruptible SOS behavior still needs device validation.',
'Evidence: docs/LANGUAGE_BENCHMARKS.md, docs/QUICK_STAT_SUMMARY.md and the latest recorded app unit-test reports (454 tests, 0 failures). WER is 30 clean desktop clips/language; smaller is better. Marathi Piper mean synthesis latency is 181 ms over 30 phrases; this excludes playback, transport and Android overhead and is not a native-listener intelligibility score. Official evaluation at https://sih.gov.in/sih2026PS explicitly lists accuracy 40%, efficiency 20%, latency 20%; this deck does not invent the remaining allocation. Phone latency, RAM/CPU/battery, listener ratings and recorded two-phone demo are release gates. Photograph retained from the supplied template.',
'Pipeline reflects current app code. PTT-off processes speech segments after pauses; simultaneous full-duplex calling is not claimed. The lower row is a future validation sequence, not completed training/export. Tamil saved data passed leakage checks: 4,428 train / 555 dev clips, 123 / 14 speakers, 8.882 / 1.118 hours, 4,983 rows with zero overlaps/errors. GPU setup and model restore were verified in received Kaggle reports; baseline failed on float-format evaluation WAVs, reader repair is prepared and retry is pending. Training, improved WER, ONNX export, INT8 validation and signed release are not complete.',
'Primary sources: SIH https://sih.gov.in/sih2026PS; sherpa-onnx https://k2-fsa.github.io/sherpa/onnx/index.html; Tamil IndicConformer https://huggingface.co/ai4bharat/indicconformer_stt_ta_hybrid_ctc_rnnt_large; FLEURS https://huggingface.co/datasets/google/fleurs; Kathbath https://huggingface.co/datasets/ai4bharat/Kathbath. Local evidence: docs/LANGUAGE_BENCHMARKS.md, docs/QUICK_STAT_SUMMARY.md, docs/KATHBATH_TAMIL_PILOT.md, tools/stt_results/tamil-gate-b-datacheck-2026-10-05 and tools/stt_results/tamil-gate-b-float32-repair-2026-10-05. MMS-VITS, Piper voices and other model assets have differing licences; complete licence and redistribution review before release. No general improved WER or Android listener quality is claimed.'
];
for(let i=0;i<6;i++) p.resolve(records.find(x=>x.kind==='slide'&&x.slide===i+1).id).speakerNotes.textFrame.setText(notes[i]);
await (await PresentationFile.exportPptx(p)).save(root+'/.build/candidate.pptx');
console.log('Edited 6 existing slides; candidate exported.');

