const { chromium } = require('C:/Users/avira/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');

const output = path.join(__dirname, 'review', 'themes');
const url = pathToFileURL(path.join(__dirname, 'index.html')).href;
const storageKey = 'itantra-appearance-v2';
const screens = ['hub', 'connect', 'chat', 'notes', 'packs', 'settings', 'diagnostics', 'sos'];
const palettes = ['ocean', 'forest', 'iris', 'ember'];
const modes = ['light', 'dark'];
const viewports = [{ width: 390, height: 844 }, { width: 1280, height: 900 }];
fs.mkdirSync(output, { recursive: true });

function luminance(rgb) {
  const linear = rgb.slice(0, 3).map(value => {
    const channel = value / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
}

function contrast(foreground, background) {
  const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (values[0] + 0.05) / (values[1] + 0.05);
}

async function inspectColors(page) {
  return page.evaluate(() => {
    const names = ['--bg', '--surface', '--ink', '--muted', '--blue', '--on-accent', '--green', '--green-soft', '--red', '--red-soft', '--amber', '--amber-soft'];
    const probe = document.createElement('span');
    probe.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none;';
    document.body.append(probe);
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const root = getComputedStyle(document.documentElement);
    const result = {};
    for (const name of names) {
      const raw = root.getPropertyValue(name).trim();
      if (!raw) throw new Error(`Missing theme token ${name}`);
      probe.style.backgroundColor = `var(${name})`;
      const css = getComputedStyle(probe).backgroundColor;
      context.clearRect(0, 0, 1, 1);
      context.fillStyle = css;
      context.fillRect(0, 0, 1, 1);
      result[name] = { css, rgba: Array.from(context.getImageData(0, 0, 1, 1).data) };
    }
    probe.remove();
    return result;
  });
}

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: viewports[0], deviceScaleFactor: 1 });
  const report = { screens, viewports, palettes, modes, layoutsChecked: 0, overflows: [], errors: [], contrast: [], passed: [], output };
  page.on('pageerror', error => report.errors.push(error.message));

  async function settle() {
    // Allow the intentional theme transition to finish before sampling its colors.
    await page.waitForTimeout(400);
  }

  async function navigate(screen) {
    await page.evaluate(value => { location.hash = value; }, screen);
    await page.waitForFunction(value => location.hash === `#${value}`, screen);
    await settle();
  }

  async function openAppearance() {
    if (await page.locator('dialog[open]').count()) await page.keyboard.press('Escape');
    await page.locator('[data-action="appearance"]:visible').first().click();
    await page.locator('dialog[open]').waitFor();
  }

  async function expectTheme(mode, palette) {
    await page.waitForFunction(({ mode, palette }) =>
      document.documentElement.dataset.theme === mode && document.documentElement.dataset.palette === palette,
    { mode, palette });
  }

  async function chooseAppearance(mode, palette) {
    await openAppearance();
    await page.locator(`dialog[open] [data-action="theme-mode"][data-mode="${mode}"]`).click();
    await page.locator(`dialog[open] [data-action="theme-palette"][data-palette="${palette}"]`).click();
    await page.keyboard.press('Escape');
    await expectTheme(mode, palette);
    await settle();
  }

  async function checkOverflow(label) {
    const dimensions = await page.evaluate(() => ({
      viewport: innerWidth,
      document: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
      modal: document.querySelector('dialog[open]') ? {
        scroll: document.querySelector('dialog[open]').scrollWidth,
        client: document.querySelector('dialog[open]').clientWidth
      } : null
    }));
    if (dimensions.document > dimensions.viewport + 1 || dimensions.body > dimensions.viewport + 1 ||
        (dimensions.modal && dimensions.modal.scroll > dimensions.modal.client + 1)) {
      report.overflows.push({ label, ...dimensions });
    }
  }

  try {
    await page.emulateMedia({ colorScheme: 'light' });
    await page.goto(`${url}#hub`);
    await settle();
    const pairs = [
      ['body text', '--ink', '--bg'],
      ['muted text', '--muted', '--bg'],
      ['surface text', '--ink', '--surface'],
      ['muted surface text', '--muted', '--surface'],
      ['primary button', '--on-accent', '--blue'],
      ['success status', '--green', '--green-soft'],
      ['failure status', '--red', '--red-soft'],
      ['warning status', '--amber', '--amber-soft']
    ];

    for (const palette of palettes) {
      for (const mode of modes) {
        await chooseAppearance(mode, palette);
        const colors = await inspectColors(page);
        for (const [name, foreground, background] of pairs) {
          assert.equal(colors[foreground].rgba[3], 255, `${palette}/${mode}: ${foreground} must be opaque`);
          assert.equal(colors[background].rgba[3], 255, `${palette}/${mode}: ${background} must be opaque`);
          const ratio = contrast(colors[foreground].rgba, colors[background].rgba);
          report.contrast.push({ palette, mode, name, ratio: Number(ratio.toFixed(3)), foreground: colors[foreground].css, background: colors[background].css });
          assert.ok(ratio >= 4.5, `${palette}/${mode}: ${name} contrast is ${ratio.toFixed(2)}:1; expected at least 4.5:1`);
        }
        for (const viewport of viewports) {
          await page.setViewportSize(viewport);
          for (const screen of screens) {
            await navigate(screen);
            await expectTheme(mode, palette);
            await checkOverflow(`${palette}/${mode}/${viewport.width}/${screen}`);
            report.layoutsChecked += 1;
            if (screen === 'hub') {
              await page.screenshot({ path: path.join(output, `hub-${palette}-${mode}-${viewport.width}.png`), fullPage: true });
            }
          }
          await navigate('settings');
          await openAppearance();
          await settle();
          await checkOverflow(`${palette}/${mode}/${viewport.width}/appearance`);
          if (palette === 'ocean') {
            await page.screenshot({ path: path.join(output, `appearance-${mode}-${viewport.width}.png`) });
          }
          await page.keyboard.press('Escape');
        }
      }
    }
    assert.deepEqual(report.overflows, [], 'Horizontal overflow detected');
    report.passed.push('All four palettes in light/dark mode across eight screens at 390 and 1280 px; theme retained during navigation');
    report.passed.push('Body, muted, surface, primary button, and status-token contrast is at least 4.5:1');

    await chooseAppearance('dark', 'forest');
    const saved = await page.evaluate(key => JSON.parse(localStorage.getItem(key)), storageKey);
    assert.equal(saved.mode, 'dark');
    assert.equal(saved.palette, 'forest');
    await page.reload();
    await expectTheme('dark', 'forest');
    report.passed.push('Appearance preference persists across reload');

    await openAppearance();
    await page.locator('dialog[open] [data-action="theme-mode"][data-mode="system"]').click();
    await page.keyboard.press('Escape');
    await expectTheme('light', 'forest');
    await page.emulateMedia({ colorScheme: 'dark' });
    await expectTheme('dark', 'forest');
    await page.emulateMedia({ colorScheme: 'light' });
    await expectTheme('light', 'forest');
    assert.equal(await page.evaluate(key => JSON.parse(localStorage.getItem(key)).mode, storageKey), 'system');
    await page.reload();
    await expectTheme('light', 'forest');
    await page.emulateMedia({ colorScheme: 'dark' });
    await expectTheme('dark', 'forest');
    report.passed.push('System mode follows OS changes immediately and after reload');

    await chooseAppearance('light', 'ocean');
    await page.emulateMedia({ colorScheme: 'dark' });
    await expectTheme('light', 'ocean');
    await chooseAppearance('dark', 'ocean');
    await page.emulateMedia({ colorScheme: 'light' });
    await expectTheme('dark', 'ocean');
    report.passed.push('Explicit light/dark choices override the OS color scheme');

    await page.emulateMedia({ reducedMotion: 'reduce' });
    await navigate('hub');
    const motion = await page.evaluate(() => {
      const selectors = ['html', 'body', 'main', '.ptt', '.btn', '.card'];
      return selectors.flatMap(selector => Array.from(document.querySelectorAll(selector)).map(element => {
        const style = getComputedStyle(element);
        return { selector, animationName: style.animationName, animationDuration: style.animationDuration, transitionDuration: style.transitionDuration };
      }));
    });
    const seconds = value => value.split(',').map(part => part.trim()).map(part => part.endsWith('ms') ? parseFloat(part) / 1000 : parseFloat(part));
    for (const item of motion) {
      assert.ok(item.animationName === 'none' || seconds(item.animationDuration).every(value => value <= 0.01), `Reduced-motion animation: ${JSON.stringify(item)}`);
      assert.ok(seconds(item.transitionDuration).every(value => value <= 0.01), `Reduced-motion transition: ${JSON.stringify(item)}`);
    }
    report.passed.push('Reduced motion removes or minimizes animation and transitions');

    await page.emulateMedia({ reducedMotion: 'no-preference' });
    for (const width of [390, 820, 1280]) {
      // At desktop width the hub's two columns are short enough that its mic
      // remains in view at 600 px tall. Use a short window to exercise the dock.
      await page.setViewportSize({ width, height: width === 1280 ? 360 : 600 });
      await navigate('hub');
      await page.evaluate(() => scrollTo(0, 0));
      await settle();
      assert.equal(await page.locator('#talk-dock').evaluate(element => getComputedStyle(element).display), 'none', 'Hidden dock must not take up space or intercept clicks');
      await page.evaluate(() => scrollTo(0, document.documentElement.scrollHeight));
      await page.evaluate(() => {
        const micBottom = document.querySelector('.ptt-area').getBoundingClientRect().bottom;
        if (micBottom > 36) {
          const spacer = document.createElement('div');
          spacer.dataset.testScrollSpacer = 'true';
          spacer.style.height = `${Math.ceil(micBottom) + 80}px`;
          document.body.append(spacer);
          scrollTo(0, document.documentElement.scrollHeight);
        }
      });
      await page.locator('#talk-dock.visible').waitFor();
      await settle();
      const dock = await page.locator('#talk-dock').evaluate(element => {
        const box = element.getBoundingClientRect();
        return { x: box.x, right: box.right, width: box.width, y: box.y, bottom: box.bottom, viewportWidth: innerWidth, viewportHeight: innerHeight, position: getComputedStyle(element).position };
      });
      assert.equal(dock.position, 'fixed');
      assert.ok(dock.width <= 380.5, `Dock is too wide: ${JSON.stringify(dock)}`);
      assert.ok(dock.x >= 0 && dock.right <= dock.viewportWidth + 1, 'Dock must fit horizontally');
      assert.ok(dock.y >= 0 && dock.bottom <= dock.viewportHeight + 1, 'Dock must fit vertically');
      await checkOverflow(`dock/${width}`);
      await page.screenshot({ path: path.join(output, `dock-dark-${width}.png`) });
      await page.evaluate(() => document.querySelector('[data-test-scroll-spacer]')?.remove());
    }
    await navigate('notes');
    assert.equal(await page.locator('#talk-dock').evaluate(element => getComputedStyle(element).display), 'none', 'Dock must be hidden outside Talk');
    report.passed.push('Compact fixed talk dock stays within the viewport and is absent while hidden or on another screen');

    assert.deepEqual(report.overflows, [], 'Horizontal overflow detected');
    assert.deepEqual(report.errors, [], 'Browser runtime errors');
    report.passed.push('No browser runtime errors');
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  } catch (error) {
    report.failure = error.stack || String(error);
    await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {});
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(report, null, 2));
    throw error;
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
