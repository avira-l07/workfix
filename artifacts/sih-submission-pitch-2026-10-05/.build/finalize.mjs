import fs from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {finalizePresentation} from 'file:///C:/Users/avira/.codex/plugins/cache/openai-primary-runtime/presentations/26.904.11930/skills/presentations/container_tools/artifact_tool_utils.mjs';
const root='D:/new itantra/artifacts/sih-submission-pitch-2026-10-05';
const skill='C:/Users/avira/.codex/plugins/cache/openai-primary-runtime/presentations/26.904.11930/skills/presentations';
const source=root+'/.build/source-before-pitch-rewrite.pptx';
const sha=createHash('sha256').update(await fs.readFile(source)).digest('hex');
const result=await finalizePresentation({
 workspaceDir:root,candidatePath:root+'/.build/candidate.pptx',finalPath:root+'/output/iTantra_SIH26173_submission.pptx',
 pythonExecutable:'C:/Users/avira/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',
 integrityValidatorPath:skill+'/container_tools/inspect_presentation_package_integrity.py',
 layoutValidatorPath:skill+'/container_tools/inspect_presentation_layout_geometry.py',
 layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-bullet-geometry','--validate-heading-fit'],
 explicitTotalSlideCount:6,requiredNativeTableOwnerSlides:[],requiredNativeChartOwnerSlides:[],
 fontPolicy:{basis:'reference',families:['Aptos','Arial','Garamond','Inter','Calibri','Times New Roman','TradeGothic'],referencePath:source,referenceSha256:sha},
 verifyArtifactToolImport:true,receiptPath:root+'/.build/validation.json'
});
console.log(JSON.stringify({finalPath:result.finalPath,slideCount:result.packageIntegrity.slide_count,bytes:result.byteCount,sha256:result.finalSha256}));
