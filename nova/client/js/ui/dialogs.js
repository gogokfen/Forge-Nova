// @ts-check
// Modal dialogs for the engine's blocking questions (choose, order, confirm, assign damage...).

import { on, send, imgUrl } from '../net.js';
import { store } from '../store.js';
import { esc, el, manaHtml } from './text.js';
import { faceCanvas } from '../gl/cardface.js';
import { settings } from './settings.js';
import { rollDice, kindFor } from './dice.js';

/**
 * @typedef {{back: HTMLElement, modal: HTMLElement, body: HTMLElement, foot: HTMLElement, at: number,
 *   setPeek: (on: boolean) => void, keys: ((e: KeyboardEvent) => boolean) | null, close: () => void}} Modal
 * `at`: when it appeared (performance.now()); `keys`: the window's own keys (true when it used the key press).
 */

/** @type {Map<number, Modal>} the engine's open questions, in the order they appeared */
const open = new Map();
const root = () => /** @type {HTMLElement} */ (document.getElementById('dialogs'));

/** A card image element with procedural fallback. */
export function cardFaceEl(card, cls = 'face') {
  const d = el(`<div class="${cls}"></div>`);
  const hidden = !card || card.hid && !card.img;
  if (hidden) {
    const owner = card && store.players.get(card.c ?? card.o);
    d.style.backgroundImage = `url(/sleeve/${owner?.sl ?? 0}.png)`;
    return d;
  }
  if (card.img) {
    const url = imgUrl(card.img);
    const probe = new Image();
    probe.onload = () => { d.style.backgroundImage = `url("${url}")`; };
    probe.onerror = () => { if (!d.firstChild) d.appendChild(faceCanvas(card)); };
    probe.src = url;
    // show the procedural face until the real image is there
    d.appendChild(faceCanvas(card));
    probe.addEventListener('load', () => d.replaceChildren());
  } else {
    d.appendChild(faceCanvas(card));
  }
  return d;
}

function reply(id, v) {
  if (!open.has(id)) return; // answered already (a second click or key press)
  send({ t: 'reply', id, v });
  close(id);
}

function close(id) {
  const d = open.get(id);
  if (d) {
    d.close();
    open.delete(id);
  }
}

/** Windows that Esc may close, most recent last (the engine's questions are not among them). */
/** @type {{back: HTMLElement, onEsc: () => void}[]} */
const escStack = [];

/**
 * Generic modal shell. With `onEsc`, the Esc key calls it while this is the topmost such window.
 * @param {string} title
 * @param {{wide?: boolean, narrow?: boolean, peek?: boolean, onEsc?: () => void}} [opts]
 * @returns {Modal}
 */
export function modal(title, { wide = false, narrow = false, peek = true, onEsc } = {}) {
  const back = el(`<div class="modal-back"><div class="modal ${wide ? 'wide' : ''} ${narrow ? 'narrow' : ''}">
    <div class="mhead"><div class="title">${manaHtml(title || '')}</div>
      ${peek ? '<button class="btn ghost small" data-peek title="Hide this dialog to look at the board">Peek</button>' : ''}</div>
    <div class="mbody"></div><div class="mfoot"></div></div></div>`);
  root().appendChild(back);
  const m = /** @type {HTMLElement} */ (back.querySelector('.modal'));
  const peekBtn = back.querySelector('[data-peek]');
  const setPeek = (/** @type {boolean} */ on) => {
    back.classList.toggle('peek', on);
    if (peekBtn) peekBtn.textContent = on ? 'Show' : 'Peek';
  };
  peekBtn?.addEventListener('click', () => setPeek(!back.classList.contains('peek')));
  const entry = onEsc ? { back, onEsc } : null;
  if (entry) escStack.push(entry);
  return {
    back, modal: m,
    body: /** @type {HTMLElement} */ (m.querySelector('.mbody')),
    foot: /** @type {HTMLElement} */ (m.querySelector('.mfoot')),
    at: performance.now(),
    setPeek,
    keys: null,
    close: () => {
      back.remove();
      const i = entry ? escStack.indexOf(entry) : -1;
      if (i >= 0) escStack.splice(i, 1);
    },
  };
}

/** Esc: closes the topmost closable window; false when there is none. */
export function closeTopWindow() {
  while (escStack.length) {
    const top = /** @type {{back: HTMLElement, onEsc: () => void}} */ (escStack.pop());
    if (top.back.isConnected) {
      top.onEsc();
      return true;
    }
  }
  return false;
}

/** Leaving a match: drops every window, including ones that were waiting for an answer. */
export function closeAllWindows() {
  closeAllDialogs();
  escStack.length = 0;
  root().replaceChildren();
}

function button(label, cls, fn) {
  const b = el(`<button class="btn ${cls}">${esc(label)}</button>`);
  b.addEventListener('click', fn);
  return b;
}

// ---------------------------------------------------------------- choose

function showChoose(m) {
  const { id, items, min, max } = m;
  const reveal = min < 0;
  const single = max === 1 || reveal;
  const hasCards = items.some((it) => it.card);
  const selected = new Set(m.selected || []);
  const dlg = modal(m.title || (reveal ? 'Revealed' : 'Choose'), { wide: hasCards && items.length > 4 });
  open.set(id, dlg);
  const ok = button(reveal ? 'OK' : 'OK', 'primary', () => reply(id, [...selected].sort((a, b) => a - b)));
  const count = el('<span class="count"></span>');
  const refresh = () => {
    const n = selected.size;
    ok.disabled = !reveal && (n < Math.max(0, min) || (max > 0 && n > max));
    count.textContent = reveal ? '' : max > 1 || max < 0 ? `${n} selected${min > 0 ? ` (min ${min}${max > 0 ? `, max ${max}` : ''})` : max > 0 ? ` (max ${max})` : ''}` : '';
    dlg.body.querySelectorAll('[data-i]').forEach((node) => {
      node.classList.toggle('sel', selected.has(Number(/** @type {HTMLElement} */ (node).dataset.i)));
    });
  };
  const toggle = (i) => {
    if (reveal) return;
    if (single) {
      if (selected.has(i) && min === 0) selected.clear();
      else { selected.clear(); selected.add(i); }
    } else if (selected.has(i)) selected.delete(i);
    else if (max < 0 || selected.size < max) selected.add(i);
    refresh();
  };
  let search = null;
  // a very long list (dev mode's "name the card" offers every card Forge knows): only the best matches of
  // the search are shown
  const big = items.length > 400;
  const SHOW = 150;
  if (items.length > 20) {
    search = /** @type {HTMLInputElement} */ (el(`<input class="search" placeholder="${big ? `Type to search ${items.length.toLocaleString()} choices… (Enter picks the first)` : 'Search…'}">`));
    dlg.body.appendChild(search);
  }
  const list = el(`<div class="${hasCards ? 'choice-cards' : 'choice-list'}"></div>`);
  /** @type {Map<number, HTMLElement>} */
  const nodes = new Map();
  const nodeFor = (i) => {
    let node = nodes.get(i);
    if (node) return node;
    const it = items[i];
    if (it.card) {
      node = el(`<div class="ccard" data-i="${i}"></div>`);
      node.appendChild(cardFaceEl(it.card));
      node.appendChild(el(`<div class="lbl" title="${esc(it.label)}">${esc(it.label)}</div>`));
      node.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: it.card })));
    } else {
      node = el(`<div class="choice" data-i="${i}">${manaHtml(it.label)}</div>`);
    }
    node.addEventListener('click', () => toggle(i));
    node.addEventListener('dblclick', () => {
      if (reveal) return;
      if (single) { selected.clear(); selected.add(i); refresh(); if (!ok.disabled) ok.click(); }
    });
    node.classList.toggle('sel', selected.has(i));
    nodes.set(i, node);
    return node;
  };
  const labels = items.map((it) => String(it.label || '').toLowerCase());
  const more = el('<div class="count choice-more"></div>');
  /** big lists: the matches of the search, exact names first, then names that start with it */
  let hits = /** @type {number[]} */ ([]);
  const showMatches = (q) => {
    const found = [];
    for (let i = 0; i < items.length && found.length < 5000; i++) if (!q || labels[i].includes(q)) found.push(i);
    if (q) {
      const rank = (i) => (labels[i] === q ? 0 : labels[i].startsWith(q) ? 1 : 2);
      found.sort((a, b) => rank(a) - rank(b) || labels[a].length - labels[b].length);
    }
    hits = found;
    list.replaceChildren(...found.slice(0, SHOW).map(nodeFor));
    more.textContent = found.length > SHOW ? `Showing ${SHOW} of ${found.length >= 5000 ? 'many' : found.length} matches: type more to narrow them down.` : found.length ? '' : 'Nothing matches.';
  };
  if (big) showMatches('');
  else items.forEach((_, i) => list.appendChild(nodeFor(i)));
  dlg.body.appendChild(list);
  if (big) dlg.body.appendChild(more);
  if (search) {
    const s = /** @type {HTMLInputElement} */ (search);
    s.addEventListener('input', () => {
      const q = s.value.trim().toLowerCase();
      if (big) { showMatches(q); return; }
      list.querySelectorAll('[data-i]').forEach((n) => {
        const i = Number(/** @type {HTMLElement} */ (n).dataset.i);
        /** @type {HTMLElement} */ (n).style.display = !q || labels[i].includes(q) ? '' : 'none';
      });
    });
    s.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' || !big || !single || reveal || !hits.length) return;
      e.preventDefault();
      selected.clear();
      selected.add(hits[0]);
      refresh();
      if (!ok.disabled) ok.click();
    });
    setTimeout(() => s.focus(), 30);
  }
  dlg.foot.append(count);
  if (min === 0 && !reveal) dlg.foot.append(button('Cancel', '', () => reply(id, [])));
  dlg.foot.append(ok);
  refresh();
}

// ---------------------------------------------------------------- order (dual list)

function showOrder(m) {
  const { id, items, remMin, remMax, srcCount } = m;
  let src = items.slice(0, srcCount).map((_, i) => i);
  let dst = items.slice(srcCount).map((_, i) => i + srcCount);
  const dlg = modal(m.title || 'Order', { wide: true });
  open.set(id, dlg);
  const wrap = el(`<div class="dual"><div class="col"><h4>Available</h4><div class="list" data-src></div></div>
     <div class="col"><h4>${esc(m.top || 'Selected (in order)')}</h4><div class="list" data-dst></div></div></div>`);
  dlg.body.appendChild(wrap);
  let remember = false;
  const ok = button('OK', 'primary', () => reply(id, { order: dst, remember }));
  const auto = button('Move all', 'small', () => { dst = dst.concat(src); src = []; render(); });
  const row = (i, inDst, pos) => {
    const it = items[i];
    const r = el(`<div class="orow">${inDst ? `<span class="ix">${pos + 1}</span>` : ''}<div class="thumb"></div><span class="sp">${manaHtml(it.label)}</span></div>`);
    if (it.card) {
      const th = /** @type {HTMLElement} */ (r.querySelector('.thumb'));
      if (it.card.img) th.style.backgroundImage = `url("${imgUrl(it.card.img)}")`;
      else th.replaceWith(Object.assign(cardFaceEl(it.card, 'thumb'), {}));
      r.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: it.card })));
    } else {
      r.querySelector('.thumb')?.remove();
    }
    if (inDst) {
      const up = el('<button class="btn ghost small mv" title="Move up">▲</button>');
      const dn = el('<button class="btn ghost small mv" title="Move down">▼</button>');
      up.addEventListener('click', (e) => { e.stopPropagation(); if (pos > 0) { [dst[pos - 1], dst[pos]] = [dst[pos], dst[pos - 1]]; render(); } });
      dn.addEventListener('click', (e) => { e.stopPropagation(); if (pos < dst.length - 1) { [dst[pos + 1], dst[pos]] = [dst[pos], dst[pos + 1]]; render(); } });
      r.append(up, dn);
      r.addEventListener('click', () => { dst.splice(pos, 1); src.push(i); render(); });
    } else {
      r.addEventListener('click', () => {
        const canAdd = remMax < 0 || src.length > remMin;
        if (!canAdd) return;
        src = src.filter((x) => x !== i);
        dst.push(i);
        render();
      });
    }
    return r;
  };
  const render = () => {
    const s = /** @type {HTMLElement} */ (wrap.querySelector('[data-src]'));
    const d = /** @type {HTMLElement} */ (wrap.querySelector('[data-dst]'));
    s.replaceChildren(...src.map((i) => row(i, false, 0)));
    d.replaceChildren(...dst.map((i, p) => row(i, true, p)));
    const n = src.length;
    ok.disabled = !(remMax < 0 || (n >= remMin && n <= remMax));
    auto.style.display = remMax === 0 && src.length ? '' : 'none';
  };
  if (m.remember) {
    const cb = el('<label class="count"><input type="checkbox"> Remember this order</label>');
    cb.querySelector('input')?.addEventListener('change', (e) => { remember = /** @type {HTMLInputElement} */ (e.target).checked; });
    dlg.foot.append(cb);
  } else dlg.foot.append(el('<span class="count"></span>'));
  dlg.foot.append(auto, ok);
  render();
}

// ---------------------------------------------------------------- option / confirm

function showOption(m) {
  const { id } = m;
  const dlg = modal(m.title || '', { narrow: !m.card });
  open.set(id, dlg);
  if (m.card) {
    const ref = el('<div class="ref-card ccard"></div>');
    ref.appendChild(cardFaceEl(m.card));
    dlg.body.appendChild(ref);
  }
  dlg.body.appendChild(el(`<div style="white-space:pre-wrap;user-select:text">${manaHtml(m.message || '')}</div>`));
  (m.options || []).forEach((o, i) => {
    const b = button(o, i === m.def ? 'primary' : '', () => reply(id, i));
    dlg.foot.append(b);
    if (i === m.def) setTimeout(() => b.focus(), 30);
  });
}

function showMessage(m) {
  const { id } = m;
  const dlg = modal(m.title || (m.error ? 'Error' : 'Forge'), { narrow: true, peek: false });
  open.set(id, dlg);
  if (m.error) dlg.modal.style.borderColor = 'rgba(239,91,91,.6)';
  const text = el(`<div style="white-space:pre-wrap;user-select:text">${manaHtml(m.message || '')}</div>`);
  dlg.body.appendChild(text);
  const ok = button('OK', 'primary', () => reply(id, true));
  dlg.foot.append(ok);
  setTimeout(() => ok.focus(), 30);
  // a die roll: the dice roll across the window first (Options → Dice animation)
  if (m.dice && settings.diceAnim !== false) showDice(dlg, m.dice, text);
}

/** The planar die's faces: Planeswalk (1) and Chaos (6) opposite each other, four blanks. */
const PLANAR_FACE = { Planeswalk: '1', Chaos: '6' };

/** Rolls the dice of a roll message above its text, which appears when they come to rest. */
function showDice(dlg, d, text) {
  const dice = d.planar ? [{ kind: 'planar', result: PLANAR_FACE[d.planar] || '2' }]
    : [...(d.rolls || []).map((r) => ({ kind: kindFor(d.sides), result: String(r) })),
      ...(d.ignored || []).map((r) => ({ kind: kindFor(d.sides), result: String(r), dim: true }))];
  if (!dice.length || dice.length > 12) return;
  const box = el(`<div class="dice-box"><canvas class="dice-cv" title="Click to skip"></canvas>
    <div class="dice-res"></div></div>`);
  const res = /** @type {HTMLElement} */ (box.querySelector('.dice-res'));
  res.textContent = d.planar ? d.planar : (d.results || d.rolls || []).join(' · ');
  if (d.ignored?.length) res.appendChild(el(`<span class="ign"> (ignored ${d.ignored.join(', ')})</span>`));
  dlg.body.insertBefore(box, text);
  text.classList.add('dice-text');
  rollDice(/** @type {HTMLCanvasElement} */ (box.querySelector('canvas')), dice, () => {
    box.classList.add('landed');
    text.classList.add('shown');
  });
}

function showInput(m) {
  const { id } = m;
  const dlg = modal(m.title || 'Input', { narrow: true });
  open.set(id, dlg);
  dlg.body.appendChild(el(`<div style="margin-bottom:10px;white-space:pre-wrap">${manaHtml(m.message || '')}</div>`));
  /** @type {HTMLInputElement|HTMLSelectElement} */
  let input;
  if (m.options && m.options.length) {
    input = /** @type {HTMLSelectElement} */ (el(`<select class="text-input">${m.options.map((o) => `<option ${o === m.initial ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select>`));
  } else {
    input = /** @type {HTMLInputElement} */ (el(`<input class="text-input" ${m.numeric ? 'type="number"' : ''} value="${esc(m.initial || '')}">`));
  }
  dlg.body.appendChild(input);
  const ok = button('OK', 'primary', () => {
    const v = input.value;
    if (m.numeric && !/^-?\d+$/.test(v.trim())) { input.focus(); return; }
    reply(id, v);
  });
  input.addEventListener('keydown', (e) => { if (/** @type {KeyboardEvent} */ (e).key === 'Enter') ok.click(); });
  dlg.foot.append(button('Cancel', '', () => reply(id, null)), ok);
  setTimeout(() => input.focus(), 30);
}

// ---------------------------------------------------------------- damage / amounts

function stepper(value, onChange) {
  const box = el(`<div class="stepper"><button class="btn small">−</button><span class="v">${value}</span><button class="btn small">+</button></div>`);
  const [minus, plus] = box.querySelectorAll('button');
  minus.addEventListener('click', () => onChange(-1));
  plus.addEventListener('click', () => onChange(1));
  return box;
}

function showAssignDamage(m) {
  const { id, damage, blockers } = m;
  const dlg = modal(`Assign ${damage} combat damage from ${m.card?.n || 'attacker'}`, { wide: true });
  open.set(id, dlg);
  const amounts = blockers.map(() => 0);
  let toDef = 0;
  const hasDef = !!m.defender;
  // default: lethal in order, remainder to the defender (trample) or the last blocker
  let left = damage;
  blockers.forEach((b, i) => { const v = Math.min(left, Math.max(0, b.lethal)); amounts[i] = v; left -= v; });
  if (left > 0) { if (hasDef) toDef = left; else amounts[amounts.length - 1] += left; }
  const note = el('<div class="count" style="margin-bottom:10px"></div>');
  dlg.body.appendChild(note);
  const rows = el('<div></div>');
  dlg.body.appendChild(rows);
  const ok = button('OK', 'primary', () => reply(id, { blockers: amounts, defender: toDef }));
  const validate = () => {
    const sum = amounts.reduce((a, b) => a + b, 0) + toDef;
    let msg = '';
    if (sum !== damage) msg = `Assign exactly ${damage} (currently ${sum}).`;
    else if (!m.override) {
      for (let i = 0; i < blockers.length - 1; i++) {
        if (amounts[i] < blockers[i].lethal && amounts.slice(i + 1).some((x) => x > 0)) { msg = 'Each blocker must be assigned lethal damage before the next one.'; break; }
      }
      if (!msg && toDef > 0 && blockers.some((b, i) => amounts[i] < b.lethal)) msg = 'All blockers need lethal damage before trampling over.';
    }
    note.textContent = msg || `All ${damage} damage assigned.`;
    note.style.color = msg ? 'var(--red)' : 'var(--green)';
    ok.disabled = !!msg;
  };
  const render = () => {
    rows.replaceChildren();
    blockers.forEach((b, i) => {
      const r = el(`<div class="amount-row"><div class="thumb"></div><div><b>${esc(b.label)}</b><div class="count">Lethal: ${b.lethal}</div></div></div>`);
      const th = /** @type {HTMLElement} */ (r.querySelector('.thumb'));
      th.replaceWith(cardFaceEl(b.card, 'thumb'));
      r.appendChild(stepper(amounts[i], (d) => { amounts[i] = Math.max(0, amounts[i] + d); render(); }));
      rows.appendChild(r);
    });
    if (hasDef) {
      const r = el(`<div class="amount-row"><div class="thumb" style="display:grid;place-items:center;font-size:26px">⚔</div><div><b>${esc(m.defender.label)}</b><div class="count">Trample / excess damage</div></div></div>`);
      r.appendChild(stepper(toDef, (d) => { toDef = Math.max(0, toDef + d); render(); }));
      rows.appendChild(r);
    }
    validate();
  };
  if (m.maySkip) dlg.foot.append(button('Decide later', '', () => reply(id, { skip: true })));
  dlg.foot.append(ok);
  render();
}

function showAssignAmount(m) {
  const { id, amount, targets } = m;
  const dlg = modal(`Distribute ${amount}${m.unit ? ' ' + m.unit : ''}${m.card ? ' — ' + m.card.n : ''}`, { wide: targets.length > 3 });
  open.set(id, dlg);
  const vals = targets.map(() => (m.atLeastOne ? 1 : 0));
  let left = amount - vals.reduce((a, b) => a + b, 0);
  for (let i = 0; left > 0 && targets.length; i = (i + 1) % targets.length) {
    if (vals[i] < targets[i].max) { vals[i]++; left--; } else if (targets.every((t, j) => vals[j] >= t.max)) break;
  }
  const note = el('<div class="count" style="margin-bottom:10px"></div>');
  dlg.body.appendChild(note);
  const rows = el('<div></div>');
  dlg.body.appendChild(rows);
  const ok = button('OK', 'primary', () => reply(id, vals));
  const render = () => {
    rows.replaceChildren();
    targets.forEach((t, i) => {
      const r = el(`<div class="amount-row"><div class="thumb"></div><div><b>${esc(t.label)}</b><div class="count">max ${t.max}</div></div></div>`);
      if (t.card) /** @type {HTMLElement} */ (r.querySelector('.thumb')).replaceWith(cardFaceEl(t.card, 'thumb'));
      r.appendChild(stepper(vals[i], (d) => { vals[i] = Math.max(m.atLeastOne ? 1 : 0, Math.min(t.max, vals[i] + d)); render(); }));
      rows.appendChild(r);
    });
    const sum = vals.reduce((a, b) => a + b, 0);
    ok.disabled = sum !== amount;
    note.textContent = sum === amount ? 'Ready.' : `Assigned ${sum} of ${amount}.`;
    note.style.color = sum === amount ? 'var(--green)' : 'var(--muted)';
  };
  dlg.foot.append(ok);
  render();
}

// ---------------------------------------------------------------- numbers ("choose X", "how many")

/**
 * A number: − / + (hold to repeat), the arrow keys, the mouse wheel over it, or typed digits. Max sets the most the
 * player's mana can pay (X of a mana cost, worked out by the host), else the largest number the rules allow; it
 * only sets the number, OK (Space / Enter) goes on.
 */
function showNumber(m) {
  const { id } = m;
  const min = m.min ?? 0;
  const max = m.max ?? 999999; // no limit from the rules
  const afford = typeof m.afford === 'number' ? m.afford : null;
  /** what Max sets: what the mana can pay for, else the rules' limit (neither: no Max button) */
  const top = afford != null ? Math.max(min, Math.min(max, afford)) : m.max != null ? max : null;
  const dlg = modal(m.title || 'Choose a number', { narrow: true });
  open.set(id, dlg);
  let v = min;
  let typing = false; // digits typed since the number was last set another way: the next one is appended
  const box = el(`<div class="numpick">
    <button class="btn np-step" title="Less (↓)">−</button>
    <div class="np-v" title="Type a number, or use the arrow keys"></div>
    <button class="btn np-step" title="More (↑)">+</button>
    ${top != null ? `<button class="btn np-max" title="${afford != null ? 'As much as your mana can pay' : 'The largest number allowed'} (End)">Max</button>` : ''}</div>`);
  const [minus, plus] = /** @type {NodeListOf<HTMLButtonElement>} */ (box.querySelectorAll('.np-step'));
  const val = /** @type {HTMLElement} */ (box.querySelector('.np-v'));
  const note = el('<div class="np-note"></div>');
  if (m.cost) dlg.body.appendChild(el(`<div class="np-cost">Cost ${manaHtml(m.cost)}</div>`));
  dlg.body.append(box, note);
  const render = () => {
    val.textContent = String(v);
    minus.disabled = v <= min;
    plus.disabled = v >= max;
    let text = '';
    let warn = false;
    if (afford != null) {
      if (afford < min) { text = 'Your mana can\'t pay for this.'; warn = true; }
      else if (v > afford) { text = `More than your mana can pay (up to ${afford}).`; warn = true; }
      else text = `Your mana can pay up to ${afford}.`;
    } else if (m.max != null) text = `From ${min} to ${max}.`;
    else if (min > 0) text = `At least ${min}.`;
    note.textContent = text;
    note.classList.toggle('warn', warn);
  };
  const set = (x) => {
    v = Math.max(min, Math.min(max, x));
    typing = false;
    render();
  };
  // − / +: a step per press, repeating while held
  const hold = (/** @type {HTMLButtonElement} */ btn, d) => {
    let t = 0;
    const stop = () => {
      clearTimeout(t);
      window.removeEventListener('pointerup', stop);
    };
    btn.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      stop();
      set(v + d);
      window.addEventListener('pointerup', stop);
      t = window.setTimeout(function again() {
        if (!btn.isConnected || (d < 0 ? v <= min : v >= max)) { stop(); return; }
        set(v + d);
        t = window.setTimeout(again, 70);
      }, 400);
    });
    btn.addEventListener('pointerleave', stop);
  };
  hold(minus, -1);
  hold(plus, 1);
  box.querySelector('.np-max')?.addEventListener('click', () => { if (top != null) set(top); });
  let wheel = 0;
  box.addEventListener('wheel', (e) => {
    e.preventDefault();
    wheel += e.deltaMode ? e.deltaY * 40 : e.deltaY;
    if (Math.abs(wheel) < 40) return;
    set(v + (wheel < 0 ? 1 : -1));
    wheel = 0;
  }, { passive: false });
  dlg.keys = (e) => {
    if (e.ctrlKey || e.altKey || e.metaKey) return false;
    switch (e.key) {
      case 'ArrowUp': case 'ArrowRight': case '+': set(v + 1); return true;
      case 'ArrowDown': case 'ArrowLeft': case '-': set(v - 1); return true;
      case 'PageUp': set(v + 10); return true;
      case 'PageDown': set(v - 10); return true;
      case 'Home': set(min); return true;
      case 'End': if (top != null) set(top); return true;
      case 'Backspace': case 'Delete':
        v = Math.max(min, Math.floor(v / 10));
        typing = true;
        render();
        return true;
      default: break;
    }
    if (!/^\d$/.test(e.key)) return false;
    const next = (typing ? v * 10 : 0) + Number(e.key);
    if (next <= 999999) v = Math.max(min, Math.min(max, next));
    typing = true;
    render();
    return true;
  };
  dlg.foot.append(el('<span class="count"></span>'), button('Cancel', '', () => reply(id, null)), button('OK', 'primary', () => reply(id, v)));
  render();
}

// ---------------------------------------------------------------- scry / surveil

/**
 * Scry and surveil: only the cards looked at. Click a card to send it to the other row (bottom of the library, or the
 * graveyard) and back; drag cards, or use the arrows, to set the order. The top row's first card is the top of the
 * library; the bottom row's last card ends up at the very bottom.
 */
function showScry(m) {
  const { id, items } = m;
  const toGrave = m.other === 'graveyard';
  const otherName = toGrave ? 'Graveyard' : 'Bottom of library';
  // a few cards: the two rows side by side (no scrolling); more: one above the other
  const cols = items.length <= 3;
  const dlg = modal(m.title || 'Scry', { wide: items.length > 1 });
  open.set(id, dlg);
  dlg.modal.classList.add('scry-dlg');
  /** @type {number[][]} [top, other] item indices in order */
  const rows = [items.map((_, i) => i), []];
  const hint = items.length > 1
    ? `Click a card to move it between the rows. Drag cards (or use ◀ ▶) to order them.${toGrave ? '' : ' The last card of the bottom row goes to the very bottom.'}`
    : `Keep it on top, or put it ${toGrave ? 'into your graveyard' : 'on the bottom of your library'}?`;
  dlg.body.appendChild(el(`<div class="scry-hint">${esc(hint)}</div>`));
  const wrap = el(`<div class="scry-rows ${cols ? 'cols' : ''}">
    <div class="scry-row"><div class="scry-h"><b>Top of library</b><span>first card = drawn next</span></div><div class="scry-cards"></div></div>
    <div class="scry-row other ${toGrave ? 'grave' : ''}"><div class="scry-h"><b>${esc(otherName)}</b><span>${toGrave ? '' : 'last card = very bottom'}</span></div><div class="scry-cards"></div></div></div>`);
  dlg.body.appendChild(wrap);
  const rowEls = /** @type {HTMLElement[]} */ ([...wrap.querySelectorAll('.scry-cards')]);
  /** @type {Map<number, HTMLElement>} */
  const nodes = new Map();
  let dragging = -1;
  const move = (i, toRow, at = -1) => {
    for (const r of rows) { const p = r.indexOf(i); if (p >= 0) r.splice(p, 1); }
    const r = rows[toRow];
    r.splice(at < 0 || at > r.length ? r.length : at, 0, i);
    render();
  };
  const rowOf = (i) => (rows[0].includes(i) ? 0 : 1);
  const nodeFor = (i) => {
    let node = nodes.get(i);
    if (node) return node;
    const it = items[i];
    const n = el(`<div class="ccard scry-card" draggable="true" data-i="${i}"><div class="scry-pos"></div>
      <div class="scry-ctl"><button class="btn ghost small" data-mv="-1" title="Move left">◀</button><button class="btn small" data-flip></button><button class="btn ghost small" data-mv="1" title="Move right">▶</button></div></div>`);
    n.insertBefore(cardFaceEl(it.card), n.firstChild);
    n.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: it.card })));
    n.addEventListener('click', (e) => {
      const mv = /** @type {HTMLElement} */ (e.target).closest('[data-mv]');
      if (mv) {
        const r = rows[rowOf(i)];
        const p = r.indexOf(i), q = p + Number(/** @type {HTMLElement} */ (mv).dataset.mv);
        if (q >= 0 && q < r.length) { [r[p], r[q]] = [r[q], r[p]]; render(); }
        return;
      }
      if (items.length > 1) move(i, 1 - rowOf(i));
    });
    n.addEventListener('dragstart', (e) => {
      dragging = i;
      n.classList.add('dragging');
      e.dataTransfer?.setData('text/plain', String(i));
      if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
    });
    n.addEventListener('dragend', () => { dragging = -1; n.classList.remove('dragging'); });
    nodes.set(i, n);
    return n;
  };
  // dropping onto a row puts the card where the pointer is
  rowEls.forEach((rowEl, r) => {
    const target = /** @type {HTMLElement} */ (rowEl.parentElement);
    target.addEventListener('dragover', (e) => { if (dragging >= 0) { e.preventDefault(); target.classList.add('drop'); } });
    target.addEventListener('dragleave', () => target.classList.remove('drop'));
    target.addEventListener('drop', (e) => {
      e.preventDefault();
      target.classList.remove('drop');
      if (dragging < 0) return;
      const i = dragging;
      let at = 0;
      for (const child of rowEl.children) {
        if (Number(/** @type {HTMLElement} */ (child).dataset.i) === i) continue;
        const b = child.getBoundingClientRect();
        if (e.clientX > b.left + b.width / 2) at++;
      }
      move(i, r, at);
    });
  });
  const ordinal = (p) => `${p + 1}${['st', 'nd', 'rd'][p] || 'th'}`;
  const render = () => {
    rows.forEach((r, ri) => {
      rowEls[ri].replaceChildren(...r.map((i, p) => {
        const node = nodeFor(i);
        /** @type {HTMLElement} */ (node.querySelector('.scry-pos')).textContent = ri === 0 ? (p === 0 ? 'Top card' : `${ordinal(p)} from top`)
          : toGrave ? 'Graveyard' : p === r.length - 1 ? 'Very bottom' : `${ordinal(r.length - 1 - p)} from bottom`;
        /** @type {HTMLElement} */ (node.querySelector('[data-flip]')).textContent = ri === 0 ? (toGrave ? 'To graveyard' : 'To bottom') : 'To top';
        node.querySelector('.scry-ctl')?.classList.toggle('hidden', items.length < 2);
        node.querySelectorAll('[data-mv]').forEach((b) => b.classList.toggle('hidden', r.length < 2));
        return node;
      }));
      rowEls[ri].parentElement?.classList.toggle('empty', !r.length);
    });
  };
  const answer = () => reply(id, { top: rows[0], other: rows[1] });
  if (items.length === 1) {
    dlg.foot.append(el('<span class="count"></span>'),
      button(toGrave ? 'Graveyard' : 'Bottom', '', () => { rows[0] = []; rows[1] = [0]; answer(); }),
      button('Keep on top', 'primary', () => { rows[0] = [0]; rows[1] = []; answer(); }));
  } else {
    dlg.foot.append(el('<span class="count"></span>'),
      button('All on top', 'small', () => { rows[0] = rows[0].concat(rows[1]); rows[1] = []; render(); }),
      button(toGrave ? 'All to graveyard' : 'All to bottom', 'small', () => { rows[1] = rows[1].concat(rows[0]); rows[0] = []; render(); }),
      button('OK', 'primary', answer));
  }
  render();
}

// ---------------------------------------------------------------- arrange (scry-like) & sideboard

function showArrange(m) {
  const { id, items } = m;
  let order = items.map((_, i) => i);
  const dlg = modal(m.title || 'Arrange cards (top first)', { wide: true });
  open.set(id, dlg);
  const list = el('<div class="list" style="display:grid;gap:6px"></div>');
  dlg.body.appendChild(list);
  const render = () => {
    list.replaceChildren(...order.map((i, pos) => {
      const it = items[i];
      const r = el(`<div class="orow"><span class="ix">${pos + 1}</span><div class="thumb"></div><span class="sp">${esc(it.label)}</span></div>`);
      /** @type {HTMLElement} */ (r.querySelector('.thumb')).replaceWith(cardFaceEl(it.card, 'thumb'));
      r.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: it.card })));
      if (it.movable) {
        const up = el('<button class="btn ghost small mv">▲</button>');
        const dn = el('<button class="btn ghost small mv">▼</button>');
        up.addEventListener('click', () => { if (pos > 0) { [order[pos - 1], order[pos]] = [order[pos], order[pos - 1]]; render(); } });
        dn.addEventListener('click', () => { if (pos < order.length - 1) { [order[pos + 1], order[pos]] = [order[pos], order[pos + 1]]; render(); } });
        r.append(up, dn);
      }
      return r;
    }));
  };
  dlg.foot.append(button('OK', 'primary', () => reply(id, order)));
  render();
}

function showSideboard(m) {
  const { id, items, sbCount } = m;
  let side = items.slice(0, sbCount).map((_, i) => i);
  let main = items.slice(sbCount).map((_, i) => i + sbCount);
  const dlg = modal(m.message || 'Sideboarding', { wide: true });
  open.set(id, dlg);
  const wrap = el(`<div class="dual"><div class="col"><h4>Sideboard</h4><div class="list" data-a></div></div><div class="col"><h4>Main deck</h4><div class="list" data-b></div></div></div>`);
  dlg.body.appendChild(wrap);
  const render = () => {
    const mk = (i, fromSide) => {
      const r = el(`<div class="orow"><span class="sp">${esc(items[i].label)}</span></div>`);
      r.addEventListener('click', () => {
        if (fromSide) { side = side.filter((x) => x !== i); main.push(i); } else { main = main.filter((x) => x !== i); side.push(i); }
        render();
      });
      return r;
    };
    /** @type {HTMLElement} */ (wrap.querySelector('[data-a]')).replaceChildren(...side.map((i) => mk(i, true)));
    /** @type {HTMLElement} */ (wrap.querySelector('[data-b]')).replaceChildren(...main.map((i) => mk(i, false)));
  };
  dlg.foot.append(button('Keep deck', '', () => reply(id, null)), button('OK', 'primary', () => reply(id, main)));
  render();
}

// ---------------------------------------------------------------- wiring

on('dialog', (m) => {
  if (open.has(m.id)) return; // re-sent after reconnect
  switch (m.kind) {
    case 'choose': showChoose(m); break;
    case 'order': showOrder(m); break;
    case 'option': showOption(m); break;
    case 'message': showMessage(m); break;
    case 'input': showInput(m); break;
    case 'assignDamage': showAssignDamage(m); break;
    case 'assignAmount': showAssignAmount(m); break;
    case 'number': showNumber(m); break;
    case 'arrange': showArrange(m); break;
    case 'scry': showScry(m); break;
    case 'sideboard': showSideboard(m); break;
    default:
      console.warn('unknown dialog', m);
      send({ t: 'reply', id: m.id, v: null });
  }
});
on('dialogClose', (m) => close(m.id));

export function anyDialogOpen() {
  return open.size > 0;
}

/**
 * A key press while the engine waits for an answer. The topmost question (the latest) may use it (the number
 * window: arrows, digits); the OK key (Space / Enter) presses its highlighted button, like a click. Returns true
 * when the key was used or swallowed: an OK press in the first moment after the window appeared was most likely
 * meant for what was there before (passing priority), so it doesn't answer a question the player hasn't read.
 * @param {KeyboardEvent} e
 * @param {boolean} isOk the player's OK key
 */
export function dialogKey(e, isOk) {
  /** @type {Modal|null} */
  let d = null;
  for (const x of open.values()) if (x.back.isConnected) d = x;
  if (!d) return false;
  if (d.back.classList.contains('peek')) {
    // looking at the board: the OK key brings the window back
    if (!isOk) return false;
    if (!e.repeat) d.setPeek(false);
    return true;
  }
  if (d.keys?.(e)) return true;
  if (!isOk) return false;
  if (e.repeat || performance.now() - d.at < 350) return true;
  const ok = /** @type {HTMLButtonElement|null} */ (d.foot.querySelector('.btn.primary'));
  if (ok && !ok.disabled) ok.click();
  else {
    // nothing to confirm yet (e.g. no card chosen): a small shake says so
    d.modal.classList.remove('nudge');
    void d.modal.offsetWidth;
    d.modal.classList.add('nudge');
  }
  return true;
}

export function closeAllDialogs() {
  for (const id of [...open.keys()]) close(id);
}
