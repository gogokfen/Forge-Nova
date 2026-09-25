// @ts-check
// Display preferences that live in the browser profile (the engine's own preferences, such as
// audio, are saved by the host in Forge's preference file).

const KEY = 'nova-settings-v1';

function load() {
  try {
    const s = JSON.parse(localStorage.getItem(KEY) || 'null');
    if (s && typeof s === 'object') return s;
    // earlier builds stored only the side panel flag
    return localStorage.getItem('nova-side') === '0' ? { side: false } : {};
  } catch {
    return {}; // storage unavailable
  }
}

export const settings = {
  /** 'rows': opponents stacked above you · 'table': everyone seated around a 2×2 table */
  layout: 'rows',
  /** side panel with card details and the game log */
  side: true,
  /** frame-time overlay */
  perf: false,
  /** hand kept in auto-sort order; while on, cards can't be rearranged by dragging */
  handSort: false,
  /** auto-sort rules, see board/handsort.js (null: the default order) */
  handSortRules: null,
  ...load(),
};

/** @type {Set<(key:string, value:any) => void>} */
const listeners = new Set();

export function onSetting(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function setSetting(key, value) {
  if (settings[key] === value) return;
  settings[key] = value;
  try { localStorage.setItem(KEY, JSON.stringify(settings)); } catch { /* storage unavailable */ }
  listeners.forEach((fn) => { try { fn(key, value); } catch (e) { console.error(e); } });
}
