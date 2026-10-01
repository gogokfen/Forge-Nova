// @ts-check
// Deck builder dialogs: open/duplicate/delete decks, import, export, basic lands, sample hand.

import { api, imgUrl } from '../net.js';
import { el, esc, manaHtml, pipsHtml, toast } from '../ui/text.js';
import { modal } from '../ui/dialogs.js';
import { openMoxfield } from '../ui/moxfield.js';
import { findCard, T } from './catalog.js';
import { FORMATS, parseDeckText, exportText, pipsOf } from './deck.js';

const FOLDER_NAMES = { commander: 'Commander', constructed: 'Constructed', brawl: 'Brawl', oathbreaker: 'Oathbreaker', tinyLeaders: 'Tiny Leaders', cmdPrecon: 'Commander precon', precon: 'Precon' };

function button(label, cls, fn) {
  const b = el(`<button class="btn ${cls}">${esc(label)}</button>`);
  b.addEventListener('click', fn);
  return b;
}

/** A card image with a text stand-in underneath until (or unless) the image loads. */
export function cardTile(card, { img = '', qty = 0, cls = '', lazy = false } = {}) {
  const key = img || card?.img || '';
  const t = el(`<div class="bc-tile ${cls}"><div class="ph"><b>${esc(card?.n || '?')}</b><span>${manaHtml(card?.mc || '')}</span><small>${esc(card?.t || '')}</small></div>${qty ? `<span class="qty">${qty}</span>` : ''}</div>`);
  if (key) {
    const im = new Image();
    im.alt = '';
    im.draggable = false;
    im.decoding = 'async';
    im.onload = () => t.classList.add('ok');
    im.onerror = () => im.remove();
    t.insertBefore(im, t.firstChild);
    if (lazy) t.dataset.src = imgUrl(key);
    else im.src = imgUrl(key);
  }
  return t;
}

/** Resolves true when confirmed. */
export function confirmBox(title, message, okLabel = 'OK', danger = true) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; d.close(); resolve(v); };
    const d = modal(title, { narrow: true, peek: false, onEsc: () => finish(false) });
    d.body.appendChild(el(`<div style="white-space:pre-wrap">${esc(message)}</div>`));
    const cancel = button('Cancel', '', () => finish(false));
    d.foot.append(cancel, button(okLabel, danger ? 'danger' : 'primary', () => finish(true)));
    setTimeout(() => cancel.focus(), 30);
  });
}

/** Resolves the index of the chosen option, or -1 (Esc / cancel). The last option is the primary one. */
export function choose(title, message, options) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; d.close(); resolve(v); };
    const d = modal(title, { narrow: true, peek: false, onEsc: () => finish(-1) });
    d.body.appendChild(el(`<div style="white-space:pre-wrap">${esc(message)}</div>`));
    options.forEach((o, i) => d.foot.append(button(o, i === options.length - 1 ? 'primary' : '', () => finish(i))));
  });
}

/** Resolves the entered text, or null. */
export function askName(title, initial = '', okLabel = 'Save') {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; d.close(); resolve(v); };
    const d = modal(title, { narrow: true, peek: false, onEsc: () => finish(null) });
    const input = /** @type {HTMLInputElement} */ (el(`<input class="text-input" maxlength="120" value="${esc(initial)}" placeholder="Deck name">`));
    d.body.appendChild(input);
    const ok = button(okLabel, 'primary', () => { const v = input.value.trim(); if (v) finish(v); else input.focus(); });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') ok.click(); });
    d.foot.append(button('Cancel', '', () => finish(null)), ok);
    setTimeout(() => { input.focus(); input.select(); }, 30);
  });
}

// ---------------------------------------------------------------- open / duplicate / delete

/**
 * Deck browser. `onOpen({src, name})` opens a deck; `onDeleted({src, name})` reports a deleted file.
 */
export async function openDeckBrowser({ onOpen, onDeleted }) {
  const dlg = modal('Open a deck', { wide: true, peek: false, onEsc: () => dlg.close() });
  dlg.modal.classList.add('picker');
  const top = el(`<div class="picker-top"><div class="tabs"><button data-tab="mine">My decks</button><button data-tab="precons">Forge precons (templates)</button></div>
    <select data-folder><option value="">All formats</option>${Object.entries(FORMATS).map(([k, f]) => `<option value="${k}">${esc(f.t)}</option>`).join('')}</select>
    <input placeholder="Search decks or commanders…"><button class="btn small ghost" data-mox title="Sync your decks from Moxfield">Moxfield…</button></div>`);
  top.querySelector('[data-mox]')?.addEventListener('click', () => { dlg.close(); openMoxfield({ onOpenDeck: onOpen }); });
  const grid = el('<div class="deck-grid"><div class="note">Loading decks…</div></div>');
  Object.assign(dlg.body.style, { padding: '0', display: 'flex', flexDirection: 'column', flex: '1' });
  dlg.body.append(top, grid);
  dlg.foot.append(button('Close', '', () => dlg.close()));
  const input = /** @type {HTMLInputElement} */ (top.querySelector('input'));
  const folderSel = /** @type {HTMLSelectElement} */ (top.querySelector('[data-folder]'));
  let tab = 'mine';
  let data = null;

  const load = async () => {
    try {
      data = await api('decks');
      render();
    } catch (e) {
      grid.replaceChildren(el(`<div class="note">Could not load decks: ${esc(e.message)}</div>`));
    }
  };
  const art = (d) => {
    const key = d.cmdrs?.[0]?.img || d.art;
    return key ? imgUrl(key) : '';
  };
  const duplicate = async (d) => {
    try {
      const full = await api(`deck?src=${encodeURIComponent(d.src)}&name=${encodeURIComponent(d.name)}`);
      const leaf = String(d.name).split('/').pop();
      const folder = String(d.name).includes('/') ? String(d.name).slice(0, String(d.name).lastIndexOf('/') + 1) : '';
      for (let i = 2; i < 50; i++) {
        const name = `${folder}${leaf} (${i})`;
        try {
          await api('deck/save', { src: d.src, name, comment: full.comment || '', sections: full.sections });
          toast(`Saved a copy as "${leaf} (${i})".`);
          return load();
        } catch (e) {
          if (/** @type {any} */ (e).status !== 409) throw e;
        }
      }
    } catch (e) {
      toast('Could not duplicate: ' + e.message, 'error');
    }
  };
  const remove = async (d) => {
    if (!await confirmBox('Delete deck?', `Delete "${d.name}" (${FOLDER_NAMES[d.src] || d.src})? The deck file is removed for classic Forge too.`, 'Delete')) return;
    try {
      await api('deck/delete', { src: d.src, name: d.name });
      onDeleted?.({ src: d.src, name: d.name });
      toast(`Deleted "${d.name}".`);
      load();
    } catch (e) {
      toast('Could not delete: ' + e.message, 'error');
    }
  };
  const render = () => {
    top.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('sel', /** @type {HTMLElement} */ (b).dataset.tab === tab));
    if (!data) return;
    const q = input.value.trim().toLowerCase();
    const folder = folderSel.value;
    let list = tab === 'mine' ? (data.user || []) : (data.builtin || []);
    if (folder) {
      const f = FORMATS[folder];
      list = tab === 'mine' ? list.filter((d) => d.src === folder) : list.filter((d) => (f.cmdr ? !!d.cmdrs?.length : !d.cmdrs?.length));
    }
    if (q) list = list.filter((d) => (d.name + ' ' + (d.cmdrs || []).map((c) => c.n).join(' ')).toLowerCase().includes(q));
    const shown = list.slice(0, 600);
    grid.replaceChildren(...shown.map((d) => {
      const a = art(d);
      const t = el(`<div class="deck-tile"><div class="img">${a ? `<img loading="lazy" src="${a}" style="width:100%;height:100%;object-fit:cover;object-position:50% 18%">` : ''}</div>
        <div class="meta"><div class="n" title="${esc(d.name)}">${esc(d.name)}</div>
        <div class="s"><span>${esc(d.cmdrs?.map((c) => c.n.split(',')[0]).join(' + ') || FOLDER_NAMES[d.src] || d.src)} · ${d.count}</span><span class="pips">${d.colors !== undefined ? pipsHtml(d.colors) : ''}</span></div>
        ${tab === 'mine' ? `<div class="acts"><span class="fold">${esc(FOLDER_NAMES[d.src] || d.src)}</span><button class="btn ghost small" data-dup title="Save a copy">Duplicate</button><button class="btn ghost small" data-del title="Delete this deck">Delete</button></div>` : ''}</div></div>`);
      t.addEventListener('click', (e) => {
        const b = /** @type {HTMLElement} */ (e.target).closest('button');
        if (b?.hasAttribute('data-dup')) duplicate(d);
        else if (b?.hasAttribute('data-del')) remove(d);
        else { dlg.close(); onOpen({ src: d.src, name: d.name }); }
      });
      return t;
    }));
    if (!shown.length) grid.appendChild(el(`<div class="note">${tab === 'mine' ? 'No decks here yet. Start one with New.' : 'No precons match.'}</div>`));
  };
  top.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => { tab = /** @type {HTMLElement} */ (b).dataset.tab || 'mine'; render(); }));
  input.addEventListener('input', render);
  folderSel.addEventListener('change', render);
  setTimeout(() => input.focus(), 30);
  render();
  await load();
}

// ---------------------------------------------------------------- import / export

/** Paste a decklist (Arena, MTGO, Moxfield, Archidekt, Forge .dck...). */
export function openImport(deck) {
  const dlg = modal('Import a decklist', { wide: true, peek: false, onEsc: () => dlg.close() });
  const ta = /** @type {HTMLTextAreaElement} */ (el(`<textarea class="text-input bd-import" spellcheck="false" placeholder="Paste a list, for example:

Commander
1 Atraxa, Praetors' Voice

Deck
1 Sol Ring (C21) 263
4x Lightning Bolt
SB: 2 Duress"></textarea>`));
  const mode = el(`<div class="seg" data-mode><button data-v="replace" class="sel">Replace the deck</button><button data-v="add">Add to the deck</button></div>`);
  const info = el('<div class="count bd-import-info"></div>');
  dlg.body.append(ta, el('<div style="display:flex;gap:12px;align-items:center;margin-top:10px"></div>'));
  /** @type {HTMLElement} */ (dlg.body.lastElementChild).append(mode, info);
  let replace = true;
  mode.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => {
    replace = b.dataset.v === 'replace';
    mode.querySelectorAll('button').forEach((x) => x.classList.toggle('sel', x === b));
  }));
  const go = button('Import', 'primary', () => {
    const r = parseDeckText(ta.value);
    if (!r.entries.length) { toast('No cards recognized.', 'error'); return; }
    deck.change(() => {
      if (replace) deck.sections = { commander: [], main: [], side: [] };
      if (r.entries.some((e) => e.section === 'commander') && !deck.format.cmdr) deck.src = 'commander';
      for (const e of r.entries) {
        const list = deck.sections[e.section];
        const set = e.set || e.card.s;
        const same = list.find((x) => x.n === e.card.n && x.s === set && (x.a || 0) === (e.art || 0));
        if (same) same.q += e.qty;
        else list.push({ n: e.card.n, s: set, a: e.art || 0, q: e.qty, img: e.set ? undefined : e.card.img });
      }
    });
    if (replace && r.name && !deck.name) deck.name = r.name;
    deck.emit();
    const n = r.unknown.length;
    toast(`Imported ${r.entries.reduce((a, e) => a + e.qty, 0)} cards${n ? `, ${n} line${n === 1 ? '' : 's'} not recognized` : ''}.`);
    dlg.close();
  });
  const preview = () => {
    const r = parseDeckText(ta.value);
    const n = r.entries.reduce((a, e) => a + e.qty, 0);
    info.innerHTML = ta.value.trim()
      ? `${n} cards recognized${r.unknown.length ? ` · <span style="color:var(--red)" title="${esc(r.unknown.join('\n'))}">${r.unknown.length} not recognized: ${esc(r.unknown.slice(0, 3).join(' · '))}${r.unknown.length > 3 ? '…' : ''}</span>` : ''}`
      : 'Section headers (Commander, Deck, Sideboard) and set codes are understood.';
    /** @type {HTMLButtonElement} */ (go).disabled = !n;
  };
  ta.addEventListener('input', preview);
  dlg.foot.append(button('Cancel', '', () => dlg.close()), go);
  preview();
  setTimeout(() => ta.focus(), 30);
}

export function openExport(deck) {
  const dlg = modal(`Export — ${deck.name || 'untitled deck'}`, { wide: true, peek: false, onEsc: () => dlg.close() });
  const styles = [['plain', 'Plain list'], ['sets', 'With set codes (Arena)'], ['forge', 'Forge .dck']];
  let style = 'plain';
  const seg = el(`<div class="seg">${styles.map(([k, l]) => `<button data-v="${k}" class="${k === style ? 'sel' : ''}">${esc(l)}</button>`).join('')}</div>`);
  const ta = /** @type {HTMLTextAreaElement} */ (el('<textarea class="text-input bd-import" readonly spellcheck="false"></textarea>'));
  const render = () => { ta.value = exportText(deck, /** @type {any} */ (style)); };
  seg.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => {
    style = b.dataset.v || 'plain';
    seg.querySelectorAll('button').forEach((x) => x.classList.toggle('sel', x === b));
    render();
  }));
  dlg.body.append(seg, ta);
  ta.style.marginTop = '10px';
  const copy = button('Copy', '', async () => {
    try { await navigator.clipboard.writeText(ta.value); toast('Copied to the clipboard.'); } catch { ta.select(); document.execCommand('copy'); toast('Copied.'); }
  });
  const download = button('Download', 'primary', () => {
    const blob = new Blob([ta.value], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${(deck.name || 'deck').replace(/[\\/:*?"<>|]/g, '_')}.${style === 'forge' ? 'dck' : 'txt'}`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });
  dlg.foot.append(button('Close', '', () => dlg.close()), copy, download);
  render();
}

// ---------------------------------------------------------------- basic lands

const BASICS = [['Plains', 'W'], ['Island', 'U'], ['Swamp', 'B'], ['Mountain', 'R'], ['Forest', 'G'], ['Wastes', 'C']];

export function openBasicLands(deck) {
  const dlg = modal('Basic lands', { narrow: true, peek: false, onEsc: () => dlg.close() });
  const F = deck.format;
  const counts = Object.fromEntries(BASICS.map(([n]) => [n, deck.sections.main.filter((e) => e.n === n).reduce((a, e) => a + e.q, 0)]));
  const rows = el('<div></div>');
  const note = el('<div class="count" style="margin:4px 0 10px"></div>');
  const render = () => {
    rows.replaceChildren(...BASICS.map(([n, c]) => {
      const r = el(`<div class="amount-row" style="grid-template-columns:34px 1fr auto;padding:6px 10px;margin-bottom:6px"><span class="ms ${c}" style="font-size:20px">${c === 'C' ? '◇' : ''}</span><b>${n}</b></div>`);
      const box = el(`<div class="stepper"><button class="btn small">−</button><span class="v">${counts[n]}</span><button class="btn small">+</button></div>`);
      const [minus, plus] = box.querySelectorAll('button');
      minus.addEventListener('click', () => { counts[n] = Math.max(0, counts[n] - 1); render(); });
      plus.addEventListener('click', () => { counts[n]++; render(); });
      r.appendChild(box);
      return r;
    }));
    const basics = Object.values(counts).reduce((a, b) => a + b, 0);
    const others = deck.count('main') - BASICS.reduce((a, [n]) => a + deck.sections.main.filter((e) => e.n === n).reduce((x, e) => x + e.q, 0), 0);
    const total = others + basics + (F.cmdr ? deck.count('commander') : 0);
    note.textContent = `${basics} basic lands · deck total ${total}${F.cmdr ? ` of ${F.size}` : ` (at least ${F.size})`}`;
  };
  /** fills the deck up to its size with basics, split by the colored mana symbols of the spells */
  const suggest = () => {
    const current = BASICS.reduce((a, [n]) => a + deck.sections.main.filter((e) => e.n === n).reduce((x, e) => x + e.q, 0), 0);
    const others = deck.count('main') - current;
    const target = Math.max(0, F.size - others - (F.cmdr ? deck.count('commander') : 0));
    const id = deck.identity();
    const pips = { W: 0, U: 0, B: 0, R: 0, G: 0 };
    for (const e of deck.sections.main) {
      const c = findCard(e.n);
      if (!c || c.tf & T.LAND) continue;
      const p = pipsOf(c.mc);
      for (const k of Object.keys(pips)) pips[k] += p[k] * e.q;
    }
    for (const e of deck.sections.commander) {
      const p = pipsOf(findCard(e.n)?.mc || '');
      for (const k of Object.keys(pips)) pips[k] += p[k] * 3; // the commander gets cast a lot
    }
    const bits = { W: 1, U: 2, B: 4, R: 8, G: 16 };
    const colors = Object.keys(pips).filter((k) => pips[k] > 0 && (id < 0 || (id & bits[k])));
    for (const [n] of BASICS) counts[n] = 0;
    if (!colors.length) { counts[id === 0 ? 'Wastes' : 'Plains'] = target; render(); return; }
    const sum = colors.reduce((a, k) => a + pips[k], 0);
    let left = target;
    const share = colors.map((k) => ({ k, exact: (target * pips[k]) / sum }));
    for (const s of share) { const v = Math.floor(s.exact); counts[BASICS.find((b) => b[1] === s.k)[0]] = v; left -= v; }
    share.sort((a, b) => (b.exact % 1) - (a.exact % 1));
    for (let i = 0; left > 0; i = (i + 1) % share.length, left--) counts[BASICS.find((b) => b[1] === share[i].k)[0]]++;
    render();
  };
  dlg.body.append(note, rows);
  dlg.foot.append(button('Suggest', 'ghost', suggest), el('<span class="count"></span>'), button('Cancel', '', () => dlg.close()), button('Apply', 'primary', () => {
    deck.change(() => {
      deck.sections.main = deck.sections.main.filter((e) => !BASICS.some(([n]) => n === e.n));
      for (const [n] of BASICS) {
        const c = findCard(n);
        if (c && counts[n] > 0) deck.sections.main.push({ n: c.n, s: c.s, a: 0, q: counts[n], img: c.img });
      }
    });
    dlg.close();
  }));
  render();
}

// ---------------------------------------------------------------- sample hand

export function openSampleHand(deck) {
  const dlg = modal('Sample hand', { wide: true, peek: false, onEsc: () => dlg.close() });
  const lib = [];
  const shuffle = () => {
    lib.length = 0;
    for (const e of deck.sections.main) for (let i = 0; i < e.q; i++) lib.push(e);
    for (let i = lib.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [lib[i], lib[j]] = [lib[j], lib[i]]; }
  };
  let drawn = 7;
  const grid = el('<div class="bd-hand"></div>');
  const info = el('<span class="count"></span>');
  const render = () => {
    const hand = lib.slice(0, drawn);
    grid.replaceChildren(...hand.map((e) => {
      const t = cardTile(findCard(e.n), { img: e.img });
      t.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:builder-detail', { detail: e.n })));
      return t;
    }));
    const lands = hand.filter((e) => (findCard(e.n)?.tf || 0) & T.LAND).length;
    info.textContent = `${hand.length} cards · ${lands} land${lands === 1 ? '' : 's'} · ${Math.max(0, lib.length - drawn)} left in the library`;
    /** @type {HTMLButtonElement} */ (draw).disabled = drawn >= lib.length;
  };
  const draw = button('Draw a card', '', () => { drawn++; render(); });
  dlg.body.appendChild(grid);
  dlg.foot.append(info, button('Close', '', () => dlg.close()), draw, button('New hand', 'primary', () => { shuffle(); drawn = 7; render(); }));
  shuffle();
  if (!lib.length) { grid.appendChild(el('<div class="note">The main deck is empty.</div>')); return; }
  render();
}
