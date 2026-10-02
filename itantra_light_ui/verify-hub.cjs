const { chromium } = require('C:/Users/avira/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href + '#hub');
  const geometry = await page.evaluate(() => {
    const box = selector => { const r = document.querySelector(selector)?.getBoundingClientRect(); return r ? { top: Math.round(r.top), bottom: Math.round(r.bottom), height: Math.round(r.height), width: Math.round(r.width) } : null; };
    return { head: box('.page-head'), connection: box('.connection-card'), talk: box('.talk-panel'), ptt: box('.talk-panel .ptt'), sos: box('.sos-trigger'), quick: box('.quick-grid'), history: box('.history-card'), messages: [...document.querySelectorAll('.history-card .message')].filter(element => getComputedStyle(element).display !== 'none').map(element => { const r = element.getBoundingClientRect(); return { top: Math.round(r.top), bottom: Math.round(r.bottom) }; }), nav: box('.bottom-nav') };
  });
  assert.ok(geometry.sos.height >= 44 && geometry.sos.width >= 70, 'SOS must be a large labelled control');
  assert.ok(geometry.messages.length >= 2 && geometry.messages[1].bottom < geometry.nav.top, 'Two recent messages must be visible above the phone navigation');
  assert.ok(geometry.ptt.bottom < geometry.nav.top, 'PTT button must remain visible above the phone navigation');
  await page.screenshot({ path: path.join(__dirname, 'review', 'hub-phone-viewport.png') });
  await page.locator('.talk-panel .language-swap').click();
  assert.match(await page.locator('.talk-panel [data-kind="mic"]').innerText(), /English/);
  assert.match(await page.locator('.talk-panel [data-kind="target"]').innerText(), /Hindi/);
  await page.locator('.talk-panel [data-kind="target"]').click();
  await page.locator('[data-action="choose-language"][data-code="auto"]').click();
  assert.ok(await page.locator('.talk-panel .language-swap').isDisabled(), 'Swap must be unavailable for Auto');
  await page.locator('.sos-trigger').click();
  assert.equal(new URL(page.url()).hash, '#sos');
  console.log(JSON.stringify(geometry, null, 2));
  await browser.close();
})().catch(error => { console.error(error); process.exit(1); });
