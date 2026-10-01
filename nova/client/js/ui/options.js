// @ts-check
// The options menu (Esc or ☰): board layout, display, audio, conceding and leaving the match.

import { store, localPlayer, zoneCards, TF } from '../store.js';
import { api, send, on, isServedGuest, guestRoom } from '../net.js';
import { el, esc, manaHtml, toast } from './text.js';
import { modal, closeTopWindow } from './dialogs.js';
import { settings, setSetting, onSetting } from './settings.js';
import { audioPrefs, applyAudioPrefs, saveGuestAudioPrefs } from './audio.js';
import { leaveAsGuest } from './online.js';
import { HAND_TYPES, HAND_RULES, handSortRules, handTypeOf, sortHand } from '../board/handsort.js';
import { KEY_ACTIONS, MAX_KEYS, keysOf, keyLabel, keyHint, comboOf, bindKey, unbindKey, resetKeys, setListeningForKey } from './keys.js';

/** @type {{close: () => void} | null} */
let current = null;

/** Forge preferences of this computer's host that the menu shows (from its hello message) */
const hostPrefs = { devMode: false };
on('hello', (m) => { if (m.devMode !== undefined) hostPrefs.devMode = !!m.devMode; });

export function isOptionsOpen() { return current !== null; }
export function closeOptions() { current?.close(); }

const LAYOUTS = [
  ['rows', 'Rows', 'Opponents stacked above you',
    '<rect x="1" y="1" width="32" height="5" rx="1.2"/><rect x="1" y="7.5" width="32" height="5" rx="1.2"/><rect class="me" x="1" y="14" width="32" height="9" rx="1.2"/>'],
  ['table', 'Table', 'Everyone around the table (2×2)',
    '<rect x="1" y="1" width="15.5" height="10.5" rx="1.2"/><rect x="17.5" y="1" width="15.5" height="10.5" rx="1.2"/><rect class="me" x="1" y="12.5" width="15.5" height="10.5" rx="1.2"/><rect x="17.5" y="12.5" width="15.5" height="10.5" rx="1.2"/>'],
];

function button(label, cls, fn) {
  const b = el(`<button class="btn ${cls}">${esc(label)}</button>`);
  b.addEventListener('click', fn);
  return b;
}

function toggle(on, onChange) {
  const sw = el(`<div class="switch ${on ? 'on' : ''}" role="switch" tabindex="0"></div>`);
  const flip = () => {
    const v = !sw.classList.contains('on');
    sw.classList.toggle('on', v);
    onChange(v);
  };
  sw.addEventListener('click', flip);
  sw.addEventListener('keydown', (e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); flip(); } });
  return sw;
}

// Audio settings are Forge preferences (shared with classic Forge); saved shortly after the last change.
let saveTimer = 0;
function saveAudio() {
  clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    if (isServedGuest()) { saveGuestAudioPrefs(); return; } // no Forge of our own: this browser keeps them
    api('prefs', { ...audioPrefs }).catch((e) => toast('Could not save audio settings: ' + e.message, 'error'));
  }, 400);
}

/** Resolves true when confirmed. */
function confirmBox(title, message, okLabel) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => {
      if (done) return;
      done = true;
      d.close();
      resolve(v);
    };
    const d = modal(title, { narrow: true, peek: false, onEsc: () => finish(false) });
    d.body.appendChild(el(`<div style="white-space:pre-wrap">${esc(message)}</div>`));
    const cancel = button('Cancel', '', () => finish(false));
    d.foot.append(cancel, button(okLabel, 'danger', () => finish(true)));
    setTimeout(() => cancel.focus(), 30);
  });
}

export function openOptions() {
  if (current) return;
  const dlg = modal('Options', { peek: false, onEsc: () => close() });
  dlg.modal.classList.add('options');
  /** @type {(() => void)[]} */
  const cleanup = [];
  const close = () => {
    cleanup.forEach((fn) => fn());
    dlg.close();
    current = null;
  };
  current = { close };

  const section = (title) => {
    const s = el(`<div class="opt-sec"><h4>${esc(title)}</h4></div>`);
    dlg.body.appendChild(s);
    return s;
  };
  const row = (sec, label, control, hint = '') => {
    const r = el(`<div class="opt-row"><div><div class="lbl">${esc(label)}</div>${hint ? `<div class="hint">${esc(hint)}</div>` : ''}</div></div>`);
    r.appendChild(control);
    sec.appendChild(r);
  };

  // ---- board
  const board = section('Board');
  const pick = el(`<div class="layout-pick">${LAYOUTS.map(([id, name, desc, svg]) =>
    `<button data-mode="${id}" class="${settings.layout === id ? 'sel' : ''}"><svg viewBox="0 0 34 24" aria-hidden="true">${svg}</svg><span><b>${name}</b><small>${desc}</small></span></button>`).join('')}</div>`);
  pick.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => {
    setSetting('layout', /** @type {HTMLElement} */ (b).dataset.mode);
    pick.querySelectorAll('[data-mode]').forEach((x) => x.classList.toggle('sel', x === b));
  }));
  board.appendChild(pick);
  if (store.inMatch) {
    row(board, 'Side panel', toggle(settings.side, (v) => setSetting('side', v)), 'Card details and game log' + keyHint('side').replace(/^ \((.*)\)$/, ' · $1'));
    row(board, 'Performance stats', toggle(settings.perf, (v) => setSetting('perf', v)));
  }
  const fs = toggle(!!document.fullscreenElement, (v) => {
    const p = v ? document.documentElement.requestFullscreen() : document.exitFullscreen();
    p.catch(() => fs.classList.toggle('on', !!document.fullscreenElement));
  });
  const onFullscreen = () => fs.classList.toggle('on', !!document.fullscreenElement);
  document.addEventListener('fullscreenchange', onFullscreen);
  cleanup.push(() => document.removeEventListener('fullscreenchange', onFullscreen));
  row(board, 'Fullscreen', fs, 'F11 works too');

  // ---- hand
  const hand = section('Hand');
  const sortSwitch = toggle(settings.handSort, (v) => setSetting('handSort', v));
  const sortCtl = el('<div class="opt-ctl"></div>');
  sortCtl.append(button('Customize…', 'small', () => cleanup.push(openHandSort().close)), sortSwitch);
  row(hand, 'Auto-sort', sortCtl, 'Keeps your hand in the order you choose. While on, you can\'t drag cards to rearrange them.');
  // the Customize window has a switch of its own
  cleanup.push(onSetting((key, v) => { if (key === 'handSort') sortSwitch.classList.toggle('on', !!v); }));

  // ---- gameplay
  const play = section('Gameplay');
  row(play, 'Highlight playable cards', toggle(settings.playable, (v) => setSetting('playable', v)),
    'When you get priority, the cards you can cast or play with the mana you have glow (lands only while you may still play one)');
  row(play, 'Dice animation', toggle(settings.diceAnim !== false, (v) => setSetting('diceAnim', v)),
    'Die rolls show the right die (d4 to d20, the planar die) tumbling onto the table. Off: just the result.');
  if (!guestRoom()) {
    row(play, 'Developer mode', toggle(hostPrefs.devMode, (v) => {
      hostPrefs.devMode = v;
      api('prefs', { devMode: v }).catch((e) => toast('Could not save: ' + e.message, 'error'));
    }), 'Forge\'s Dev tab beside the game log in games against the AI: add cards, set life, generate mana… (a Forge setting, shared with classic Forge)');
  }

  // ---- audio (applies while dragging)
  const audio = section('Audio');
  const volume = (label, onKey, volKey) => {
    const range = /** @type {HTMLInputElement} */ (el(`<input type="range" min="0" max="100" step="1" value="${audioPrefs[volKey]}">`));
    const val = el(`<span class="val">${audioPrefs[volKey]}%</span>`);
    const sw = toggle(audioPrefs[onKey], (v) => {
      applyAudioPrefs({ [onKey]: v });
      range.disabled = !v;
      saveAudio();
    });
    range.disabled = !audioPrefs[onKey];
    range.addEventListener('input', () => {
      const v = Number(range.value);
      val.textContent = v + '%';
      applyAudioPrefs({ [volKey]: v });
      saveAudio();
    });
    const ctl = el('<div class="vol"></div>');
    ctl.append(sw, range, val);
    row(audio, label, ctl);
  };
  volume('Sound effects', 'sounds', 'volSounds');
  volume('Music', 'music', 'volMusic');

  // ---- Discord (this computer's Nova talks to the Discord app; not in a browser invite)
  if (!isServedGuest()) discordSection(section('Discord'), row, cleanup);

  // ---- actions
  dlg.foot.append(button('Keyboard shortcuts', 'ghost', showShortcuts), el('<span class="count"></span>'));
  if (store.inMatch) {
    const me = localPlayer();
    const gameOn = !store.spectator && !!me && !me.lost && !store.game.over;
    const guest = !!guestRoom();
    if (gameOn) {
      dlg.foot.append(button('Concede game', '', async () => {
        const text = store.online
          ? 'You lose this game; the others play on and you can keep watching.'
          : 'You lose this game. From the game-over screen you can play the next game, start a rematch or leave.';
        if (!await confirmBox('Concede this game?', text, 'Concede')) return;
        close();
        send({ t: 'concede' });
      }));
    }
    if (store.online && guest) {
      dlg.foot.append(button('Leave the room', 'danger', async () => {
        if (!await confirmBox('Leave the room?', gameOn ? "You concede this game and leave your friends' room." : "You leave your friends' room.", 'Leave')) return;
        close();
        leaveAsGuest();
      }));
    } else if (store.online) {
      dlg.foot.append(button('End the match', 'danger', async () => {
        if (!await confirmBox('End the match for everyone?', 'The game stops for all players and everyone returns to the room.', 'End match')) return;
        close();
        send({ t: 'leave' });
      }));
    } else {
      dlg.foot.append(button(store.spectator ? 'Stop watching' : 'Back to main menu', 'danger', async () => {
        if (gameOn && !await confirmBox('Back to the main menu?', 'This concedes the current game and ends the match.', 'Leave match')) return;
        close();
        send({ t: 'leave' });
      }));
    }
  }
  dlg.foot.append(button(store.inMatch ? 'Resume' : 'Close', 'primary', close));
}

// ---------------------------------------------------------------- Discord Rich Presence

const DISCORD_STATUS = {
  off: 'Off.',
  'no-id': 'Paste your Application ID to connect.',
  connecting: 'Connecting to Discord…',
  'no-discord': 'Discord isn\'t running here right now. Nova connects by itself when it starts.',
};

/** On/off, the application id Discord needs, whether your life total shows, and the connection state. */
function discordSection(sec, row, cleanup) {
  const sw = toggle(false, (v) => save({ enabled: v }));
  row(sec, 'Rich Presence', sw, 'Your Discord status shows what you play: the format, your deck and commander, your life and the turn');
  const idRow = el(`<div class="opt-row discord-id"><div><div class="lbl">Application ID</div>
    <div class="hint">Discord shows the status under an application of yours: create one (free) in Discord's Developer Portal, name it what your status should say (e.g. "Forge Nova") and paste its Application ID here.</div></div>
    <div class="opt-ctl"><input class="life-in wide-in" data-id placeholder="Application ID" spellcheck="false" autocomplete="off"><button class="btn small" data-portal title="Opens Discord's Developer Portal in your browser">Portal…</button></div></div>`);
  sec.appendChild(idRow);
  const lifeSw = toggle(true, (v) => save({ showLife: v }));
  row(sec, 'Show your life total', lifeSw);
  const status = el('<div class="hint discord-status"></div>');
  sec.appendChild(status);
  const input = /** @type {HTMLInputElement} */ (idRow.querySelector('[data-id]'));
  const show = (s) => {
    sw.classList.toggle('on', !!s.enabled);
    lifeSw.classList.toggle('on', s.showLife !== false);
    if (document.activeElement !== input) input.value = s.appId || '';
    const text = !s.enabled ? DISCORD_STATUS.off
      : s.status === 'connected' ? `Connected to Discord${s.user ? ' as ' + s.user : ''}: your status follows your games.`
        : s.status === 'error' ? `${s.error || 'Discord refused the connection'}. Check the Application ID.`
          : DISCORD_STATUS[s.status] || '';
    status.textContent = text;
    status.classList.toggle('ok', s.enabled && s.status === 'connected');
    status.classList.toggle('warn', s.enabled && s.status === 'error');
  };
  const load = () => api('discord').then(show).catch(() => {});
  const save = (body) => api('discord', body).then(show).catch((e) => { toast(e.message, 'error'); load(); });
  input.addEventListener('change', () => save({ appId: input.value.trim() }));
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') input.blur(); });
  idRow.querySelector('[data-portal]')?.addEventListener('click', () => api('discord/portal', {}).catch((e) => toast(e.message, 'error')));
  load();
  // the connection comes and goes with the Discord app
  const timer = window.setInterval(load, 3000);
  cleanup.push(() => clearInterval(timer));
}

// ---------------------------------------------------------------- hand auto-sort window

/** Shown in the preview outside a match (or with fewer than two cards in hand). */
const SAMPLE_HAND = [
  { n: 'Lightning Bolt', mc: '{R}', cmc: 1, col: 'R', tf: TF.INSTANT },
  { n: 'Forest', tf: TF.LAND | TF.BASIC },
  { n: 'Llanowar Elves', mc: '{G}', cmc: 1, col: 'G', tf: TF.CREATURE },
  { n: 'Sol Ring', mc: '{1}', cmc: 1, tf: TF.ARTIFACT },
  { n: 'Wrath of God', mc: '{2}{W}{W}', cmc: 4, col: 'W', tf: TF.SORCERY },
  { n: 'Rhystic Study', mc: '{2}{U}', cmc: 3, col: 'U', tf: TF.ENCH },
  { n: 'Baleful Strix', mc: '{U}{B}', cmc: 2, col: 'UB', tf: TF.ARTIFACT | TF.CREATURE },
  { n: 'Liliana of the Veil', mc: '{1}{B}{B}', cmc: 3, col: 'B', tf: TF.PW },
  { n: 'Command Tower', tf: TF.LAND },
];
const BAND = { W: '#f8f2d6', U: '#4aa6e8', B: '#9a8f93', R: '#e8634a', G: '#3fa35c' };

/** The Customize window of the hand auto-sort: on/off, the rules and the card type order, with a preview. */
function openHandSort() {
  const dlg = modal('Hand auto-sort', { peek: false, onEsc: () => dlg.close() });
  const on = el('<div class="opt-row hs-on"><div><div class="lbl">Auto-sort</div><div class="hint">Your hand follows this order while auto-sort is on.</div></div></div>');
  on.appendChild(toggle(settings.handSort, (v) => setSetting('handSort', v)));
  const editor = handSortEditor();
  dlg.body.append(on, editor.el);
  const reset = button('Reset to default', 'ghost', editor.reset);
  reset.title = 'Lands, creatures, other permanents, then spells; by mana value, then name';
  dlg.foot.append(reset, el('<span class="count"></span>'), button('Done', 'primary', () => dlg.close()));
  return dlg;
}

/** Rule priority (drag or ▲▼), each rule's direction, the left-to-right order of card types, and a preview. */
function handSortEditor() {
  const box = el('<div class="hs"></div>');
  const save = (rules) => { setSetting('handSortRules', rules); render(); };
  const render = () => {
    const { types, rules } = handSortRules(settings.handSortRules);
    const byType = rules.some((r) => r.k === 'type' && r.on);
    const mine = store.inMatch && !store.spectator ? zoneCards(localPlayer(), 'Hand') : [];
    const cards = mine.length > 1 ? mine : SAMPLE_HAND;
    box.innerHTML = `
      <div class="hs-h">Sort by<small>the top rule first; the rules below it only order cards that tie</small></div>
      <div class="hs-rules" data-sort="y">${rules.map((r, i) => {
        const R = HAND_RULES[r.k];
        return `<div class="hs-rule ${r.on ? '' : 'off'}" data-key="${r.k}">
          <span class="grip" title="Drag to change the priority">⠿</span><input type="checkbox" ${r.on ? 'checked' : ''} title="Use this rule">
          <span class="n">${i + 1}</span><span class="lbl">${esc(R.label)}</span>
          ${R.dirs ? `<button class="btn small dir" title="Reverse this rule">${esc(R.dirs[r.desc ? 1 : 0])}</button>` : '<span class="note">in the order below</span>'}
          <span class="sp"></span>
          <button class="btn ghost small" data-step="-1" title="Higher priority" ${i === 0 ? 'disabled' : ''}>▲</button>
          <button class="btn ghost small" data-step="1" title="Lower priority" ${i === rules.length - 1 ? 'disabled' : ''}>▼</button></div>`;
      }).join('')}</div>
      <div class="hs-h ${byType ? '' : 'off'}">Card types<small>left to right in your hand · drag to reorder</small><span class="sp"></span>
        <button class="btn ghost small" data-flip title="Reverse the order">⇄ Flip</button></div>
      <div class="hs-types ${byType ? '' : 'off'}" data-sort="x">${types.map((k) =>
        `<div class="hs-type" data-key="${k}" tabindex="0" title="Drag, or use the arrow keys">${esc(HAND_TYPES.find((t) => t.k === k)?.label || k)}</div>`).join('')}</div>
      <div class="hs-ends ${byType ? '' : 'off'}"><span>◀ Left</span><span>Right ▶</span></div>
      <div class="hs-h">${mine.length > 1 ? 'Your hand' : 'Example'}<small>as it will be sorted</small></div>
      <div class="hs-hand">${sortHand(cards, settings.handSortRules).map((c) => {
        const col = c.col || '';
        return `<div class="hs-card" style="--c:${col.length > 1 ? 'var(--gold)' : BAND[col] || '#8a8f98'}" title="${esc(c.n)}">
          <span class="mc">${manaHtml(c.mc || '')}</span><b>${esc(c.n)}</b><small>${esc(handTypeOf(c)?.one || '')}</small></div>`;
      }).join('')}</div>`;
  };
  /** the rule set as currently saved, plus the rule a click or change happened in */
  const at = (e) => {
    const r = handSortRules(settings.handSortRules);
    const key = /** @type {HTMLElement|null} */ (/** @type {HTMLElement} */ (e.target).closest('.hs-rule'))?.dataset.key;
    return { r, i: r.rules.findIndex((x) => x.k === key) };
  };
  box.addEventListener('click', (e) => {
    const t = /** @type {HTMLElement} */ (e.target);
    const { r, i } = at(e);
    const step = /** @type {HTMLElement|null} */ (t.closest('[data-step]'));
    if (t.closest('.dir') && i >= 0) {
      r.rules[i].desc = !r.rules[i].desc;
      save(r);
    } else if (step && i >= 0) {
      const j = i + Number(step.dataset.step);
      if (j < 0 || j >= r.rules.length) return;
      [r.rules[i], r.rules[j]] = [r.rules[j], r.rules[i]];
      save(r);
    } else if (t.closest('[data-flip]')) {
      r.types.reverse();
      save(r);
    }
  });
  box.addEventListener('change', (e) => {
    const { r, i } = at(e);
    if (i < 0) return;
    r.rules[i].on = /** @type {HTMLInputElement} */ (e.target).checked;
    save(r);
  });
  // the arrow keys move a focused card type
  box.addEventListener('keydown', (e) => {
    const tile = /** @type {HTMLElement|null} */ (/** @type {HTMLElement} */ (e.target).closest('.hs-type'));
    const d = e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0;
    if (!tile || !d) return;
    e.preventDefault();
    const r = handSortRules(settings.handSortRules);
    const k = tile.dataset.key || '';
    const i = r.types.indexOf(k), j = i + d;
    if (i < 0 || j < 0 || j >= r.types.length) return;
    [r.types[i], r.types[j]] = [r.types[j], r.types[i]];
    save(r);
    /** @type {HTMLElement|null} */ (box.querySelector(`.hs-type[data-key="${k}"]`))?.focus();
  });
  dragSort(box, (list, keys) => {
    const r = handSortRules(settings.handSortRules);
    if (list.classList.contains('hs-types')) r.types = keys;
    else r.rules = keys.map((k) => r.rules.find((x) => x.k === k)).filter((x) => !!x);
    save(r);
  });
  render();
  return { el: box, reset: () => save(null) };
}

/**
 * Drag-to-reorder for the [data-key] children of containers marked data-sort="x" (a row of equal-width items)
 * or "y" (a column). `onDrop(list, keys)` gets the new order.
 */
function dragSort(box, onDrop) {
  box.addEventListener('pointerdown', (e) => {
    const t = /** @type {HTMLElement} */ (e.target);
    const item = /** @type {HTMLElement|null} */ (t.closest('[data-key]'));
    const list = item?.parentElement;
    if (!item || !list?.dataset.sort || e.button !== 0 || t.closest('button, input')) return;
    const vertical = list.dataset.sort === 'y';
    const x0 = e.clientX, y0 = e.clientY;
    let dragging = false;
    const move = (/** @type {PointerEvent} */ ev) => {
      if (!dragging) {
        if (Math.hypot(ev.clientX - x0, ev.clientY - y0) < 5) return;
        dragging = true;
        item.classList.add('dragging');
      }
      // the item goes before the first other item whose middle is past the pointer
      const p = vertical ? ev.clientY : ev.clientX;
      const before = [...list.children].find((n) => {
        if (n === item) return false;
        const r = n.getBoundingClientRect();
        return p < (vertical ? r.top + r.height / 2 : r.left + r.width / 2);
      });
      if (before) { if (item.nextElementSibling !== before) list.insertBefore(item, before); }
      else if (list.lastElementChild !== item) list.appendChild(item);
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', up);
      if (!dragging) return;
      item.classList.remove('dragging');
      onDrop(list, [...list.children].map((n) => /** @type {HTMLElement} */ (n).dataset.key || ''));
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', up);
  });
}

/**
 * Keyboard shortcuts: click a key to change it, + to add a second one, × to remove one. A key does one thing:
 * giving it to another action takes it away from the first. Esc stays the options key.
 */
export function showShortcuts() {
  /** @type {{id: string, slot: number}|null} the key being changed, waiting for a key press */
  let capture = null;
  let note = '';
  const listen = (slot) => {
    capture = slot;
    setListeningForKey(!!slot);
  };
  const close = () => {
    window.removeEventListener('keydown', onKey, true);
    listen(null);
    d.close();
  };
  const d = modal('Keyboard shortcuts', { narrow: true, peek: false, onEsc: () => close() });
  d.modal.classList.add('keys-dlg');
  const render = () => {
    const rows = KEY_ACTIONS.map((a) => {
      const keys = keysOf(a.id);
      const chips = keys.map((k, i) => {
        const on = capture && capture.id === a.id && capture.slot === i;
        return `<span class="kchip ${on ? 'listen' : ''}" data-id="${a.id}" data-slot="${i}" title="Click, then press the new key">${on ? 'Press a key…' : esc(keyLabel(k))}`
          + `${on ? '' : `<button class="kx" data-del="${esc(k)}" data-id="${a.id}" title="Remove this key">×</button>`}</span>`;
      }).join('');
      const adding = capture && capture.id === a.id && capture.slot === keys.length;
      const add = keys.length < MAX_KEYS
        ? `<span class="kchip add ${adding ? 'listen' : ''}" data-id="${a.id}" data-slot="${keys.length}" title="Add another key">${adding ? 'Press a key…' : '+'}</span>` : '';
      return `<span>${esc(a.label)}</span><span class="kchips">${chips}${add}</span>`;
    }).join('');
    d.body.innerHTML = `<div class="settings-grid keys-grid">
      <span>Options menu, close a window</span><span class="kchips"><span class="kchip fixed" title="Esc can't be changed">Esc</span></span>
      ${rows}
      <span>Number window (choose X)</span><b>↑ ↓ · digits · End = Max</b>
      <div class="keys-h">Mouse</div>
      <span>Apply click to whole pile</span><b>Shift+Click</b>
      <span>Rearrange your hand</span><b>Drag a hand card</b>
      <span>Zone menu</span><b>Right-click a player</b>
      <span>Scroll the text of the card you point at</span><b>Mouse wheel</b>
      <span>Enlarge an opponent (Rows layout)</span><b>Mouse wheel over their area</b></div>
      <div class="keys-note">${esc(note || 'Click a key to change it. Esc cancels.')}</div>`;
  };
  const onKey = (/** @type {KeyboardEvent} */ e) => {
    if (!d.back.isConnected) { // the window was dropped with all others (the match ended)
      window.removeEventListener('keydown', onKey, true);
      listen(null);
      return;
    }
    if (!capture) return;
    e.preventDefault();
    e.stopImmediatePropagation(); // nothing else acts on this key press, not even Esc
    if (e.code === 'Escape') {
      listen(null);
      render();
      return;
    }
    const combo = comboOf(e);
    if (!combo) return; // a modifier alone: wait for the key it goes with
    const label = KEY_ACTIONS.find((a) => a.id === capture?.id)?.label || '';
    const movedFrom = bindKey(capture.id, capture.slot, combo);
    note = movedFrom ? `${keyLabel(combo)} now means "${label}" (it no longer does "${movedFrom}").` : `${label}: ${keysOf(capture.id).map(keyLabel).join(' · ')}`;
    listen(null);
    render();
  };
  window.addEventListener('keydown', onKey, true);
  d.body.addEventListener('click', (e) => {
    const t = /** @type {HTMLElement} */ (e.target);
    const del = /** @type {HTMLElement|null} */ (t.closest('[data-del]'));
    if (del) {
      unbindKey(del.dataset.id || '', del.dataset.del || '');
      note = '';
      listen(null);
      render();
      return;
    }
    const chip = /** @type {HTMLElement|null} */ (t.closest('.kchip[data-id]'));
    listen(chip ? { id: chip.dataset.id || '', slot: Number(chip.dataset.slot) } : null);
    render();
  });
  render();
  d.foot.append(button('Reset to defaults', 'ghost', () => { resetKeys(); note = 'Default keys restored.'; listen(null); render(); }),
    el('<span class="count"></span>'), button('Close', 'primary', () => close()));
}

function isTextField(t) {
  if (t.tagName === 'TEXTAREA' || t.tagName === 'SELECT') return true;
  return t.tagName === 'INPUT' && !/^(range|checkbox|radio|button|submit)$/i.test(/** @type {HTMLInputElement} */ (t).type);
}

/** Esc closes the topmost closable window (a zone you opened, a picker, this menu), otherwise opens the menu. */
export function installEscapeKey() {
  window.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || e.repeat) return;
    const menus = document.querySelectorAll('.popmenu');
    if (menus.length) {
      menus.forEach((m) => m.remove());
      return;
    }
    if (e.target instanceof HTMLElement && isTextField(e.target)) e.target.blur();
    if (!document.getElementById('boot')?.classList.contains('hidden')) return; // loading / reconnecting
    e.preventDefault();
    if (!closeTopWindow()) openOptions();
  });
}
