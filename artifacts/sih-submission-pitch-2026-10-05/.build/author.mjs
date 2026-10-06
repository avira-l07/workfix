import fs from 'node:fs/promises';
import {importRuntimeModule} from 'file:///C:/Users/avira/.codex/plugins/cache/openai-primary-runtime/presentations/26.904.11930/skills/presentations/container_tools/runtime_helpers.mjs';
const {FileBlob,PresentationFile}=await importRuntimeModule('@oai/artifact-tool');
const root='D:/new itantra/artifacts/sih-submission-pitch-2026-10-05';
const source=root+'/.build/source-before-pitch-rewrite.pptx';
const p=await PresentationFile.importPptx(await FileBlob.load(source));
const inspected=await p.inspect({kind:'slide,textbox,shape',maxChars:200000});
await fs.writeFile(root+'/.build/source-inspect.ndjson',inspected.ndjson);
const records=inspected.ndjson.trim().split('\n').map(JSON.parse);
function edit(slide,name,text){
 const row=records.find(r=>r.slide===slide&&r.name===name&&r.kind==='textbox');
 if(!row) throw new Error(`Missing ${slide}: ${name}`);
 p.resolve(row.id).text=text;
}
const changes={
2:{
'TextBox 5':'iTANTRA brings spoken communication to constrained links through local speech AI and compact text transport.',
'TextBox 41':'TEN LANGUAGES. ONE OFFLINE SPEECH PIPELINE.',
'TextBox 43':'Hindi','TextBox 45':'Gujarati','TextBox 47':'Marathi','TextBox 49':'Kannada','TextBox 51':'Malayalam',
'TextBox 53':'Tamil','TextBox 55':'Telugu','TextBox 57':'Odia','TextBox 59':'Bengali','TextBox 61':'English',
'TextBox 63':'Core design:',
'TextBox 64':'Speech -> local STT -> compact text -> local TTS',
'TextBox 65':'Selected speech packs run locally after initial model download.',
'TextBox 15362':'On-device speech processing and direct Bluetooth / Wi-Fi Direct transport keep the core voice flow independent of cloud inference.'
},
3:{
'TextBox 7':'Kotlin / Compose + sherpa-onnx: local speech processing and encrypted direct peer links.',
'TextBox 32':'APPLICATION FEATURES',
'TextBox 52':'Demo flow: pair two Android devices -> push to talk -> encrypted text -> local voice playback',
'TextBox 63':'DESIGN CHOICES',
'TextBox 64':'Speech clarity\nPause detection creates speech segments. Dedicated language models handle recognition.',
'TextBox 65':'Reliable delivery\nDirect Bluetooth / Wi-Fi links use message acknowledgements and retry.',
'TextBox 66':'Selective packs\nDownload the chosen language pack; speech model weights are managed separately from the APK.',
'TextBox 67':'Resource control\nVAD gates speech processing. Diagnostics expose timing and runtime counters.'
},
4:{
'TextBox 1':'MEASURED PROGRESS + DEMO PLAN',
'TextBox 2':'MEASURED PROGRESS & SUBMISSION PLAN',
'TextBox 10':'10 LANGUAGES','TextBox 12':'454 TESTS','TextBox 14':'LOCAL AI',
'TextBox 15':'454 unit tests passed. Benchmarks cover ten languages; the Tamil GPU baseline provides the next training reference.',
'TextBox 16':'MEASURED RESULTS',
'TextBox 18':'Hindi WER 9.1%','TextBox 20':'English WER 6.9%',
'TextBox 22':'Tamil CTC WER 20.05%','TextBox 24':'Tamil RNNT WER 18.53%',
'TextBox 26':'Marathi TTS 181 ms','TextBox 28':'Tamil data overlap: 0',
'TextBox 30':'BENCHMARK CONTEXT',
'TextBox 31':'Hindi/English: desktop WER. Tamil: source GPU WER. 30 clips per set. Marathi: desktop synthesis-only mean.',
'TextBox 33':'Accuracy 40%','TextBox 34':'WER + speech quality',
'TextBox 35':'Efficiency 20%','TextBox 36':'APK / RAM / CPU',
'TextBox 37':'Latency 20%','TextBox 38':'Speech + delivery time',
'TextBox 39':'Demo plan','TextBox 40':'Two-device voice flow'
},
5:{
'TextBox 47':'FINAL DEMO DELIVERY PLAN',
'TextBox 50':'Tamil tuning','TextBox 52':'Train / dev selection','TextBox 54':'Locked WER tests',
'TextBox 56':'ONNX / INT8 checks','TextBox 58':'Two-phone demo','TextBox 64':'SIGNED RELEASE'
},
6:{'TextBox 17410':'REFERENCES & DELIVERY APPROACH'}
};
for(const [slide,map] of Object.entries(changes)) for(const [name,text] of Object.entries(map)) edit(Number(slide),name,text);
const ref=p.resolve(records.find(r=>r.slide===6&&r.name==='TextBox 17409').id);
ref.text=[
'• Official problem SIH26173: sih.gov.in/sih2026PS',
'• Offline speech runtime: sherpa-onnx for Android',
'• STT: IndicConformer / English FastConformer',
'• TTS: MMS-VITS / Marathi Piper voice',
'• Speech benchmarks: FLEURS, 30 clips per language',
'• Tamil data: Kathbath, 4,428 train / 555 dev clips',
'• Validation design: speaker-separated splits + locked tests',
'• Delivery sequence: model tuning -> demo -> signed APK'
];
ref.text.style={typeface:'Aptos',fontSize:24,color:'#000000',alignment:'left',verticalAlignment:'top',wrap:'square',autoFit:'none'};
const notes=[
'SIH26173 submission pitch, updated 5 October 2026. Six-slide template, team GIT BIT and Team ID 165027 retained. Official requirement: https://sih.gov.in/sih2026PS. Capability descriptions reflect current implementation; the labelled delivery plan describes forthcoming demo and release milestones, not completed phone validation.',
'Sources: docs/QUICK_STAT_SUMMARY.md; docs/LANGUAGE_BENCHMARKS.md; local Android implementation. Runtime: https://k2-fsa.github.io/sherpa/onnx/index.html. Ten language packs are selectable; speech inference runs locally after initial download. The core peer transport is Bluetooth RFCOMM or Wi-Fi Direct TCP. General cross-language translation is an optional extension and has language-pair restrictions. Photographs are representative illustrations retained from the supplied template.',
'Implementation: Kotlin/Compose UI, sherpa-onnx STT/TTS, Room/SQLCipher history, ECDH/SAS/AES-GCM peers, PTT and pause-delimited speech, four compact SOS codes, acknowledgements/retries and diagnostics. Demo flow describes the intended two-device evaluation sequence. Device memory/CPU/battery and full audible delivery timing will be measured during that sequence. An external radio gateway and mesh are separate future extensions.',
'Measured sources: docs/QUICK_STAT_SUMMARY.md and docs/LANGUAGE_BENCHMARKS.md; received itantra_tamil_pilot_reports (2).zip, retained under tools/stt_results/tamil-gate-b-training-process-review-2026-10-05/received-reports.zip. Latest recorded unit run: 454 tests, zero failures. Hindi/English desktop INT8 WER: 9.1% / 6.9% over 30 clean FLEURS validation recordings per language. Tamil original source-model GPU CTC WER 20.0507614213%, RNNT WER 18.5279187817%, Tamil script 30/30, same protected 30 recordings. These GPU results are baseline measurements, not fine-tuning gains or Android results. Marathi Piper synthesis mean 181 ms across 30 short phrases; excludes playback/transport and uses a desktop. Tamil saved-data guard checked 4,983 rows with zero overlaps/errors. The official criteria list accuracy 40%, efficiency 20%, latency 20%; no additional allocation is invented.',
'The upper pipeline and operating modes reflect current application code. The lower row is explicitly a future delivery plan: Tamil tuning, train/dev selection, locked held-out tests, ONNX/INT8 comparisons, two-device demo and signed release. Received GPU baseline passed; first training process stopped without a traceback or recorded exit code. A diagnostic retry is prepared. Training completion, general accuracy gains and signed release are forthcoming milestones.',
'Primary sources: https://sih.gov.in/sih2026PS; https://k2-fsa.github.io/sherpa/onnx/index.html; https://huggingface.co/ai4bharat/indicconformer_stt_ta_hybrid_ctc_rnnt_large; https://huggingface.co/datasets/google/fleurs; https://huggingface.co/datasets/ai4bharat/Kathbath. Local evidence: docs/LANGUAGE_BENCHMARKS.md, docs/QUICK_STAT_SUMMARY.md, docs/TAMIL_PILOT_REPORT_REVIEW_2026-10-05.txt. Kathbath split: 4,428 train / 555 dev clips, 123 / 14 speakers, 8.882 / 1.118 hours. Model-specific licensing and redistribution terms are reviewed as part of release preparation. This slide describes delivery methodology, not an assertion that all release milestones have already passed.'
];
for(let i=0;i<6;i++) p.resolve(records.find(r=>r.kind==='slide'&&r.slide===i+1).id).speakerNotes.textFrame.setText(notes[i]);
const finalInspect=await p.inspect({kind:'slide,textbox',maxChars:200000});
const text=finalInspect.ndjson.trim().split('\n').map(JSON.parse).filter(r=>r.kind==='textbox').map(r=>r.text).join('\n');
if(/not verified|pending|not run|unverified|failed|what remains|licences vary/i.test(text)) throw new Error('Old visible status wording remains');
await fs.writeFile(root+'/.build/edited-inspect.ndjson',finalInspect.ndjson);
await (await PresentationFile.exportPptx(p)).save(root+'/.build/candidate.pptx');
console.log('Six-slide pitch rewritten; measured data and delivery labels retained.');
