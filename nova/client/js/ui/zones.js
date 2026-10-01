// @ts-check
// Floating zone viewers (graveyard, exile, library, command...). Cards inside are clickable,
// so targets in these zones can be picked exactly like on the battlefield. While the engine waits
// for a pick among a zone's cards (e.g. a library search), its viewer repeats the prompt and buttons,
// lists the pickable cards first and dims the rest, like Forge's desktop zone windows.
// When the engine opens several zones at once (a card that picks from every graveyard), they share
// one window, a section per zone. Your own library's window can list the cards left in it.

import { store, subscribe, zoneCards, isLocal, TF } from '../store.js';
import { on, send } from '../net.js';
import { el, esc, manaHtml } from './text.js';
import { modal, cardFaceEl } from './dialogs.js';

/**
 * Open viewers by 'pid:zone' (a window showing several zones is listed under each of them).
 * @type {Map<string, {close: () => void, render: () => void, removeZone: (key: string) => void}>}
 */
const viewers = new Map();

const ZONE_NAMES = { Graveyard: 'Graveyard', Exile: 'Exile', Library: 'Library', Command: 'Command zone', Hand: 'Hand', Sideboard: 'Sideboard', Ante: 'Ante', Junkyard: 'Junkyard' };
const ZONE_PLURAL = { Graveyard: 'Graveyards', Exile: 'Exile', Library: 'Libraries', Command: 'Command zones', Hand: 'Hands', Sideboard: 'Sideboards', Ante: 'Ante', Junkyard: 'Junkyards' };
const FILTER_FROM = 20; // cards in a window before the name filter appears

const keyOf = (pid, zone) => pid + ':' + zone;

export function openZone(pid, zone, auto = false) {
  openZones([{ p: pid, z: zone }], auto);
}

/**
 * One window for these zones ({p: player id, z: zone}); zones already shown in a window stay there.
 * @param {{p: number, z: string}[]} list
 */
export function openZones(list, auto = false) {
  const seen = new Set();
  const zones = list.filter((z) => {
    const k = keyOf(z.p, z.z);
    if (seen.has(k) || viewers.has(k) || !store.players.get(z.p)) return false;
    seen.add(k);
    return true;
  });
  if (!zones.length) return;
  const multi = zones.length > 1;
  const first = /** @type {any} */ (store.players.get(zones[0].p));
  const title = !multi ? `${first.n} — ${ZONE_NAMES[zones[0].z] || zones[0].z}`
    : zones.every((z) => z.z === zones[0].z) ? ZONE_PLURAL[zones[0].z] || zones[0].z
      : [...new Set(zones.map((z) => ZONE_NAMES[z.z] || z.z))].join(' and ');
  // Esc closes a viewer you opened; one the engine opened for a pick stays (Esc opens the options)
  const dlg = modal(title, { wide: true, onEsc: auto ? undefined : () => close() });
  dlg.back.style.background = 'rgba(5,7,10,.35)';
  // prompt and filter stay above the scrolling cards
  const tools = el('<div class="zone-tools"><div class="zone-prompt"></div><input class="search" placeholder="Filter by name…"></div>');
  const promptEl = /** @type {HTMLElement} */ (tools.querySelector('.zone-prompt'));
  const filter = /** @type {HTMLInputElement} */ (tools.querySelector('input'));
  dlg.modal.insertBefore(tools, dlg.body);
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

  /** a section per zone: in a window of one zone, just its grid */
  const sections = zones.map((z) => {
    const box = el(`<div class="zone-sec ${multi ? 'multi' : ''}">${multi ? '<div class="zone-sec-h"><b></b><span class="n"></span></div>' : ''}<div class="zone-grid"></div><div class="zone-empty hidden">No cards</div></div>`);
    dlg.body.appendChild(box);
    return {
      key: keyOf(z.p, z.z), pid: z.p, zone: z.z, box,
      head: /** @type {HTMLElement|null} */ (box.querySelector('.zone-sec-h b')),
      count: /** @type {HTMLElement|null} */ (box.querySelector('.zone-sec-h .n')),
      grid: /** @type {HTMLElement} */ (box.querySelector('.zone-grid')),
      empty: /** @type {HTMLElement} */ (box.querySelector('.zone-empty')),
    };
  });

  // your own library: what's left in it, worked out from the cards you can see elsewhere
  const ownLibrary = !multi && zones[0].z === 'Library' && isLocal(zones[0].p) && !store.spectator;
  /** @type {null | {data: any, box: HTMLElement}} */
  let left = null;
  const leftBtn = ownLibrary ? /** @type {HTMLButtonElement} */ (el('<button class="btn small lib-left-btn" title="Your deck minus every card of yours you can see somewhere else">Cards left in library</button>')) : null;
  if (leftBtn) {
    dlg.foot.insertBefore(leftBtn, closeBtn);
    leftBtn.addEventListener('click', () => {
      if (left) { left.box.remove(); left = null; render(); return; }
      left = { data: null, box: el('<div class="lib-left"><div class="note">Working out what\'s left…</div></div>') };
      dlg.body.appendChild(left.box);
      send({ t: 'libraryLeft', id: zones[0].p });
      render();
    });
  }
  const offLeft = on('libraryLeft', (m) => {
    if (!left || m.pid !== zones[0].p) return;
    left.data = m;
    renderLeft();
  });

  let choosing = false; // the engine is waiting for a pick among this window's cards
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
    const sel = store.sel;
    const q = filter.value.trim().toLowerCase();
    let total = 0, hiddenTotal = 0, listed = 0;
    const per = sections.map((s) => {
      const pl = store.players.get(s.pid);
      let cards = zoneCards(pl, s.zone);
      // a library lists its top card first, other zones their most recent card
      if (s.zone !== 'Library') cards = cards.slice().reverse();
      const picks = cards.filter((c) => sel.ids.has(c.id)).length;
      const hidden = pl?.z?.[s.zone + '#'] || 0;
      total += cards.length + hidden;
      hiddenTotal += hidden;
      listed += cards.length;
      return { s, pl, cards, picks, hidden };
    });
    choosing = per.some((x) => x.picks > 0);
    // several zones: the ones you can pick from first
    if (multi) {
      const order = per.slice().sort((a, b) => Number(b.picks > 0) - Number(a.picks > 0));
      order.forEach((x, i) => { if (dlg.body.children[i] !== x.s.box) dlg.body.insertBefore(x.s.box, dlg.body.children[i] || null); });
    }
    filter.classList.toggle('hidden', listed < FILTER_FROM && !q);
    const showing = new Set();
    for (const { s, pl, cards: all, picks, hidden } of per) {
      let cards = all;
      if (choosing) cards = cards.filter((c) => sel.ids.has(c.id)).concat(cards.filter((c) => !sel.ids.has(c.id)));
      if (s.head) {
        const zn = (ZONE_NAMES[s.zone] || s.zone).toLowerCase();
        s.head.textContent = isLocal(s.pid) ? `Your ${zn}` : `${pl?.n || '?'}'s ${zn}`;
        /** @type {HTMLElement} */ (s.count).textContent = `${all.length + hidden} card${all.length + hidden === 1 ? '' : 's'}${choosing ? picks ? ` · ${picks} to choose from` : ' · nothing to choose here' : ''}`;
      }
      const shown = [];
      for (const c of cards) {
        const node = nodeFor(c);
        const pick = sel.ids.has(c.id);
        node.classList.toggle('pick', pick);
        node.classList.toggle('act', !pick && sel.weak.has(c.id)); // playable from here right now
        node.classList.toggle('sel', sel.hiC.has(c.id));
        node.classList.toggle('dim', choosing && !pick);
        showing.add(c.id);
        if (!q || String(c.n || '').toLowerCase().includes(q)) shown.push(node);
      }
      const cur = s.grid.children;
      if (shown.length !== cur.length || shown.some((n, i) => cur[i] !== n)) s.grid.replaceChildren(...shown);
      s.empty.classList.toggle('hidden', all.length > 0 || hidden > 0 || !multi);
      s.box.classList.toggle('none', choosing && picks === 0);
      // the "cards left" list replaces the library's (hidden) cards
      s.box.classList.toggle('hidden', !!left);
    }
    for (const id of nodes.keys()) if (!showing.has(id)) nodes.delete(id);
    info.textContent = `${total} card${total === 1 ? '' : 's'}${hiddenTotal ? ` (${hiddenTotal} hidden)` : ''}`;
    if (leftBtn) leftBtn.textContent = left ? 'Show the library' : 'Cards left in library';
    renderPrompt();
    if (left && left.data) requestLeft();
  };

  // ---- cards left in your library
  let leftTimer = 0;
  let leftVersion = -1;
  /** the library changed (a draw, a search): ask again, a moment later */
  const requestLeft = () => {
    if (leftVersion === store.version || leftTimer) return;
    leftTimer = window.setTimeout(() => {
      leftTimer = 0;
      leftVersion = store.version;
      if (left) send({ t: 'libraryLeft', id: zones[0].p });
    }, 400);
  };
  const GROUPS = [['Creatures', TF.CREATURE], ['Planeswalkers', TF.PW], ['Battles', TF.BATTLE], ['Instants', TF.INSTANT], ['Sorceries', TF.SORCERY],
    ['Artifacts', TF.ARTIFACT], ['Enchantments', TF.ENCH], ['Lands', TF.LAND]];
  const groupOf = (tf) => {
    if (tf & TF.LAND) return 'Lands';
    for (const [name, bit] of GROUPS) if (tf & bit) return name;
    return 'Other';
  };
  const renderLeft = () => {
    if (!left || !left.data) return;
    const m = left.data;
    leftVersion = store.version;
    if (m.error) { left.box.innerHTML = '<div class="note">Couldn\'t work out the cards left. Try again in a moment.</div>'; return; }
    const entries = (m.cards || []).slice();
    const total = entries.reduce((n, e) => n + e.k, 0);
    const lands = entries.filter((e) => e.tf & TF.LAND).reduce((n, e) => n + e.k, 0);
    const q = filter.value.trim().toLowerCase();
    const groups = new Map();
    for (const e of entries) {
      const g = groupOf(e.tf || 0);
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push(e);
    }
    const head = el(`<div class="lib-left-sum">
      <div><b>${total}</b> card${total === 1 ? '' : 's'} left <span class="dim">· your deck minus the cards you can see elsewhere (hand, battlefield, graveyard, exile, command zone, stack)</span></div>
      <div class="lib-left-stats">${lands} land${lands === 1 ? '' : 's'}${total ? ` · next card is a land: <b>${Math.round((lands / total) * 100)}%</b>` : ''}
        ${m.elsewhere ? ` · <span title="Cards of yours that are face down in exile or in another hidden place: you can't tell them apart from your library">includes ${m.elsewhere} card${m.elsewhere === 1 ? '' : 's'} of yours hidden elsewhere</span>` : ''}</div></div>`);
    const order = [...GROUPS.map((g) => g[0]), 'Other'];
    const parts = [head];
    for (const g of order) {
      const list = groups.get(g);
      if (!list) continue;
      list.sort((a, b) => (a.cmc || 0) - (b.cmc || 0) || String(a.card?.n).localeCompare(String(b.card?.n)));
      const visible = list.filter((e) => !q || String(e.card?.n || '').toLowerCase().includes(q));
      if (!visible.length) continue;
      const n = list.reduce((s, e) => s + e.k, 0);
      const sec = el(`<div class="zone-sec multi"><div class="zone-sec-h"><b>${esc(g)}</b><span class="n">${n}</span></div><div class="zone-grid"></div></div>`);
      const grid = /** @type {HTMLElement} */ (sec.querySelector('.zone-grid'));
      for (const e of visible) {
        const node = el(`<div class="ccard left-card" title="${esc(e.card?.n || '')}"></div>`);
        node.appendChild(cardFaceEl(e.card));
        if (e.k > 1) node.appendChild(el(`<div class="badge">×${e.k}</div>`));
        node.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: e.card })));
        grid.appendChild(node);
      }
      parts.push(sec);
    }
    if (!entries.length) parts.push(el('<div class="note">No cards left in your library.</div>'));
    left.box.replaceChildren(...parts);
  };

  filter.addEventListener('input', () => { render(); renderLeft(); });
  const unsub = subscribe((kind) => { if (kind === 'prompt') renderPrompt(); else render(); });
  const keys = new Set(sections.map((s) => s.key));
  const close = () => {
    unsub();
    offLeft();
    clearTimeout(leftTimer);
    dlg.close();
    for (const k of keys) viewers.delete(k);
  };
  /** the engine is done with one of the zones */
  const removeZone = (k) => {
    const s = sections.find((x) => x.key === k);
    if (!s || !keys.has(k)) return;
    keys.delete(k);
    viewers.delete(k);
    s.box.remove();
    if (!keys.size) close();
    else render();
  };
  closeBtn.addEventListener('click', close);
  dlg.back.addEventListener('click', (e) => { if (e.target === dlg.back && !auto) close(); });
  const viewer = { close, render, removeZone };
  for (const k of keys) viewers.set(k, viewer);
  render();
}

export function closeZone(pid, zone) {
  viewers.get(keyOf(pid, zone))?.removeZone(keyOf(pid, zone));
}

export function closeAllZones() {
  for (const v of new Set(viewers.values())) v.close();
}

// the engine shows zones for a choice (several at once share one window) and hides them afterwards
on('showZones', (m) => openZones(m.zones, true));
on('hideZones', (m) => { for (const z of m.zones) closeZone(z.p, z.z); });
