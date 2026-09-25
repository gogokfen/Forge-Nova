// @ts-check
// Formats, generated-deck choices and the deck picker window, shared by the lobby and online rooms.

import { imgUrl } from '../net.js';
import { esc, el, pipsHtml } from './text.js';
import { modal } from './dialogs.js';

/** Forge's formats. `life` is Forge's own default for the given number of players. */
export const FORMATS = [
  { id: 'commander', t: 'Commander', d: '100-card singleton decks led by a commander · 21 commander damage kills', cmdr: true, life: () => 40 },
  { id: 'constructed', t: 'Constructed', d: 'Regular 60-card decks', cmdr: false, life: () => 20 },
  { id: 'brawl', t: 'Brawl', d: 'Standard-legal commander variant', cmdr: true, life: (n) => (n === 2 ? 25 : 30) },
  { id: 'oathbreaker', t: 'Oathbreaker', d: 'A planeswalker leads, with a signature spell', cmdr: true, life: () => 20 },
  { id: 'tinyLeaders', t: 'Tiny Leaders', d: '50-card decks, every card costs 3 or less', cmdr: true, life: () => 25 },
];

export const formatOf = (id) => FORMATS.find((f) => f.id === id) || FORMATS[0];

export const GEN_CMDR = [
  { src: 'gen', name: 'randomCommander', label: 'Random generated commander deck' },
  { src: 'gen', name: 'randomCommanderPrecon', label: 'Random commander precon' },
  { src: 'gen', name: 'randomUser', label: 'Random pick from my commander decks' },
];
export const GEN_CONS = [
  { src: 'gen', name: 'randomColors', label: 'Random 2-color deck' },
  { src: 'gen', name: 'theme', label: 'Random theme deck' },
  { src: 'gen', name: 'randomPrecon', label: 'Random preconstructed deck' },
  { src: 'gen', name: 'randomUser', label: 'Random pick from my decks' },
];

/** Art for a deck tile: its commander, else its most expensive spell. */
export function deckArt(d) {
  if (!d) return '';
  const key = d.cmdrs?.[0]?.img || d.art;
  return key ? imgUrl(key) : '';
}

/** Whether a listed deck suits a format (commander formats need a commander). */
export function deckFits(d, cmdr) {
  if (!d) return false;
  if (d.src === 'gen') return (cmdr ? GEN_CMDR : GEN_CONS).some((g) => g.name === d.name);
  return cmdr ? !!d.cmdrs?.length : !d.cmdrs?.length || d.src === 'constructed';
}

/**
 * The deck picker: tabs of deck tiles with a search box.
 * @param {{title: string, initial?: string, tools?: HTMLElement[], onPick: (d:any) => void,
 *   tabs: {key: string, label: string, list: () => any[], empty?: string}[]}} opts
 * @returns {{close: () => void, render: () => void}}
 */
export function pickDeckDialog(opts) {
  const dlg = modal(opts.title, { wide: true, peek: false, onEsc: () => dlg.close() });
  dlg.modal.classList.add('picker');
  let tab = opts.initial || opts.tabs[0].key;
  const top = el(`<div class="picker-top"><div class="tabs">${opts.tabs.map((t) => `<button data-tab="${t.key}">${esc(t.label)}</button>`).join('')}</div><input placeholder="Search decks or commanders…"></div>`);
  for (const t of opts.tools || []) top.appendChild(t);
  const grid = el('<div class="deck-grid"></div>');
  dlg.body.style.padding = '0';
  dlg.body.style.display = 'flex';
  dlg.body.style.flexDirection = 'column';
  dlg.body.style.flex = '1';
  dlg.body.append(top, grid);
  dlg.foot.append(Object.assign(el('<button class="btn">Cancel</button>'), { onclick: dlg.close }));
  const input = /** @type {HTMLInputElement} */ (top.querySelector('input'));
  const render = () => {
    top.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('sel', /** @type {HTMLElement} */ (b).dataset.tab === tab));
    const t = opts.tabs.find((x) => x.key === tab) || opts.tabs[0];
    const q = input.value.trim().toLowerCase();
    let list = t.list();
    if (q) list = list.filter((d) => (d.name + ' ' + (d.label || '') + ' ' + (d.cmdrs || []).map((c) => c.n).join(' ')).toLowerCase().includes(q));
    const shown = list.slice(0, 400);
    grid.replaceChildren(...shown.map((d) => {
      const art = deckArt(d);
      const tile = el(`<div class="deck-tile"><div class="img">${art ? `<img loading="lazy" src="${art}" style="width:100%;height:100%;object-fit:cover;object-position:50% 18%">` : ''}</div>
        <div class="meta"><div class="n" title="${esc(d.label || d.name)}">${esc(d.label || d.name)}</div>
        <div class="s"><span>${esc(d.cmdrs?.map((c) => c.n.split(',')[0]).join(' + ') || d.sub || (d.src === 'gen' ? 'generated' : d.src))}</span><span class="pips">${d.colors !== undefined ? pipsHtml(d.colors) : ''}</span></div></div></div>`);
      tile.addEventListener('click', () => { dlg.close(); opts.onPick(d); });
      return tile;
    }));
    if (!shown.length) grid.appendChild(el(`<div class="note">${esc(t.empty || 'No decks here.')}</div>`));
  };
  top.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => { tab = /** @type {HTMLElement} */ (b).dataset.tab || tab; render(); }));
  input.addEventListener('input', render);
  render();
  setTimeout(() => input.focus(), 30);
  return { close: dlg.close, render };
}
