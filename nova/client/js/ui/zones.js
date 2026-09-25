// @ts-check
// Floating zone viewers (graveyard, exile, library, command...). Cards inside are clickable,
// so targets in these zones can be picked exactly like on the battlefield. While the engine waits
// for a pick among a zone's cards (e.g. a library search), its viewer repeats the prompt and buttons,
// lists the pickable cards first and dims the rest, like Forge's desktop zone windows.

import { store, subscribe, zoneCards } from '../store.js';
import { on, send } from '../net.js';
import { el, esc, manaHtml } from './text.js';
import { modal, cardFaceEl } from './dialogs.js';

/** @type {Map<string, {close:() => void, render: () => void}>} */
const viewers = new Map();

const ZONE_NAMES = { Graveyard: 'Graveyard', Exile: 'Exile', Library: 'Library', Command: 'Command zone', Hand: 'Hand', Sideboard: 'Sideboard', Ante: 'Ante', Junkyard: 'Junkyard' };
const FILTER_FROM = 20; // cards in a zone before the name filter appears

export function openZone(pid, zone, auto = false) {
  const key = pid + ':' + zone;
  if (viewers.has(key)) return;
  const p = store.players.get(pid);
  if (!p) return;
  // Esc closes a viewer you opened; one the engine opened for a pick stays (Esc opens the options)
  const dlg = modal(`${p.n} — ${ZONE_NAMES[zone] || zone}`, { wide: true, onEsc: auto ? undefined : () => close() });
  dlg.back.style.background = 'rgba(5,7,10,.35)';
  // prompt and filter stay above the scrolling cards
  const tools = el('<div class="zone-tools"><div class="zone-prompt"></div><input class="search" placeholder="Filter by name…"></div>');
  const promptEl = /** @type {HTMLElement} */ (tools.querySelector('.zone-prompt'));
  const filter = /** @type {HTMLInputElement} */ (tools.querySelector('input'));
  dlg.modal.insertBefore(tools, dlg.body);
  const grid = el('<div class="zone-grid"></div>');
  dlg.body.appendChild(grid);
  const info = el('<span class="count"></span>');
  const closeBtn = el('<button class="btn ghost">Close</button>');
  const b2 = /** @type {HTMLButtonElement} */ (el('<button class="btn"></button>'));
  const b1 = /** @type {HTMLButtonElement} */ (el('<button class="btn primary"></button>'));
  b1.addEventListener('click', () => send({ t: 'ok' }));
  b2.addEventListener('click', () => send({ t: 'cancel' }));
  dlg.foot.append(info, closeBtn, b2, b1);

  /** @type {Map<number, {card:any, node:HTMLElement}>} rebuilt only when the card itself changes */
  const nodes = new Map();
  const nodeFor = (c) => {
    const known = nodes.get(c.id);
    if (known && known.card === c) return known.node;
    const node = el(`<div class="ccard" title="${esc(c.n || '')}"></div>`);
    node.appendChild(cardFaceEl(c));
    node.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: c })));
    node.addEventListener('click', (e) => {
      if (node.classList.contains('dim')) return; // not a legal pick
      send({ t: 'card', id: c.id, btn: 1, x: e.clientX, y: e.clientY });
    });
    nodes.set(c.id, { card: c, node });
    return node;
  };

  let choosing = false; // the engine is waiting for a pick among this zone's cards
  const renderPrompt = () => {
    const pr = store.prompt || {};
    const text = choosing ? pr.msg || '' : '';
    if (promptEl.dataset.text !== text) {
      promptEl.dataset.text = text;
      promptEl.innerHTML = manaHtml(text);
    }
    promptEl.classList.toggle('hidden', !text);
    for (const [b, st, label] of /** @type {[HTMLButtonElement, any, string][]} */ ([[b1, pr.b1, 'OK'], [b2, pr.b2, 'Cancel']])) {
      b.textContent = st?.l || label;
      b.disabled = !st?.on;
      b.classList.toggle('hidden', !choosing || (!st?.l && !st?.on));
    }
    tools.classList.toggle('hidden', !text && filter.classList.contains('hidden'));
  };
  const render = () => {
    const pl = store.players.get(pid);
    const sel = store.sel;
    let cards = zoneCards(pl, zone);
    // a library lists its top card first, other zones their most recent card
    if (zone !== 'Library') cards = cards.slice().reverse();
    choosing = cards.some((c) => sel.ids.has(c.id));
    if (choosing) cards = cards.filter((c) => sel.ids.has(c.id)).concat(cards.filter((c) => !sel.ids.has(c.id)));
    const hidden = pl?.z?.[zone + '#'] || 0;
    const total = cards.length + hidden;
    info.textContent = `${total} card${total === 1 ? '' : 's'}${hidden ? ` (${hidden} hidden)` : ''}`;
    const q = filter.value.trim().toLowerCase();
    filter.classList.toggle('hidden', cards.length < FILTER_FROM && !q);
    const shown = [];
    for (const c of cards) {
      const node = nodeFor(c);
      const pick = sel.ids.has(c.id);
      node.classList.toggle('pick', pick);
      node.classList.toggle('act', !pick && sel.weak.has(c.id)); // playable from here right now
      node.classList.toggle('sel', sel.hiC.has(c.id));
      node.classList.toggle('dim', choosing && !pick);
      if (!q || String(c.n || '').toLowerCase().includes(q)) shown.push(node);
    }
    const ids = new Set(cards.map((c) => c.id));
    for (const id of nodes.keys()) if (!ids.has(id)) nodes.delete(id);
    const cur = grid.children;
    if (shown.length !== cur.length || shown.some((n, i) => cur[i] !== n)) grid.replaceChildren(...shown);
    renderPrompt();
  };
  filter.addEventListener('input', render);
  const unsub = subscribe((kind) => { if (kind === 'prompt') renderPrompt(); else render(); });
  const close = () => { unsub(); dlg.close(); viewers.delete(key); };
  closeBtn.addEventListener('click', close);
  dlg.back.addEventListener('click', (e) => { if (e.target === dlg.back && !auto) close(); });
  viewers.set(key, { close, render });
  render();
}

export function closeZone(pid, zone) {
  viewers.get(pid + ':' + zone)?.close();
}

export function closeAllZones() {
  for (const v of [...viewers.values()]) v.close();
}

on('showZones', (m) => { for (const z of m.zones) openZone(z.p, z.z, true); });
on('hideZones', (m) => { for (const z of m.zones) closeZone(z.p, z.z); });
