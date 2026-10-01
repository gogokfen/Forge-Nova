// @ts-check
// Keyboard shortcuts of the match: the actions, their default keys and the player's own bindings.
// Keys are stored by physical position (KeyboardEvent.code), so they work with any keyboard layout
// (a Hebrew or Russian layout still sends "KeyA" for the A key).

import { settings, setSetting } from './settings.js';

/** Actions that can be rebound; each has up to MAX_KEYS keys. Esc (options, closing windows) stays fixed. */
export const KEY_ACTIONS = [
  { id: 'ok', label: 'OK / pass priority / confirm a window', keys: ['Space', 'Enter'] },
  { id: 'cancel', label: 'Cancel / End turn', keys: ['Backspace'] },
  { id: 'passTurn', label: 'Pass until end of turn', keys: ['F2'] },
  { id: 'undo', label: 'Undo', keys: ['Ctrl+KeyZ'] },
  { id: 'attackAll', label: 'Alpha strike (attack with everything)', keys: ['KeyA'] },
  { id: 'side', label: 'Toggle side panel', keys: ['Tab'] },
];
export const MAX_KEYS = 2;

const MODIFIERS = new Set(['ControlLeft', 'ControlRight', 'ShiftLeft', 'ShiftRight', 'AltLeft', 'AltRight', 'MetaLeft', 'MetaRight', 'OSLeft', 'OSRight', 'CapsLock', 'Fn', 'FnLock']);

/** "Ctrl+Shift+KeyZ" for a key press; '' for a lone modifier or Esc (not bindable). */
export function comboOf(e) {
  let code = e.code || '';
  if (code === 'NumpadEnter') code = 'Enter';
  if (!code || MODIFIERS.has(code) || code === 'Escape') return '';
  return (e.ctrlKey ? 'Ctrl+' : '') + (e.altKey ? 'Alt+' : '') + (e.shiftKey ? 'Shift+' : '') + (e.metaKey ? 'Win+' : '') + code;
}

/** The keys of an action (the player's own, else the defaults). */
export function keysOf(id) {
  const own = settings.keys?.[id];
  if (Array.isArray(own)) return own;
  return KEY_ACTIONS.find((a) => a.id === id)?.keys || [];
}

/** The action a key press triggers, or null. */
export function actionFor(e) {
  const combo = comboOf(e);
  if (!combo) return null;
  for (const a of KEY_ACTIONS) if (keysOf(a.id).includes(combo)) return a.id;
  return null;
}

/**
 * Binds `combo` to an action (replacing its key at `slot`, or adding one). A key can do only one thing:
 * it is taken away from any other action. Returns the label of that other action, if any.
 */
export function bindKey(id, slot, combo) {
  const all = currentMap();
  let movedFrom = '';
  for (const a of KEY_ACTIONS) {
    if (a.id === id) continue;
    const i = all[a.id].indexOf(combo);
    if (i >= 0) {
      all[a.id].splice(i, 1);
      movedFrom = a.label;
    }
  }
  const mine = all[id].filter((k) => k !== combo);
  if (slot < mine.length) mine[slot] = combo;
  else mine.push(combo);
  all[id] = mine.slice(0, MAX_KEYS);
  setSetting('keys', all);
  return movedFrom;
}

export function unbindKey(id, combo) {
  const all = currentMap();
  all[id] = all[id].filter((k) => k !== combo);
  setSetting('keys', all);
}

export function resetKeys() {
  setSetting('keys', null);
}

function currentMap() {
  /** @type {Record<string, string[]>} */
  const m = {};
  for (const a of KEY_ACTIONS) m[a.id] = [...keysOf(a.id)];
  return m;
}

const NAMES = {
  Space: 'Space', Enter: 'Enter', Backspace: 'Backspace', Tab: 'Tab', Delete: 'Delete', Insert: 'Insert',
  Home: 'Home', End: 'End', PageUp: 'Page Up', PageDown: 'Page Down',
  ArrowLeft: '←', ArrowRight: '→', ArrowUp: '↑', ArrowDown: '↓',
  Backquote: '`', Minus: '-', Equal: '=', BracketLeft: '[', BracketRight: ']', Backslash: '\\',
  Semicolon: ';', Quote: "'", Comma: ',', Period: '.', Slash: '/', IntlBackslash: '\\',
  NumpadAdd: 'Num +', NumpadSubtract: 'Num -', NumpadMultiply: 'Num *', NumpadDivide: 'Num /', NumpadDecimal: 'Num .',
  ContextMenu: 'Menu', Pause: 'Pause', ScrollLock: 'Scroll Lock', PrintScreen: 'Print Screen',
};

/** "Ctrl+KeyZ" → "Ctrl+Z" */
export function keyLabel(combo) {
  const parts = combo.split('+');
  const code = parts.pop() || '';
  let name = NAMES[code];
  if (!name) {
    if (/^Key[A-Z]$/.test(code)) name = code.slice(3);
    else if (/^Digit\d$/.test(code)) name = code.slice(5);
    else if (/^Numpad\d$/.test(code)) name = 'Num ' + code.slice(6);
    else name = code;
  }
  return [...parts, name].join('+');
}

/** "Space · Enter" (or "none") */
export function keysLabel(id) {
  const k = keysOf(id);
  return k.length ? k.map(keyLabel).join(' · ') : 'none';
}

/** " (Tab)" for tooltips; '' when the action has no key */
export function keyHint(id) {
  const k = keysOf(id);
  return k.length ? ` (${keyLabel(k[0])})` : '';
}

// While the shortcut editor listens for a key, the match must not act on it.
let listening = false;
export function isListeningForKey() { return listening; }
export function setListeningForKey(v) { listening = v; }
