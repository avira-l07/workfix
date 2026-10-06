import fs from 'node:fs/promises';
import { importRuntimeModule } from 'file:///C:/Users/avira/.codex/plugins/cache/openai-primary-runtime/presentations/26.904.11930/skills/presentations/container_tools/runtime_helpers.mjs';
const {FileBlob,PresentationFile}=await importRuntimeModule('@oai/artifact-tool');
const p=await PresentationFile.importPptx(await FileBlob.load('C:/Users/avira/OneDrive/Desktop/iTantra_final_submission.pptx'));
const snap=await p.inspect({kind:'slide,textbox,shape,image,table,chart,layout',maxChars:180000});
await fs.writeFile('D:/new itantra/artifacts/sih-submission-2026-10-05/.build/source-inspect.ndjson',snap.ndjson);
console.log(snap.ndjson);
