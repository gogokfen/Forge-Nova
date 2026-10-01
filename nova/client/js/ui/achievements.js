// @ts-check
// Classic Forge's Achievements screen: a trophy case per collection (Constructed, Draft, Quest, planeswalker
// ultimates, challenges…), drawn from Forge's own skin (sprite_trophies.png: trophies, plates, shelves). A trophy is
// bronze, silver, gold or mythic by the tier earned (special achievements have their own); one not earned yet is
// faint. Point at a trophy for what it takes.

import { api, imgUrl } from '../net.js';
import { el, esc, toast } from './text.js';
import { modal } from './dialogs.js';

const SPRITE = '/res/skins/default/sprite_trophies.png';
/** x of each trophy in the sprite (135 × 185 each) */
const TROPHY_X = { common: 0, uncommon: 135, rare: 270, mythic: 405, special: 540 };
const TIER_NAMES = { common: 'Common', uncommon: 'Uncommon', rare: 'Rare', mythic: 'Mythic', special: 'Special' };
const PER_SHELF = 4;
const MIN_SHELVES = 3;

/** @type {{close: () => void} | null} */
let current = null;

export function openAchievements() {
  if (current) return;
  const dlg = modal('Achievements', { wide: true, peek: false, onEsc: () => close() });
  dlg.modal.classList.add('achv-dlg');
  const close = () => { dlg.close(); current = null; };
  current = { close };
  dlg.foot.append(el('<span class="count">Earned in games against the AI and in Forge\'s other modes; online games with friends don\'t count.</span>'),
    Object.assign(el('<button class="btn primary">Close</button>'), { onclick: close }));
  dlg.body.innerHTML = '<div class="note">Loading achievements…</div>';
  api('achievements').then((data) => render(dlg.body, data)).catch((e) => {
    dlg.body.innerHTML = `<div class="note">Couldn't load the achievements: ${esc(e.message)}</div>`;
    toast(e.message, 'error');
  });
}

function earnedCount(set) {
  return set.items.filter((a) => a.tier).length;
}

function render(body, data) {
  const sets = (data.sets || []).filter((s) => s.items?.length);
  if (!sets.length) {
    body.innerHTML = '<div class="note">No achievements found.</div>';
    return;
  }
  const total = sets.reduce((n, s) => n + s.items.length, 0);
  const earned = sets.reduce((n, s) => n + earnedCount(s), 0);
  body.replaceChildren();
  const layout = el(`<div class="achv">
    <div class="achv-nav"><div class="achv-total"><b>${earned}</b> of ${total} earned</div></div>
    <div class="achv-main"><div class="achv-case"></div><div class="achv-detail empty">Point at a trophy to see what it takes.</div></div></div>`);
  body.appendChild(layout);
  const nav = /** @type {HTMLElement} */ (layout.querySelector('.achv-nav'));
  const caseEl = /** @type {HTMLElement} */ (layout.querySelector('.achv-case'));
  const detail = /** @type {HTMLElement} */ (layout.querySelector('.achv-detail'));
  let pick = 0;
  try { pick = Math.max(0, sets.findIndex((s) => s.name === localStorage.getItem('nova-achv-tab'))); } catch { /* storage unavailable */ }
  const tabs = sets.map((s, i) => {
    const b = el(`<button class="achv-tab"><span>${esc(s.name)}</span><small>${earnedCount(s)}/${s.items.length}</small></button>`);
    b.addEventListener('click', () => show(i));
    nav.appendChild(b);
    return b;
  });
  const show = (i) => {
    pick = i;
    try { localStorage.setItem('nova-achv-tab', sets[i].name); } catch { /* storage unavailable */ }
    tabs.forEach((t, k) => t.classList.toggle('on', k === i));
    drawCase(caseEl, sets[i], detail);
    detail.className = 'achv-detail empty';
    detail.textContent = 'Point at a trophy to see what it takes.';
  };
  show(pick);
}

/** The case: a top, then shelves of four trophies, each on a name plate. */
function drawCase(caseEl, set, detail) {
  const shelves = Math.max(MIN_SHELVES, Math.ceil(set.items.length / PER_SHELF));
  const parts = [el(`<div class="achv-top" style="background-image:url(${SPRITE})"></div>`)];
  for (let s = 0; s < shelves; s++) {
    const shelf = el(`<div class="achv-shelf" style="background-image:url(${SPRITE})"></div>`);
    for (const a of set.items.slice(s * PER_SHELF, (s + 1) * PER_SHELF)) shelf.appendChild(trophy(a, detail));
    parts.push(shelf);
  }
  caseEl.replaceChildren(...parts);
  caseEl.scrollTop = 0;
}

function trophy(a, detail) {
  const tier = a.tier || (a.special ? 'special' : 'common');
  const t = el(`<div class="achv-slot ${a.tier ? 'earned' : 'locked'}" tabindex="0">
    <div class="achv-trophy" style="background-image:url(${SPRITE});background-position:-${TROPHY_X[tier]}px 0"><div class="achv-art"></div></div>
    <div class="achv-plate" style="background-image:url(${SPRITE})"><b>${esc(a.name)}</b>${a.sub ? `<small>${esc(String(a.sub).replace(/\s*\(.*\)$/, ''))}</small>` : ''}</div></div>`);
  const art = /** @type {HTMLElement} */ (t.querySelector('.achv-art'));
  // the achievement's picture (Forge's download), else the card it is about
  const probe = new Image();
  probe.onload = () => { art.style.backgroundImage = `url("${probe.src}")`; art.classList.add('pic'); };
  probe.onerror = () => {
    if (a.card) { art.style.backgroundImage = `url("${imgUrl(a.card)}")`; art.classList.add('card'); }
  };
  probe.src = `/achv?key=${encodeURIComponent(a.key)}`;
  const showDetail = () => {
    document.querySelectorAll('.achv-slot.sel').forEach((n) => n.classList.remove('sel'));
    t.classList.add('sel');
    detail.className = 'achv-detail';
    detail.innerHTML = `<div class="achv-d-name">${esc(a.name)}${a.tier ? ` <span class="achv-badge ${a.tier}">${TIER_NAMES[a.tier]}</span>` : ' <span class="achv-badge none">Not earned yet</span>'}</div>
      ${a.sub ? `<div class="achv-d-sub">${esc(a.sub)}</div>` : ''}
      ${a.shared ? `<div class="achv-d-shared">${esc(a.shared)}${a.special ? '' : '…'}</div>` : ''}
      <div class="achv-d-tiers">${(a.tiers || []).map((x) => `<div class="${x.on ? 'on' : ''}"><span class="achv-dot ${x.t}"></span>${a.special ? '' : `<b>${TIER_NAMES[x.t]}</b> `}${esc(x.d)}</div>`).join('')}</div>`;
  };
  t.addEventListener('mouseenter', showDetail);
  t.addEventListener('focus', showDetail);
  t.addEventListener('click', showDetail);
  return t;
}
