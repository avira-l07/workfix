// Unit tests of pure JavaScript functions, without a browser, DOM, or rendered UI.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('itantra_light_ui/app.js', 'utf8');
const bucket = source.split('\n').find(line => line.startsWith('function bucket('));
const context = vm.createContext({ Date });
vm.runInContext(bucket, context);
const now = new Date(2026, 9, 15, 12);
const result = [0,1,6,7,10,40,400].map(days => {
  const date = new Date(2026,9,15-days,10).getTime();
  context.timestamp=date; context.fixedNow=now;
  return { daysAgo: days, bucket: vm.runInContext('bucket(timestamp,fixedNow)', context) };
});
assert.equal(result[0].bucket,'Today'); assert.equal(result[1].bucket,'Yesterday');
assert.equal(result[2].bucket,'This week'); assert.equal(result[3].bucket,'This month');
fs.writeFileSync('audit/2026-10-04/preview-pure-results.json', JSON.stringify({scope:'Pure date function only; browser/UI unverified',result},null,2));
console.log(JSON.stringify(result));
