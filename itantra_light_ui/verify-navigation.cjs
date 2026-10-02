const { chromium } = require('C:/Users/avira/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const { pathToFileURL } = require('node:url');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const base = pathToFileURL(path.join(__dirname, 'index.html')).href;
  const screens = ['hub', 'connect', 'chat', 'notes', 'packs', 'settings', 'diagnostics', 'sos'];
  const handlers = new Set([...readFileSync(path.join(__dirname, 'app.js'), 'utf8').matchAll(/case'([^']+)':/g)].map(match => match[1]));

  await page.goto(base + '#hub');
  assert.equal(await page.locator('.bottom-nav a').count(), 5);
  assert.equal(await page.locator('.bottom-nav [data-action="more"]').count(), 0);
  for (const [label, screen] of [['Talk', 'hub'], ['Connect', 'connect'], ['Messages', 'chat'], ['Notes', 'notes'], ['Settings', 'settings']]) {
    await page.locator(`.bottom-nav a[href="#${screen}"]`).click();
    assert.equal(new URL(page.url()).hash, '#' + screen, `${label} route`);
    await page.waitForFunction(screen => document.querySelector(`.bottom-nav a[href="#${screen}"]`)?.getAttribute('aria-current') === 'page', screen);
    assert.equal(await page.locator(`.bottom-nav a[href="#${screen}"]`).getAttribute('aria-current'), 'page');
  }

  await page.locator('.bottom-nav a[href="#chat"]').click();
  await page.locator('.conversation-row').first().waitFor();
  assert.ok(await page.locator('.conversation-row').count() >= 2, 'Messages opens previous-device inbox');
  await page.locator('.conversation-row').first().click();
  assert.equal(await page.locator('.chat-shell').count(), 1, 'Conversation opens its detail');
  await page.locator('.bottom-nav a[href="#chat"]').click();
  assert.ok(await page.locator('.conversation-row').count() >= 2, 'Messages tab returns to inbox from detail');

  await page.locator('.bottom-nav a[href="#settings"]').click();
  await page.locator('.settings-shortcut[href="#packs"]').click();
  assert.equal(new URL(page.url()).hash, '#packs');
  await page.locator('.bottom-nav a[href="#settings"]').click();
  await page.locator('.settings-shortcut[href="#diagnostics"]').click();
  assert.equal(new URL(page.url()).hash, '#diagnostics');
  await page.locator('.topbar a[href="#sos"]').click();
  assert.equal(new URL(page.url()).hash, '#sos');
  await page.locator('.topbar a[href="#settings"]').click();
  assert.equal(new URL(page.url()).hash, '#settings');
  await page.locator('[data-action="copy-demo-id"]').click();
  await page.getByText(/Sample device ID (copied|:)/).waitFor();
  await page.screenshot({ path: path.join(__dirname, 'review', 'mobile-settings-navigation.png'), fullPage: true });

  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 });
    for (const screen of screens) {
      await page.goto(base + '#' + screen);
      const links = await page.locator('a[href^="#"]').evaluateAll(elements => elements.map(element => element.getAttribute('href').slice(1)));
      for (const route of links) assert.ok(screens.includes(route), `${screen}: unhandled #${route}`);
      const actions = await page.locator('[data-action]').evaluateAll(elements => elements.map(element => element.dataset.action));
      for (const action of actions) assert.ok(handlers.has(action), `${screen}: missing ${action} click handler`);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${screen}: horizontal overflow at ${width}`);
    }
  }
  await page.goto(base + '#settings');
  await page.locator('.topbar [data-action="appearance"]').click();
  assert.ok(await page.locator('dialog').isVisible());
  assert.ok(await page.locator('dialog').evaluate(element => element.getBoundingClientRect().bottom >= innerHeight - 1), 'Mobile sheet touches viewport bottom');
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('dialog').isVisible(), false);
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ mobileDestinations: 5, routes: screens.length, viewports: [320, 390], missingHandlers: 0, overflows: 0, errors }, null, 2));
  await browser.close();
})().catch(error => { console.error(error); process.exit(1); });
