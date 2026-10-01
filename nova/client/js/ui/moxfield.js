// @ts-check
// Moxfield sync: your Moxfield decks next to Forge's copies, synced with one click or automatically.
// The host does the work (Moxfield's API only answers pages of moxfield.com); this is its window and badge.

import { api, on } from '../net.js';
import { el, esc, pipsHtml, toast } from './text.js';
import { modal } from './dialogs.js';
import { deckArt } from './decks.js';

const FOLDERS = { commander: 'Commander', constructed: 'Constructed', brawl: 'Brawl', oathbreaker: 'Oathbreaker', tinyLeaders: 'Tiny Leaders' };
const MOX_FORMATS = { commander: 'Commander', pauperEdh: 'Pauper Commander', duel: 'Duel Commander', standardBrawl: 'Standard Brawl',
  brawl: 'Brawl', oathbreaker: 'Oathbreaker', predh: 'PreDH', none: 'No format', commanderPrecons: 'Commander precon' };
const fmtName = (f) => MOX_FORMATS[f] || (f ? f.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase()) : 'No format');

/** status → badge text, style and explanation */
const STATUS = {
  new: { t: 'New', cls: 'new', tip: 'Not in Forge yet.' },
  changed: { t: 'Changed on Moxfield', cls: 'chg', tip: 'Moxfield has a newer version, and Forge\'s copy wasn\'t edited since the last sync.' },
  different: { t: 'Differs', cls: 'chg', tip: 'Forge has a deck of this name with other cards (it came from somewhere else, or from an older sync). Syncing gives it Moxfield\'s cards.' },
  edited: { t: 'Edited in Forge', cls: 'edit', tip: 'You changed Forge\'s copy after the last sync, and Moxfield\'s didn\'t change. It\'s left alone unless you choose Moxfield\'s version.' },
  conflict: { t: 'Changed in both', cls: 'bad', tip: 'Both copies changed since the last sync. Syncing replaces the changes made in Forge with Moxfield\'s version.' },
  same: { t: 'Up to date', cls: 'ok', tip: 'Forge has the same cards.' },
  error: { t: 'Couldn\'t download', cls: 'bad', tip: '' },
};
/** order of the "To sync" tab */
const RANK = { conflict: 0, different: 1, changed: 2, new: 3, edited: 4, error: 5, same: 6 };
/** what "Sync all" (and the automatic sync, minus "different") takes care of */
const SAFE = new Set(['new', 'changed', 'different']);

/** @type {any} the host's Moxfield state: settings, the last report, the number of decks waiting */
let state = null;
/** @type {Set<(s:any) => void>} */
const listeners = new Set();
/** @type {((m:any) => void)|null} progress of a running check, for the open window */
let onProgress = null;

function setState(s) {
  state = s;
  listeners.forEach((fn) => { try { fn(s); } catch (e) { console.error(e); } });
}

/** Called whenever the Moxfield state changes (the lobby's badge). */
export function onMoxState(fn) { listeners.add(fn); return () => listeners.delete(fn); }

/** Moxfield decks waiting for a sync (new, changed, or differing from Forge's copy). */
export function moxPending() { return state?.pending || 0; }

/** Fetches the state; the host also catches up with Moxfield in the background when automatic sync is due. */
export async function moxAuto() {
  try { setState(await api('moxfield/auto', {})); } catch { /* the engine is still loading */ }
  return state;
}

/** Opens a Moxfield page in the user's own browser (where they are logged in), else in a new window. */
export function openExternal(url) {
  api('moxfield/open', { url }).catch(() => window.open(url, '_blank', 'noopener'));
}

/** Lists up to three names, then "+N more". */
function names(list) {
  const shown = list.slice(0, 3).map((n) => `"${n}"`).join(', ');
  return list.length > 3 ? `${shown} +${list.length - 3} more` : shown;
}

/** Tells the rest of the app which deck files a sync wrote (lobby lists, the deck builder). */
function decksChanged(results) {
  window.dispatchEvent(new CustomEvent('nova:decks-changed', { detail: results }));
}

function reportResults(results, auto = false) {
  const ok = results.filter((r) => !r.error);
  if (ok.length) {
    const added = ok.filter((r) => r.created).map((r) => r.name);
    const updated = ok.filter((r) => !r.created).map((r) => r.name);
    const what = [added.length ? `added ${names(added)}` : '', updated.length ? `updated ${names(updated)}` : ''].filter(Boolean).join(' · ');
    toast(`${auto ? 'Moxfield sync' : 'Moxfield'}: ${what}.`);
    decksChanged(ok);
  }
  for (const r of results.filter((x) => x.error)) toast(`Couldn't sync "${r.name}": ${r.error}`, 'error');
}

// an automatic sync finished (Nova started, or we came back to the menu after a while)
on('moxfield', (m) => {
  if (m.results) reportResults(m.results, true);
  api('moxfield').then(setState).catch(() => {});
});
on('moxProgress', (m) => onProgress?.(m));

function button(label, cls, fn, title = '') {
  const b = /** @type {HTMLButtonElement} */ (el(`<button class="btn ${cls}" ${title ? `title="${esc(title)}"` : ''}>${esc(label)}</button>`));
  b.addEventListener('click', fn);
  return b;
}

/** Resolves true when confirmed. */
function confirmBox(title, message, okLabel) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; d.close(); resolve(v); };
    const d = modal(title, { narrow: true, peek: false, onEsc: () => finish(false) });
    d.body.appendChild(el(`<div style="white-space:pre-wrap">${esc(message)}</div>`));
    const cancel = button('Cancel', '', () => finish(false));
    d.foot.append(cancel, button(okLabel, 'danger', () => finish(true)));
    setTimeout(() => cancel.focus(), 30);
  });
}

function ago(ms) {
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 45) return 'just now';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  return h < 24 ? `${h} h ago` : new Date(ms).toLocaleDateString();
}

const leaf = (path) => String(path || '').split('/').pop() || '';

const SECTIONS = { commander: 'Commander', main: 'Main deck', side: 'Sideboard', attractions: 'Attractions', contraptions: 'Contraptions', planes: 'Planes', schemes: 'Schemes' };

/** What syncing does to Forge's copy, section by section. */
function diffHtml(d) {
  const head = d.status === 'edited' || d.status === 'conflict'
    ? 'Using Moxfield\'s version makes these changes to Forge\'s copy (undoing the edits made in Forge):'
    : 'Syncing makes these changes to Forge\'s copy:';
  return `<div class="mox-diff-h">${esc(head)}</div><div class="mox-diff-cols">${(d.diff || []).map((s) => `<div class="mox-sec"><h5>${esc(SECTIONS[s.sec] || s.sec)}</h5>
    ${s.add.map(([n, q]) => `<div class="add">+ ${q} ${esc(n)}</div>`).join('')}
    ${s.rem.map(([n, q]) => `<div class="rem">− ${q} ${esc(n)}</div>`).join('')}
    ${s.qty.map(([n, a, b]) => `<div class="qty">${a} → ${b} ${esc(n)}</div>`).join('')}
    ${s.print.map(([n, a, b]) => `<div class="prt" title="Printing (set and collector number)">${esc(n)}: ${esc(a)} → ${esc(b)}</div>`).join('')}</div>`).join('')}</div>`;
}

const helpHtml = (open) => `<details class="mox-help" ${open ? 'open' : ''}><summary>Missing a deck?</summary>
  <p>Nova lists your <b>public</b> decks, including the ones Moxfield marks as not legal (a deck that isn't finished, a Commander deck that isn't exactly 100 cards…).</p>
  <p><b>Unlisted</b> decks aren't listed anywhere, so add them once with <b>Add decks by link…</b>; from then on they sync like the others.</p>
  <p><b>Private</b> decks can't be read by any other app. On Moxfield, set the deck to <i>Unlisted</i> (only people with the link can see it) and add its link.</p>
  <p>Syncing only copies Moxfield's cards into Forge; nothing is sent to Moxfield. Your deck notes and Forge-only settings stay.</p></details>`;

/** @type {{close: () => void}|null} the open window */
let win = null;

/**
 * The Moxfield window.
 * @param {{onOpenDeck?: (ref: {src: string, name: string}) => void}} [opts] opens a Forge deck in the deck builder
 */
export function openMoxfield(opts = {}) {
  if (win) return;
  let tab = '';
  /** what is running: '' | 'check' | 'sync' | 'link' */
  let busy = '';
  /** @type {{done:number, total:number}|null} */
  let prog = null;
  let error = '';
  let helpOpen = false;
  /** the saved user name last put in the name field (a name being typed isn't replaced) */
  let shownUser = null;
  /** decks whose changes are shown */
  const shown = new Set();
  /** @type {any} polls the host while an automatic sync it started is running */
  let poll = 0;

  const dlg = modal('Moxfield decks', { wide: true, peek: false, onEsc: () => close() });
  dlg.modal.classList.add('picker', 'mox');
  Object.assign(dlg.body.style, { padding: '0', display: 'flex', flexDirection: 'column', flex: '1', minHeight: '0' });
  const top = el(`<div class="mox-top">
      <div class="mox-r1">
        <input class="mox-user" placeholder="Your Moxfield user name" spellcheck="false" autocomplete="off" maxlength="100" title="The NAME in your profile link: moxfield.com/users/NAME">
        <button class="btn primary" data-check>Check Moxfield</button>
        <span class="mox-status"></span>
      </div>
      <div class="mox-r2">
        <label class="tgl" title="New decks and decks changed on Moxfield are copied into Forge when Nova starts and when you come back to the menu. Decks you edited in Forge are never overwritten automatically."><input type="checkbox" data-auto> Sync automatically</label>
        <span class="bd-sp"></span>
        <button class="btn ghost small" data-link title="Unlisted decks, or anyone's public deck">Add decks by link…</button>
        <button class="btn ghost small" data-force title="Download every deck again instead of only the ones Moxfield lists as changed">Re-download all</button>
      </div></div>`);
  const bar = el('<div class="picker-top mox-bar"><div class="tabs"></div><input placeholder="Search decks or commanders…"></div>');
  const list = el('<div class="mox-list"></div>');
  dlg.body.append(top, bar, list);
  const count = el('<span class="count"></span>');
  const syncAll = button('Sync all', 'primary', () => syncIds(safeIds()), 'Adds the new decks and updates the ones that changed on Moxfield. Decks you edited in Forge are left alone.');
  dlg.foot.append(count, button('Close', '', () => close()), syncAll);

  const q = (s) => /** @type {HTMLElement} */ (top.querySelector(s));
  const userIn = /** @type {HTMLInputElement} */ (q('.mox-user'));
  const autoCb = /** @type {HTMLInputElement} */ (q('[data-auto]'));
  const checkBtn = /** @type {HTMLButtonElement} */ (q('[data-check]'));
  const status = q('.mox-status');
  const search = /** @type {HTMLInputElement} */ (bar.querySelector('input'));
  const tabsEl = /** @type {HTMLElement} */ (bar.querySelector('.tabs'));

  const decks = () => /** @type {any[]} */ (state?.report?.decks || []);
  const safeIds = () => decks().filter((d) => SAFE.has(d.status)).map((d) => d.id);
  const running = () => !!busy || !!state?.running;

  const statusHtml = () => {
    const rep = state?.report;
    if (running()) {
      const what = busy === 'sync' ? 'Syncing…' : busy === 'link' ? 'Adding decks…' : 'Checking Moxfield…';
      return `<span class="spin"></span>${what}${prog && prog.total ? ` ${prog.done}/${prog.total} decks` : ''}`;
    }
    if (error || state?.error) return `<span class="err">${esc(error || state.error)}</span>`;
    if (!rep) return '';
    const n = rep.decks.length;
    return `${n} deck${n === 1 ? '' : 's'} on Moxfield · checked ${ago(rep.checked)}`;
  };

  const actionOf = (d) => {
    const folder = FOLDERS[d.target?.src] || 'Forge';
    switch (d.status) {
      case 'new': return { t: 'Add to Forge', cls: '', tip: `Adds it to your ${folder} decks as "${leaf(d.target?.name)}"` };
      case 'changed': return { t: d.add || d.rem || d.print ? 'Update' : 'Rename', cls: '', tip: 'Copies Moxfield\'s version into Forge' };
      case 'different': return { t: 'Update', cls: '', tip: 'Gives Forge\'s deck of this name Moxfield\'s cards (its notes and settings stay)' };
      case 'edited': case 'conflict': return { t: 'Use Moxfield\'s', cls: 'danger', tip: 'Replaces the changes made in Forge with Moxfield\'s version' };
      default: return null;
    }
  };

  const rowHtml = (d) => {
    const S = STATUS[d.status] || { t: d.status, cls: '', tip: '' };
    const art = d.info ? deckArt(d.info) : '';
    const bits = [esc(fmtName(d.format))];
    if (d.info?.colors !== undefined) bits.push(`<span class="pips">${pipsHtml(d.info.colors)}</span>`);
    if (d.cards) bits.push(`${d.cards} card${d.cards === 1 ? '' : 's'}`);
    if (d.author) bits.push(`by ${esc(d.author)}`);
    const ch = [];
    if (d.status !== 'new' && d.status !== 'same') {
      if (d.add || d.rem) ch.push(`+${d.add || 0} −${d.rem || 0} cards`);
      if (d.print) ch.push(`${d.print} printing${d.print === 1 ? '' : 's'}`);
      if (d.rename) ch.push(`renamed to "${esc(d.rename)}"`);
    }
    if (ch.length) bits.push(`<b class="ch">${ch.join(' · ')}</b>`);
    if (d.status === 'new') bits.push(`→ ${esc(FOLDERS[d.target?.src] || '')} decks`);
    else if (d.forge && leaf(d.forge.name) !== d.name) bits.push(`in Forge: ${esc(leaf(d.forge.name))}${d.forge.src !== 'commander' ? ` (${esc(FOLDERS[d.forge.src] || d.forge.src)})` : ''}`);
    const act = actionOf(d);
    const cmdrs = (d.info?.cmdrs || []).map((c) => c.n).join(' + ');
    return `<div class="mox-row" data-id="${esc(d.id)}">
      <div class="art">${art ? `<img loading="lazy" src="${art}" alt="">` : ''}</div>
      <div class="mid">
        <div class="l1"><span class="n" title="${esc(d.name + (cmdrs ? ` — ${cmdrs}` : ''))}">${esc(d.name)}</span><span class="mox-st ${S.cls}" title="${esc(S.tip)}">${esc(S.t)}</span>${d.extra ? '<span class="mox-tag" title="Added by link">link</span>' : ''}</div>
        <div class="l2">${bits.join(' · ')}</div>
        ${d.unknown ? `<div class="warn">Forge doesn't know ${d.unknown.length === 1 ? 'this card, so it\'s left out' : `these ${d.unknown.length} cards, so they're left out`}: ${esc(d.unknown.join(', '))}</div>` : ''}
        ${d.demoted ? `<div class="warn">Forge can't use ${esc(d.demoted.join(' + '))} as ${d.demoted.length === 1 ? 'a commander, so it goes' : 'commanders, so they go'} in the main deck.</div>` : ''}
        ${d.error ? `<div class="warn">${esc(d.error)}</div>` : ''}
        ${shown.has(d.id) && d.diff?.length ? `<div class="mox-diff">${diffHtml(d)}</div>` : ''}
      </div>
      <div class="acts">
        ${d.diff?.length ? `<button class="btn ghost small" data-act="diff">${shown.has(d.id) ? 'Hide changes' : 'Changes'}</button>` : ''}
        ${d.forge && opts.onOpenDeck && (d.status === 'same' || d.status === 'edited') ? '<button class="btn ghost small" data-act="edit" title="Open Forge\'s copy in the Deck Builder">Edit</button>' : ''}
        ${act ? `<button class="btn small ${act.cls}" data-act="sync" title="${esc(act.tip)}" ${running() ? 'disabled' : ''}>${esc(act.t)}</button>` : ''}
        <a class="btn ghost small" href="${esc(d.url)}" data-act="web" title="Open on Moxfield">↗</a>
        ${d.extra ? '<button class="btn ghost small" data-act="unlink" title="Stop following this deck (Forge\'s copy stays)">✕</button>' : ''}
      </div>
    </div>`;
  };

  const goneHtml = (g) => `<div class="mox-row" data-gone="${esc(g.id)}">
      <div class="art"></div>
      <div class="mid"><div class="l1"><span class="n">${esc(leaf(g.name))}</span><span class="mox-st bad">Not on Moxfield</span></div>
        <div class="l2">${esc(FOLDERS[g.src] || g.src)} · its Moxfield deck was deleted or made private. Forge's copy stays as it is.</div></div>
      <div class="acts">${opts.onOpenDeck ? '<button class="btn ghost small" data-act="edit" title="Open it in the Deck Builder">Edit</button>' : ''}
        <a class="btn ghost small" href="${esc(g.url)}" data-act="web" title="Open on Moxfield">↗</a>
        <button class="btn ghost small" data-act="hide" title="Stop listing it here">Hide</button></div></div>`;

  const introHtml = () => `<div class="mox-intro">
      <h3>Your Moxfield decks in Forge</h3>
      <ol>
        <li>Type your Moxfield user name (the <b>NAME</b> in <code>moxfield.com/users/NAME</code>) and click <b>Check Moxfield</b>.</li>
        <li>Your decks show up next to Forge's copies. <b>Sync all</b> adds the new ones and updates the ones you changed on Moxfield.</li>
        <li>With <b>Sync automatically</b> on, Nova does that by itself when it starts and whenever you come back to the menu.</li>
      </ol>${helpHtml(helpOpen)}</div>`;

  const render = () => {
    const rep = state?.report || null;
    if (state?.running && !busy && !poll) {
      poll = setTimeout(() => { poll = 0; api('moxfield').then(setState).catch(() => {}); }, 1500);
    }
    if (state && state.user !== shownUser) {
      shownUser = state.user;
      userIn.value = state.user || '';
    }
    autoCb.checked = !!state?.auto;
    checkBtn.disabled = running();
    checkBtn.textContent = rep ? 'Check again' : 'Check Moxfield';
    /** @type {HTMLButtonElement} */ (q('[data-force]')).disabled = running() || !rep;
    /** @type {HTMLButtonElement} */ (q('[data-link]')).disabled = running();
    status.innerHTML = statusHtml();
    const all = decks();
    const gone = /** @type {any[]} */ (rep?.gone || []);
    const todo = all.filter((d) => d.status !== 'same');
    const tabs = [['todo', 'To sync', todo.length], ['all', 'All', all.length], ['same', 'Up to date', all.length - todo.length]];
    if (gone.length) tabs.push(['gone', 'Not on Moxfield', gone.length]);
    if (!rep) tab = '';
    else if (!tabs.some((t) => t[0] === tab)) tab = todo.length ? 'todo' : 'all';
    tabsEl.innerHTML = rep ? tabs.map(([k, l, n]) => `<button data-tab="${k}" class="${k === tab ? 'sel' : ''}">${esc(l)} <small>${n}</small></button>`).join('') : '';
    /** @type {HTMLElement} */ (bar).style.display = rep ? '' : 'none';
    const n = safeIds().length;
    syncAll.textContent = n ? `Sync all (${n})` : 'Sync all';
    syncAll.disabled = !n || running();
    const edited = all.filter((d) => d.status === 'edited' || d.status === 'conflict').length;
    count.textContent = edited ? `${edited} deck${edited === 1 ? '' : 's'} edited in Forge ${edited === 1 ? 'is' : 'are'} left alone` : '';
    if (!rep) {
      list.innerHTML = state?.user && running() ? '<div class="note mox-wait">Checking your decks on Moxfield…</div>' : introHtml();
      return;
    }
    const qs = search.value.trim().toLowerCase();
    const hit = (d) => !qs || (d.name + ' ' + (d.info?.cmdrs || []).map((c) => c.n).join(' ')).toLowerCase().includes(qs);
    let html;
    if (tab === 'gone') {
      html = gone.filter((g) => !qs || g.name.toLowerCase().includes(qs)).map(goneHtml).join('');
    } else {
      const rows = (tab === 'todo' ? todo : tab === 'same' ? all.filter((d) => d.status === 'same') : all).filter(hit);
      rows.sort((a, b) => (tab === 'todo' ? RANK[a.status] - RANK[b.status] : 0) || a.name.localeCompare(b.name));
      html = rows.map(rowHtml).join('');
      if (!rows.length) {
        html = `<div class="note mox-empty">${qs ? 'No decks match.' : tab === 'todo' ? 'Everything is in sync.' : all.length ? 'No decks here.'
          : `No public decks found for "${esc(state.user)}". Check the name, or see "Missing a deck?" below.`}</div>`;
      }
    }
    const scroll = list.scrollTop;
    list.innerHTML = html + helpHtml(helpOpen);
    list.scrollTop = scroll;
  };

  const run = async (what, fn) => {
    if (running()) return;
    busy = what;
    error = '';
    prog = null;
    render();
    try {
      await fn();
    } catch (e) {
      error = /** @type {Error} */ (e).message;
      if (what !== 'check') toast(error, 'error');
    } finally {
      busy = '';
      prog = null;
      render();
    }
  };

  const check = (force = false) => {
    const user = userIn.value.trim();
    return run('check', async () => {
      if (!user && !state?.extra?.length) {
        userIn.focus();
        throw new Error('Type your Moxfield user name first.');
      }
      setState(await api('moxfield/check', { user, force }));
    });
  };

  const syncIds = (ids) => {
    if (!ids.length) return;
    run('sync', async () => {
      const r = await api('moxfield/apply', { ids });
      setState(r.state);
      reportResults(r.results || []);
    });
  };

  const linkDialog = () => {
    const d = modal('Add Moxfield decks by link', { peek: false, onEsc: () => d.close() });
    const ta = /** @type {HTMLTextAreaElement} */ (el(`<textarea class="text-input mox-links" spellcheck="false" placeholder="https://moxfield.com/decks/…&#10;One or more links"></textarea>`));
    d.body.append(el('<div class="note mox-links-note">For unlisted decks, or anyone\'s public deck. From then on they sync like your own. A private deck can\'t be read: set it to Unlisted on Moxfield first.</div>'), ta);
    const ok = button('Add', 'primary', () => {
      const text = ta.value.trim();
      if (!text) { ta.focus(); return; }
      d.close();
      run('link', async () => {
        const r = await api('moxfield/link', { text });
        setState(r.state);
        tab = 'todo';
        const missing = r.missing?.length ? ` ${r.missing.length} can't be read (private or deleted).` : '';
        toast(`Added ${r.added} deck${r.added === 1 ? '' : 's'}.${missing}`, missing ? 'error' : '');
      });
    });
    d.foot.append(button('Cancel', '', () => d.close()), ok);
    setTimeout(() => ta.focus(), 30);
  };

  checkBtn.addEventListener('click', () => check());
  q('[data-force]').addEventListener('click', () => check(true));
  q('[data-link]').addEventListener('click', linkDialog);
  userIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') check(); });
  autoCb.addEventListener('change', () => api('moxfield/settings', { auto: autoCb.checked }).then(setState).catch((e) => toast(e.message, 'error')));
  search.addEventListener('input', render);
  // 'toggle' doesn't bubble: listen while it passes down
  list.addEventListener('toggle', (e) => {
    const t = /** @type {HTMLElement} */ (e.target);
    if (t.classList?.contains('mox-help')) helpOpen = /** @type {HTMLDetailsElement} */ (t).open;
  }, true);
  tabsEl.addEventListener('click', (e) => {
    const b = /** @type {HTMLElement} */ (e.target).closest('[data-tab]');
    if (b) { tab = /** @type {HTMLElement} */ (b).dataset.tab || tab; list.scrollTop = 0; render(); }
  });
  list.addEventListener('click', async (e) => {
    const t = /** @type {HTMLElement} */ (e.target);
    const act = /** @type {HTMLElement|null} */ (t.closest('[data-act]'));
    const row = /** @type {HTMLElement|null} */ (t.closest('.mox-row'));
    if (!act || !row) return;
    const kind = act.dataset.act;
    if (kind === 'web') {
      e.preventDefault();
      openExternal(/** @type {HTMLAnchorElement} */ (act).href);
      return;
    }
    if (row.dataset.gone) {
      const g = (state?.report?.gone || []).find((x) => x.id === row.dataset.gone);
      if (!g) return;
      if (kind === 'edit') { close(); opts.onOpenDeck?.({ src: g.src, name: g.name }); }
      if (kind === 'hide') api('moxfield/hide', { id: g.id }).then(setState).catch((x) => toast(x.message, 'error'));
      return;
    }
    const d = decks().find((x) => x.id === row.dataset.id);
    if (!d) return;
    if (kind === 'diff') {
      if (shown.has(d.id)) shown.delete(d.id); else shown.add(d.id);
      render();
    } else if (kind === 'edit' && d.forge) {
      close();
      opts.onOpenDeck?.({ src: d.forge.src, name: d.forge.name });
    } else if (kind === 'sync') {
      if ((d.status === 'edited' || d.status === 'conflict') && !await confirmBox(`Use Moxfield's version of "${d.name}"?`,
        'The changes made to Forge\'s copy since the last sync are replaced by Moxfield\'s version. Its deck notes stay.', 'Use Moxfield\'s')) return;
      syncIds([d.id]);
    } else if (kind === 'unlink') {
      if (!await confirmBox(`Stop following "${d.name}"?`, 'It won\'t be synced anymore. Forge\'s copy stays.', 'Stop following')) return;
      api('moxfield/unlink', { id: d.id }).then(setState).catch((x) => toast(x.message, 'error'));
    }
  });

  const unsub = onMoxState(() => render());
  onProgress = (m) => { prog = m; status.innerHTML = statusHtml(); };
  // re-render every half minute so "checked 2 min ago" stays true
  const timer = setInterval(() => { if (!running()) status.innerHTML = statusHtml(); }, 30000);
  const close = () => {
    unsub();
    onProgress = null;
    clearInterval(timer);
    clearTimeout(poll);
    dlg.close();
    win = null;
  };
  win = { close };

  render();
  api('moxfield').then((s) => {
    setState(s);
    if (s.user && !s.report && !s.running) check(); // first look since Nova started
    else if (!s.user) setTimeout(() => userIn.focus(), 30);
  }).catch((e) => { error = e.message; render(); });
}
