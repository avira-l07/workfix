import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';

const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(root, 'outputs/01a0e889-e467-7eb0-83ff-c46d04dccb56');
await fs.mkdir(out, { recursive: true });
const pairs = JSON.parse(await fs.readFile(path.join(root, 'draft-pairs.json'), 'utf8'));
const cases = [];
for (const [category, rows] of Object.entries(pairs)) {
  assert.equal(rows.length, 12, category);
  rows.forEach(([hi, gu, notes], index) => {
    const group = `${category}-${String(index + 1).padStart(2, '0')}`;
    const split = index < 2 ? 'Development' : 'Evaluation';
    for (const [source, target, text, reference] of [['hi', 'gu', hi, gu], ['gu', 'hi', gu, hi]]) {
      assert(text.trim() && reference.trim());
      cases.push({ id: `${source}-${target}-${group}`, group, split, category,
        source, target, text: text.normalize('NFC'), reference: reference.normalize('NFC'),
        reference_origin: 'AI draft; requires bilingual human review',
        review_status: 'Needs review', reviewer: '', notes });
    }
  });
}
assert.equal(cases.length, 240);
assert.equal(new Set(cases.map(c => c.id)).size, 240);
for (const source of ['hi', 'gu']) {
  const direction = cases.filter(c => c.source === source);
  assert.equal(direction.filter(c => c.split === 'Development').length, 20);
  assert.equal(direction.filter(c => c.split === 'Evaluation').length, 100);
  assert.equal(new Set(direction.map(c => c.text)).size, 120);
}
assert(cases.every(c => cases.filter(other => other.group === c.group).every(other => other.split === c.split)));

const wb = Workbook.create();
const guide = wb.worksheets.add('Read first');
const instructions = [
  ['Hindi and Gujarati translation evaluation', '6 October 2026'],
  ['Current task', 'Step 1: review and freeze the reference set. Engine comparison has not started.'],
  ['Reference status', 'All source/reference pairs are AI drafts. None has been approved by a human.'],
  ['Each direction', '20 development cases and 100 reserved evaluation cases across 10 categories.'],
  ['What to edit', 'Check both D (source) and E (reference). Correct E, then set F to Approved and enter your name in G.'],
  ['Unclear input', 'Choose Ambiguous and explain the possible meanings in H. Never invent certainty from an incomplete utterance.'],
  ['Unsuitable input', 'Choose Exclude and explain why in H. Replace excluded evaluation cases before freezing the set.'],
  ['Equivalent wording', 'Approve natural equivalents. Preserve meaning, names, numbers, units, negation, time and directions.'],
  ['First review batch', 'Begin with the first 10 cases in Hindi to Gujarati, then the first 10 in Gujarati to Hindi. Continue in batches.'],
  ['Evaluation split', 'Do not use reserved evaluation cases for phrase rules, prompt examples, training or iterative tuning.'],
  ['Reverse directions', 'Both directions of the same pair have the same split. Review each direction independently.'],
  ['Source/reference changes', 'Keep case IDs and splits. Record meaningful edits in H so changes can be tracked.'],
  ['After review', 'Return the edited workbook. Only approved cases will enter scoring after the reference set is frozen.'],
  ['Phone evaluation', 'Later: compare actual outputs on identical inputs, review failures, measure memory and latency.'],
  ['Scoring', 'Later: report chrF or chrF++ with metric settings, plus separate name/number/negation/direction error counts.'],
  ['No current score', 'No engine output, accuracy percentage or human-approved reference is reported in this workbook.'],
  ['Generalization limit', 'Reserved means unused in our local tuning. Possible overlap with pretrained-model data is unknown.'],
  ['Synthetic examples', 'Names, phone numbers, codes and vehicle identifiers are illustrative test content.'],
  ['Reference origin', 'Authored AI draft pairs for iTantra, not copied from a published human-translated benchmark.'],
  ['IN22-Conv context', 'https://huggingface.co/datasets/ai4bharat/IN22-Conv'],
  ['IN22-Conv access', 'This published conversational benchmark requires account acceptance; its data was not downloaded or reproduced.'],
  ['Metric reference', 'https://github.com/mjpost/sacrebleu'],
];
guide.getRange(`A1:B${instructions.length}`).values = instructions;
guide.getRange(`A1:B${instructions.length}`).format.font = {name:'Arial', size:11, color:'#1F2937'};
guide.getRange(`A1:B${instructions.length}`).format.wrapText = true;
guide.getRange(`A1:B${instructions.length}`).format.rowHeightPx = 50;
guide.getRange('A1').format.font = {name:'Arial',size:14,bold:true};
guide.getRange('A:A').format.columnWidthPx = 330;
guide.getRange('B:B').format.columnWidthPx = 820;
guide.showGridLines = false;

for (const [source, name] of [['hi', 'Hindi to Gujarati'], ['gu', 'Gujarati to Hindi']]) {
  const sheet = wb.worksheets.add(name);
  sheet.getRange('A1').values = [[name]];
  sheet.getRange('D2').values = [['All references are AI drafts awaiting your bilingual review.']];
  sheet.getRange('D3').values = [['20 development cases + 100 evaluation cases. Editable columns D–H.']];
  const headers = ['Case ID', 'Split', 'Category', 'Source text', 'Reference — edit here', 'Review status', 'Reviewer', 'Notes'];
  const records = cases.filter(c => c.source === source).map(c => [c.id, c.split, c.category.replaceAll('_',' '), c.text, c.reference, c.review_status, c.reviewer, c.notes]);
  sheet.getRange('A6:H6').values = [headers];
  sheet.getRange('A7:H126').values = records;
  sheet.getRange('A1:H126').format.font = {name:'Arial', size:11, color:'#1F2937'};
  sheet.getRange('D7:E126').format.font = {name:'Arial', size:12, color:'#1F2937'};
  sheet.getRange('A1').format.font = {name:'Arial', size:14, bold:true};
  sheet.getRange('D2:D3').format.font = {name:'Arial',size:11,italic:true,color:'#475569'};
  sheet.getRange('A6:H126').format.wrapText = true;
  sheet.getRange('A7:H126').format.rowHeightPx = 78;
  sheet.getRange('A7:H126').format.verticalAlignment = 'top';
  sheet.getRange('A6:H6').format = {fill:'#334155',font:{name:'Arial',size:11,bold:true,color:'#FFFFFF'},rowHeightPx:42,wrapText:true};
  sheet.getRange('D7:H126').format.fill = '#FFF9E6';
  const widths = [300,115,160,420,420,125,120,330];
  widths.forEach((width,index) => {sheet.getRange(`${String.fromCharCode(65+index)}:${String.fromCharCode(65+index)}`).format.columnWidthPx = width;});
  sheet.getRange('F7:F126').dataValidation = {rule:{type:'list',values:['Needs review','Approved','Ambiguous','Exclude']}};
  sheet.tables.add('A6:H126',true,source==='hi'?'HindiGujaratiCases':'GujaratiHindiCases');
  sheet.freezePanes.freezeRows(6);
  sheet.freezePanes.freezeColumns(1);
  sheet.showGridLines = false;
  const values = sheet.getRange('D7:F126').values;
  assert.deepEqual(values, records.map(r => r.slice(3,6)));
  console.log((await wb.inspect({kind:'table',range:`'${name}'!D6:H9`,tableMaxRows:4,tableMaxCols:5,maxChars:1800})).ndjson);
}
const scan = await wb.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!',options:{useRegex:true,maxResults:20},maxChars:1000});
console.log(scan.ndjson);
for (const name of ['Read first','Hindi to Gujarati','Gujarati to Hindi']) {
  const image = await wb.render({sheetName:name,range:name==='Read first'?'A1:B9':'C6:F10',scale:1.5,format:'png'});
  await fs.writeFile(path.join(root,`${name.replaceAll(' ','-')}-preview.png`),new Uint8Array(await image.arrayBuffer()));
}
const file = await SpreadsheetFile.exportXlsx(wb);
const filename = path.join(out,'iTantra-Hindi-Gujarati-reference-review.xlsx');
await file.save(filename);
const data = {version:1,as_of:'2026-10-06',reference_status:'AI draft; no human approvals',
  human_approved_cases:0,engine_comparison:'Not run',app_integration:'None',cases};
await fs.writeFile(path.join(root,'draft-cases.json'),JSON.stringify(data,null,2)+'\n');
const hash = crypto.createHash('sha256').update(await fs.readFile(filename)).digest('hex');
await fs.writeFile(path.join(root,'verification.json'),JSON.stringify({workbook:filename,sha256:hash,
  directional_cases:240,development_per_direction:20,evaluation_per_direction:100,
  categories:10,duplicate_source_cases:0,group_split_overlap:0,human_approved_cases:0,
  engine_comparison:'Not run',app_code_changed_in_step1:false},null,2)+'\n');
console.log(filename);
