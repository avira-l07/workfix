/* Load before styles so the saved appearance is present on the first frame. */
(() => {
  'use strict';
  const key = 'itantra-appearance-v2';
  const modes = ['light', 'dark', 'system'];
  const palettes = ['ocean', 'forest', 'iris', 'ember'];
  const system = matchMedia('(prefers-color-scheme: dark)');
  let saved;
  try { saved = JSON.parse(localStorage.getItem(key) || '{}'); } catch { saved = {}; }
  let preferences = {
    mode: modes.includes(saved?.mode) ? saved.mode : 'light',
    palette: palettes.includes(saved?.palette) ? saved.palette : 'ocean'
  };
  let transitionTimer;
  function apply(animate = false) {
    const root = document.documentElement;
    if (animate && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
      root.classList.add('theme-transition');
      clearTimeout(transitionTimer);
      transitionTimer = setTimeout(() => root.classList.remove('theme-transition'), 320);
    }
    root.dataset.theme = preferences.mode === 'system' ? (system.matches ? 'dark' : 'light') : preferences.mode;
    root.dataset.palette = preferences.palette;
    root.dataset.mode = preferences.mode;
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = root.dataset.theme === 'dark' ? '#101722' : '#f4f7fb';
    window.dispatchEvent(new CustomEvent('appearancechange', {detail: {...preferences}}));
  }
  window.itantraAppearance = {
    get: () => ({...preferences}),
    set(update) {
      if (modes.includes(update.mode)) preferences.mode = update.mode;
      if (palettes.includes(update.palette)) preferences.palette = update.palette;
      try { localStorage.setItem(key, JSON.stringify(preferences)); } catch { /* Still works when storage is blocked. */ }
      apply(true);
    }
  };
  system.addEventListener('change', () => { if (preferences.mode === 'system') apply(true); });
  apply();
})();
