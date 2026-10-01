// @ts-check
// Deck Builder screen: the card catalog, the deck being built, card details, statistics and checks.

import { api } from '../net.js';
import { el, esc, manaHtml, toast } from '../ui/text.js';
import { modal } from '../ui/dialogs.js';
import { catalog, loadCatalog, filterCards, sortCards, rankByName, SORTS, T, CMD, isLegal, findCard } from './catalog.js';
import { DeckModel, FORMATS, checkDeck, deckStats, groupOf, TYPE_GROUPS } from './deck.js';
import { openDeckBrowser, openImport, openExport, openBasicLands, openSampleHand, confirmBox, choose, askName, cardTile } from './dialogs.js';
import { openExternal } from '../ui/moxfield.js';

const UI_KEY = 'nova-builder-ui';
const DRAFT_KEY = 'nova-builder-draft';
const CARD_RATIO = 0.716;
const LIST_ROW = 30;
const IMG_DELAY = 120; // ms without scrolling before catalog images are requested
// catalog images requested at once: uncached ones are downloaded by the host, and the browser allows only
// six connections to it, so this keeps room for everything else (deck checks, printings, the details panel)
const IMG_PARALLEL = 4;
const COLOR_NAMES = { W: 'White', U: 'Blue', B: 'Black', R: 'Red', G: 'Green', C: 'Colorless' };
const TYPE_CHIPS = [['creature', 'Creature'], ['planeswalker', 'Planeswalker'], ['instant', 'Instant'], ['sorcery', 'Sorcery'],
  ['artifact', 'Artifact'], ['enchantment', 'Enchantment'], ['land', 'Land'], ['battle', 'Battle']];
const GROUPINGS = [['type', 'Type'], ['mv', 'Mana value'], ['color', 'Color'], ['none', 'None']];

function loadUi() {
  const def = { view: 'grid', sort: 'name', desc: false, searchIn: 'nametype', size: 150, deckView: 'list', group: 'type', identity: true, hideAiBad: false, paperOnly: true, tab: 'card' };
  try { return { ...def, ...JSON.parse(localStorage.getItem(UI_KEY) || '{}') }; } catch { return def; }
}

function emptyFilter() {
  return { q: '', colors: [], colorMode: 'any', multi: false, types: [], mv: [], format: -1, rarity: [], set: '' };
}

function isTextField(t) {
  if (!(t instanceof HTMLElement)) return false;
  if (t.tagName === 'TEXTAREA' || t.tagName === 'SELECT') return true;
  return t.tagName === 'INPUT' && !/^(range|checkbox|radio|button)$/i.test(/** @type {HTMLInputElement} */ (t).type);
}

function colorGroup(c) {
  if (!c) return 'Other';
  if (c.tf & T.LAND && !(c.tf & T.CREATURE)) return 'Lands';
  if (c.nc > 1) return 'Multicolor';
  if (c.nc === 0) return 'Colorless';
  return COLOR_NAMES[Object.entries({ W: 1, U: 2, B: 4, R: 8, G: 16 }).find(([, b]) => b === c.c)?.[0] || 'C'];
}

const GROUP_ORDER = {
  type: [...TYPE_GROUPS.map(([g]) => g), 'Other'],
  color: ['White', 'Blue', 'Black', 'Red', 'Green', 'Multicolor', 'Colorless', 'Lands', 'Other'],
  mv: ['Mana value 0', 'Mana value 1', 'Mana value 2', 'Mana value 3', 'Mana value 4', 'Mana value 5', 'Mana value 6', 'Mana value 7+', 'Lands', 'Other'],
  none: ['Main deck'],
};

export class Builder {
  /** @param {HTMLElement} root */
  constructor(root, { onBack }) {
    this.root = root;
    this.onBack = onBack;
    this.deck = new DeckModel();
    this.ui = loadUi();
    this.f = emptyFilter();
    this.commandersOnly = false;
    /** @type {any[]} */ this.results = [];
    /** @type {Map<string, HTMLElement>} */ this.cells = new Map();
    /** @type {Set<HTMLImageElement>} catalog images being downloaded */ this.imgBusy = new Set();
    this.cellGeo = ''; // view, width and card size the cells were placed for
    this.scrolledAt = 0;
    /** @type {any} the card a pending hover will show */ this.hoverTarget = null;
    this.visible = false;
    this.mounted = false;
    this.restored = false;
    /** @type {{card:any, entry?:any, section?:string}|null} */
    this.focus = null;
    /** @type {any} a printing picked in the card panel, used when adding that card */
    this.addPrinting = null;
    this.checks = checkDeck(this.deck);
    this.forge = { problem: null, legal: [], pending: false, error: '' };
    this.checkSeq = 0;
    /** @type {Map<string, Promise<any[]>>} */ this.printCache = new Map();
    this.decksChanged = false;
    this.lastIdentity = -2;
    this.lastSrc = '';
    /** @type {any} */ this.el = {};
    this.deck.onChange(() => this.afterChange());
    window.addEventListener('keydown', (e) => this.onKey(e));
    window.addEventListener('resize', () => { if (this.visible) this.renderResults(); });
    window.addEventListener('nova:builder-detail', (e) => {
      const c = findCard(/** @type {CustomEvent} */ (e).detail);
      if (c) this.showCard(c);
    });
  }

  // ================================================================= lifecycle

  /** @param {{src:string, name:string}|null} open a deck to open, or null to continue where you left off */
  async show(open = null) {
    this.visible = true;
    this.root.classList.remove('hidden');
    if (!this.mounted) this.mount();
    if (!catalog.ready) {
      try {
        await loadCatalog();
      } catch (e) {
        this.el.cat.innerHTML = `<div class="bd-loading">Could not load the card database: ${esc(e.message)}</div>`;
        return;
      }
      this.buildCatalogPanel();
    }
    if (!this.restored) {
      this.restored = true;
      try {
        const draft = localStorage.getItem(DRAFT_KEY);
        if (draft) this.deck.fromDraft(draft);
      } catch { /* no usable draft */ }
    }
    if (open) await this.openDeck(open);
    this.el.name.value = this.deck.name;
    this.afterChange();
    this.refreshResults(true);
  }

  hide() {
    this.visible = false;
    this.root.classList.add('hidden');
    this.closeMenu();
  }

  mount() {
    this.mounted = true;
    this.root.innerHTML = `
      <div class="bd-top">
        <div class="bd-title"><span class="logo-icon"></span>Deck Builder</div>
        <input class="bd-name" data-name placeholder="Untitled deck" maxlength="120" spellcheck="false" title="Deck name">
        <select class="bd-fmt" data-fmt title="Format">${Object.entries(FORMATS).map(([k, f]) => `<option value="${k}">${esc(f.t)}</option>`).join('')}</select>
        <button class="bd-status" data-status title="Deck checks"></button>
        <button class="btn ghost small hidden" data-moxlink title="This deck is synced from Moxfield: edit it there, and the sync brings the changes here">Moxfield ↗</button>
        <span class="bd-sp"></span>
        <button class="btn ghost small" data-undo title="Undo (Ctrl+Z)">↶</button>
        <button class="btn ghost small" data-redo title="Redo (Ctrl+Y)">↷</button>
        <button class="btn small" data-new>New</button>
        <button class="btn small" data-open>Open…</button>
        <button class="btn small" data-import>Import…</button>
        <button class="btn small" data-export>Export…</button>
        <button class="btn small" data-hand>Sample hand</button>
        <button class="btn small" data-saveas>Save as…</button>
        <button class="btn primary small" data-save title="Save (Ctrl+S)">Save</button>
        <button class="btn ghost small" data-back>Back to menu</button>
      </div>
      <div class="bd-main">
        <section class="bd-panel bd-cat"><div class="bd-loading"><div class="boot-bar"><div></div></div>Loading Forge's card database…</div></section>
        <section class="bd-panel bd-deck">
          <div class="dk-head"><div class="dk-count" data-dcount></div><span class="bd-sp"></span>
            <select data-group title="Group the deck by">${GROUPINGS.map(([k, l]) => `<option value="${k}">${l}</option>`).join('')}</select>
            <div class="seg" data-dview><button data-v="list" title="List">☰</button><button data-v="images" title="Card images">▦</button></div>
            <button class="btn small" data-basics title="Add or adjust basic lands">Basic lands…</button></div>
          <div class="dk-body" data-dbody></div>
          <div class="dk-foot" data-dfoot></div>
        </section>
        <section class="bd-panel bd-side">
          <div class="tabs bd-tabs"><button data-tab="card">Card</button><button data-tab="stats">Stats</button><button data-tab="checks">Checks</button></div>
          <div class="bd-sidebody" data-sbody></div>
          <textarea class="bd-notes" data-notes placeholder="Deck notes…" spellcheck="false" title="Notes saved with the deck"></textarea>
        </section>
      </div>`;
    const q = (s) => /** @type {HTMLElement} */ (this.root.querySelector(s));
    this.el = {
      name: q('[data-name]'), fmt: q('[data-fmt]'), status: q('[data-status]'), undo: q('[data-undo]'), redo: q('[data-redo]'), save: q('[data-save]'), moxlink: q('[data-moxlink]'),
      cat: q('.bd-cat'), dcount: q('[data-dcount]'), dbody: q('[data-dbody]'), dfoot: q('[data-dfoot]'), group: q('[data-group]'), dview: q('[data-dview]'),
      sbody: q('[data-sbody]'), tabs: q('.bd-tabs'), notes: q('[data-notes]'),
    };
    const E = this.el;
    E.name.addEventListener('input', () => {
      this.deck.name = /** @type {HTMLInputElement} */ (E.name).value;
      this.deck.dirty = true;
      this.updateToolbar();
      this.saveDraftSoon();
    });
    E.fmt.addEventListener('change', () => this.setFormat(/** @type {HTMLSelectElement} */ (E.fmt).value));
    E.status.addEventListener('click', () => this.setTab('checks'));
    E.moxlink.addEventListener('click', () => { if (this.deck.source) openExternal(this.deck.source); });
    E.undo.addEventListener('click', () => this.deck.undo());
    E.redo.addEventListener('click', () => this.deck.redo());
    q('[data-new]').addEventListener('click', () => this.newDeck());
    q('[data-open]').addEventListener('click', () => openDeckBrowser({
      onOpen: (ref) => this.openDeck(ref).then(() => this.afterChange()),
      onDeleted: (ref) => {
        this.decksChanged = true;
        if (this.deck.origin && this.deck.origin.src === ref.src && this.deck.origin.name === ref.name) {
          this.deck.origin = null;
          this.deck.dirty = true;
          this.updateToolbar();
        }
      },
    }));
    q('[data-import]').addEventListener('click', () => openImport(this.deck));
    q('[data-export]').addEventListener('click', () => openExport(this.deck));
    q('[data-hand]').addEventListener('click', () => openSampleHand(this.deck));
    q('[data-saveas]').addEventListener('click', () => this.save(true));
    E.save.addEventListener('click', () => this.save());
    q('[data-back]').addEventListener('click', () => { this.hide(); this.onBack(); });
    q('[data-basics]').addEventListener('click', () => openBasicLands(this.deck));
    E.group.addEventListener('change', () => { this.ui.group = /** @type {HTMLSelectElement} */ (E.group).value; this.saveUi(); this.renderDeck(); });
    E.dview.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { this.ui.deckView = b.dataset.v; this.saveUi(); this.renderDeck(); }));
    E.tabs.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => this.setTab(b.dataset.tab || 'card')));
    E.notes.addEventListener('input', () => {
      this.deck.comment = /** @type {HTMLTextAreaElement} */ (E.notes).value;
      this.deck.dirty = true;
      this.updateToolbar();
      this.saveDraftSoon();
    });
    this.bindDeckEvents();
  }

  saveUi() {
    try { localStorage.setItem(UI_KEY, JSON.stringify(this.ui)); } catch { /* storage unavailable */ }
  }

  saveDraftSoon(now = false) {
    clearTimeout(this.draftTimer);
    const write = () => { try { localStorage.setItem(DRAFT_KEY, this.deck.toDraft()); } catch { /* storage unavailable */ } };
    if (now) write();
    else this.draftTimer = setTimeout(write, 400);
  }

  /** Called after every deck change (edit, undo, load). */
  afterChange() {
    if (!this.mounted) return;
    this.checks = checkDeck(this.deck);
    this.updateToolbar();
    this.renderDeck();
    const id = this.deck.identity();
    if (id !== this.lastIdentity || this.deck.src !== this.lastSrc) {
      this.lastIdentity = id;
      this.lastSrc = this.deck.src;
      this.refreshResults(true);
    } else {
      this.updateBadges();
    }
    if (this.focus?.entry && !this.findEntry(this.focus.entry)) this.focus = { card: this.focus.card };
    this.renderSide();
    this.scheduleForgeCheck();
    this.saveDraftSoon();
  }

  findEntry(entry) {
    for (const s of ['commander', 'main', 'side']) if (this.deck.sections[s].includes(entry)) return s;
    return null;
  }

  // ================================================================= toolbar & deck files

  updateToolbar() {
    const d = this.deck, E = this.el;
    if (document.activeElement !== E.name) /** @type {HTMLInputElement} */ (E.name).value = d.name;
    if (document.activeElement !== E.notes) /** @type {HTMLTextAreaElement} */ (E.notes).value = d.comment || '';
    /** @type {HTMLSelectElement} */ (E.fmt).value = d.src;
    /** @type {HTMLButtonElement} */ (E.undo).disabled = !d.undoStack.length;
    /** @type {HTMLButtonElement} */ (E.redo).disabled = !d.redoStack.length;
    E.save.textContent = d.dirty ? 'Save*' : 'Saved';
    E.save.classList.toggle('primary', d.dirty);
    E.moxlink.classList.toggle('hidden', !(d.origin && /^https:\/\/(www\.)?moxfield\.com\/decks\//.test(d.source || '')));
    const errors = this.checks.issues.filter((i) => i.level === 'error').length;
    const bad = errors || (!this.forge.pending && this.forge.problem); // an older verdict doesn't count while rechecking
    E.status.className = `bd-status ${bad ? 'bad' : 'ok'}`;
    E.status.textContent = bad ? `⚠ ${errors || 1} issue${(errors || 1) === 1 ? '' : 's'}` : this.forge.pending ? '… checking' : `✓ Legal ${d.format.t}`;
  }

  setFormat(src) {
    if (!FORMATS[src] || src === this.deck.src) return;
    this.deck.change(() => {
      this.deck.src = src;
      // constructed decks have no command zone: commanders become regular cards
      if (!FORMATS[src].cmdr) {
        this.deck.sections.main.push(...this.deck.sections.commander);
        this.deck.sections.commander = [];
      }
    });
  }

  async confirmDiscard() {
    const d = this.deck;
    const empty = !d.count('main') && !d.count('commander') && !d.count('side');
    if (!d.dirty || empty) return true;
    const i = await choose('Unsaved changes', `"${d.name || 'Untitled deck'}" has changes that aren't saved.`, ['Cancel', 'Discard changes', 'Save']);
    if (i === 2) return this.save();
    return i === 1;
  }

  async newDeck() {
    if (!await this.confirmDiscard()) return;
    this.deck.reset(this.deck.src);
    this.focus = null;
    this.el.name.value = '';
    this.el.name.focus();
  }

  /** A Moxfield sync wrote these deck files: an unchanged copy of one of them open here is reloaded. */
  onDecksChanged(changed) {
    const o = this.deck.origin;
    if (!this.mounted || !o || this.deck.dirty) return;
    if (changed.some((r) => r.src === o.src && r.path === o.name)) this.openDeck(o).then(() => this.afterChange());
  }

  async openDeck(ref) {
    if (!await this.confirmDiscard()) return;
    try {
      const d = await api(`deck?src=${encodeURIComponent(ref.src)}&name=${encodeURIComponent(ref.name)}`);
      this.deck.load(d);
      this.focus = null;
      this.el.name.value = this.deck.name;
      if (!this.deck.origin) toast('Opened as a template: save it to keep your own copy.');
    } catch (e) {
      toast('Could not open the deck: ' + e.message, 'error');
    }
  }

  suggestName() {
    const c = this.deck.sections.commander[0];
    return c ? c.n.split(',')[0] : 'New deck';
  }

  /** Saves into the format's deck folder (shared with classic Forge). Resolves true when saved. */
  async save(saveAs = false) {
    const d = this.deck;
    let name = d.name.trim();
    if (saveAs || !name) {
      name = (await askName(saveAs ? 'Save a copy as' : 'Name this deck', saveAs ? `${name || this.suggestName()} (copy)` : name || this.suggestName())) || '';
      if (!name) return false;
    }
    name = name.replace(/[\\/:*?"<>|]/g, '-');
    const sameFormat = !saveAs && d.origin && d.origin.src === d.src;
    const folder = sameFormat && d.origin && d.origin.name.includes('/') ? d.origin.name.slice(0, d.origin.name.lastIndexOf('/') + 1) : '';
    const path = folder + name;
    const req = {
      src: d.src, name: path, comment: d.comment, sections: d.toApi(),
      oldSrc: saveAs ? null : d.origin?.src ?? null, oldName: saveAs ? null : d.origin?.name ?? null,
    };
    try {
      await api('deck/save', req);
    } catch (e) {
      if (/** @type {any} */ (e).status !== 409) { toast('Could not save: ' + e.message, 'error'); return false; }
      if (!await confirmBox('Replace deck?', `There's already a ${d.format.t} deck named "${name}". Replace it?`, 'Replace')) return false;
      try {
        await api('deck/save', { ...req, overwrite: true });
      } catch (e2) {
        toast('Could not save: ' + e2.message, 'error');
        return false;
      }
    }
    d.name = name;
    d.origin = { src: d.src, name: path };
    if (saveAs) d.source = ''; // a copy isn't linked to the Moxfield deck
    d.dirty = false;
    this.decksChanged = true;
    this.el.name.value = name;
    this.updateToolbar();
    this.saveDraftSoon(true);
    toast(`Saved "${name}" to your ${d.format.t} decks.`);
    return true;
  }

  onKey(e) {
    if (!this.visible || document.querySelector('.modal-back')) return;
    const typing = isTextField(e.target);
    const mod = e.ctrlKey || e.metaKey;
    const k = e.key.toLowerCase();
    if (mod && k === 's') { e.preventDefault(); this.save(); }
    else if (mod && !typing && k === 'z') { e.preventDefault(); if (e.shiftKey) this.deck.redo(); else this.deck.undo(); }
    else if (mod && !typing && k === 'y') { e.preventDefault(); this.deck.redo(); }
    else if (!typing && !mod && e.key === '/') { e.preventDefault(); this.el.q?.focus(); }
  }

  // ================================================================= catalog panel

  buildCatalogPanel() {
    const E = this.el;
    E.cat.innerHTML = `
      <div class="cat-top">
        <div class="cat-search">
          <input data-q placeholder='Search — e.g.  elf   t:dragon   o:"draw a card"   mv<=3   id:rg   f:commander' spellcheck="false" autocomplete="off">
          <select data-in title="What plain words are matched against"><option value="nametype">Name &amp; type</option><option value="all">All text</option><option value="name">Name only</option></select>
          <button class="btn ghost small" data-help title="Search syntax">?</button>
        </div>
        <div class="cat-row">
          <div class="chips" data-colors>${['W', 'U', 'B', 'R', 'G', 'C'].map((c) => `<button class="chip" data-c="${c}" title="${COLOR_NAMES[c]}"><span class="ms ${c}">${c === 'C' ? '◇' : ''}</span></button>`).join('')}<button class="chip txt" data-multi title="Multicolored cards only">Multi</button></div>
          <select data-cmode title="How selected colors combine"><option value="any">Any of these colors</option><option value="exact">Exactly these colors</option><option value="atmost">At most these colors</option></select>
          <div class="chips" data-mv title="Mana value">${[0, 1, 2, 3, 4, 5, 6, 7].map((n) => `<button class="chip txt" data-v="${n}">${n === 7 ? '7+' : n}</button>`).join('')}</div>
        </div>
        <div class="cat-row chips" data-types>${TYPE_CHIPS.map(([k, l]) => `<button class="chip txt" data-t="${k}">${l}</button>`).join('')}</div>
        <div class="cat-row">
          <select data-format title="Only cards legal in this format"><option value="-1">Any format</option>${catalog.formats.map((n, i) => `<option value="${i}">${esc(n)}</option>`).join('')}</select>
          <div class="chips" data-rarity>${[['C', 'Common'], ['U', 'Uncommon'], ['R', 'Rare'], ['M', 'Mythic rare']].map(([r, l]) => `<button class="chip txt rar-${r}" data-r="${r}" title="${l}">${r}</button>`).join('')}</div>
          <input class="set-in" data-set list="bd-sets" placeholder="Set…" title="Printed in this set (type a code or name)" spellcheck="false">
          <datalist id="bd-sets">${catalog.sets.slice().sort((a, b) => String(b.date).localeCompare(String(a.date))).map((s) => `<option value="${esc(s.code)}">${esc(s.name)}</option>`).join('')}</datalist>
          <label class="tgl" title="Only cards within your commander's color identity"><input type="checkbox" data-ident> Commander colors</label>
          <label class="tgl" title="Only cards that can lead this format's decks"><input type="checkbox" data-cmdonly> Commanders</label>
          <label class="tgl" title="Hide cards Forge's AI can't play well"><input type="checkbox" data-aibad> AI-friendly</label>
          <label class="tgl" title="Hide digital-only cards (Alchemy &quot;A-&quot; versions and online-only sets)"><input type="checkbox" data-paper> Paper only</label>
          <button class="btn ghost small" data-reset title="Clear all filters">Reset</button>
        </div>
        <div class="cat-bar"><span data-count></span><span class="bd-sp"></span>
          <span class="lbl">Sort</span><select data-sort>${Object.entries(SORTS).map(([k, s]) => `<option value="${k}">${s.label}</option>`).join('')}</select>
          <button class="btn ghost small" data-dir title="Reverse the order"></button>
          <div class="seg" data-view><button data-v="grid" title="Card images">▦</button><button data-v="list" title="List">☰</button></div>
          <input type="range" data-size min="100" max="240" step="10" title="Card size">
        </div>
      </div>
      <div class="cat-results" data-results><div class="vspace" data-space></div></div>`;
    const q = (s) => /** @type {HTMLElement} */ (E.cat.querySelector(s));
    Object.assign(E, { q: q('[data-q]'), results: q('[data-results]'), space: q('[data-space]'), count: q('[data-count]') });
    const f = this.f, ui = this.ui;
    const input = /** @type {HTMLInputElement} */ (E.q);
    let typing = 0;
    input.addEventListener('input', () => {
      clearTimeout(typing);
      typing = setTimeout(() => { f.q = input.value; this.refreshResults(); }, 140);
    });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && this.results.length) { // Enter adds the first match
        f.q = input.value;
        this.refreshResults();
        if (this.results[0]) this.addCard(this.results[0], 'main', 1);
      }
    });
    const inSel = /** @type {HTMLSelectElement} */ (q('[data-in]'));
    inSel.value = ui.searchIn;
    inSel.addEventListener('change', () => { ui.searchIn = inSel.value; this.saveUi(); this.refreshResults(); });
    q('[data-help]').addEventListener('click', () => this.showSyntaxHelp());
    const chipToggle = (sel, arr, attr, conv = (v) => v) => {
      q(sel).querySelectorAll(`[data-${attr}]`).forEach((b) => b.addEventListener('click', () => {
        const v = conv(/** @type {HTMLElement} */ (b).dataset[attr]);
        const i = arr.indexOf(v);
        if (i >= 0) arr.splice(i, 1); else arr.push(v);
        b.classList.toggle('on', i < 0);
        this.refreshResults();
      }));
    };
    chipToggle('[data-colors]', f.colors, 'c');
    chipToggle('[data-mv]', f.mv, 'v', Number);
    chipToggle('[data-types]', f.types, 't');
    chipToggle('[data-rarity]', f.rarity, 'r');
    q('[data-multi]').addEventListener('click', (e) => { f.multi = !f.multi; /** @type {HTMLElement} */ (e.currentTarget).classList.toggle('on', f.multi); this.refreshResults(); });
    const cmode = /** @type {HTMLSelectElement} */ (q('[data-cmode]'));
    cmode.addEventListener('change', () => { f.colorMode = /** @type {any} */ (cmode.value); this.refreshResults(); });
    const fmt = /** @type {HTMLSelectElement} */ (q('[data-format]'));
    fmt.addEventListener('change', () => { f.format = Number(fmt.value); this.refreshResults(); });
    const setIn = /** @type {HTMLInputElement} */ (q('[data-set]'));
    setIn.addEventListener('change', () => {
      const v = setIn.value.trim();
      let code = v.split(/\s/)[0].toUpperCase();
      if (v && !catalog.setIndex.has(code)) {
        const hit = catalog.sets.find((s) => s.name.toLowerCase().includes(v.toLowerCase()));
        code = hit ? hit.code.toUpperCase() : code;
        if (hit) setIn.value = hit.code;
      }
      f.set = v ? code : '';
      this.refreshResults();
    });
    const ident = /** @type {HTMLInputElement} */ (q('[data-ident]'));
    ident.checked = ui.identity;
    ident.addEventListener('change', () => { ui.identity = ident.checked; this.saveUi(); this.refreshResults(); });
    const cmdOnly = /** @type {HTMLInputElement} */ (q('[data-cmdonly]'));
    cmdOnly.addEventListener('change', () => { this.commandersOnly = cmdOnly.checked; this.refreshResults(); });
    const aibad = /** @type {HTMLInputElement} */ (q('[data-aibad]'));
    aibad.checked = ui.hideAiBad;
    aibad.addEventListener('change', () => { ui.hideAiBad = aibad.checked; this.saveUi(); this.refreshResults(); });
    const paper = /** @type {HTMLInputElement} */ (q('[data-paper]'));
    paper.checked = ui.paperOnly;
    paper.addEventListener('change', () => { ui.paperOnly = paper.checked; this.saveUi(); this.refreshResults(); });
    q('[data-reset]').addEventListener('click', () => {
      Object.assign(f, emptyFilter());
      input.value = '';
      setIn.value = '';
      fmt.value = '-1';
      cmode.value = 'any';
      cmdOnly.checked = false;
      this.commandersOnly = false;
      E.cat.querySelectorAll('.chip.on').forEach((c) => c.classList.remove('on'));
      this.refreshResults();
    });
    const sort = /** @type {HTMLSelectElement} */ (q('[data-sort]'));
    sort.value = ui.sort;
    sort.addEventListener('change', () => { ui.sort = sort.value; this.saveUi(); this.refreshResults(); });
    const dir = q('[data-dir]');
    const showDir = () => { dir.textContent = ui.desc ? '↓' : '↑'; };
    showDir();
    dir.addEventListener('click', () => { ui.desc = !ui.desc; showDir(); this.saveUi(); this.refreshResults(); });
    const view = q('[data-view]');
    const size = /** @type {HTMLInputElement} */ (q('[data-size]'));
    const showView = () => {
      view.querySelectorAll('button').forEach((b) => b.classList.toggle('sel', b.dataset.v === ui.view));
      size.style.visibility = ui.view === 'grid' ? '' : 'hidden';
    };
    showView();
    view.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { ui.view = b.dataset.v || 'grid'; this.saveUi(); showView(); this.resetCells(); this.renderResults(); }));
    size.value = String(ui.size);
    size.addEventListener('input', () => { ui.size = Number(size.value); this.saveUi(); this.renderResults(); });
    let frame = 0;
    E.results.addEventListener('scroll', () => {
      this.scrolledAt = performance.now();
      if (frame) return;
      frame = requestAnimationFrame(() => { frame = 0; this.renderResults(); });
    }, { passive: true });
    new ResizeObserver(() => { if (this.visible) this.renderResults(); }).observe(E.results);
    this.bindResultEvents();
  }

  showSyntaxHelp() {
    const d = modal('Search syntax', { narrow: false, peek: false, onEsc: () => d.close() });
    d.body.innerHTML = `<div class="settings-grid bd-help">
      <b>elf lord</b><span>Plain words: in the name (or type / text, per the menu next to the search box)</span>
      <b>"draw a card"</b><span>A phrase with spaces</span>
      <b>t:dragon  t:legendary</b><span>Type line contains</span>
      <b>o:flying  o:"enters the battlefield"</b><span>Rules text contains</span>
      <b>mv=3  mv&lt;=2  mv&gt;=6</b><span>Mana value (also cmc)</span>
      <b>c:rg  c=rg  c&lt;=rg  c:m  c:c</b><span>Colors: at least / exactly / at most red-green · multicolor · colorless</span>
      <b>id:esper  id&lt;=wub</b><span>Fits a commander of these colors (guild and shard names work)</span>
      <b>pow&gt;=5  tou&lt;2  loy=4</b><span>Power, toughness, loyalty</span>
      <b>r:mythic  r&gt;=rare</b><span>Rarity (default printing)</span>
      <b>s:mh3  e:c21</b><span>Printed in a set</span>
      <b>f:commander  f:modern  banned:legacy</b><span>Legal (or not) in a format</span>
      <b>is:commander  is:partner  is:legendary  is:dfc  is:vanilla  is:aibad</b><span>Card properties</span>
      <b>-t:creature</b><span>A minus sign negates any term</span></div>
      <div class="note" style="margin-top:12px">Terms combine with AND. Press Enter to add the first result, / to jump to the search box.</div>`;
    d.foot.append(Object.assign(el('<button class="btn primary">Close</button>'), { onclick: () => d.close() }));
  }

  /** Recomputes the result list from the filters. */
  refreshResults(keepScroll = false) {
    if (!catalog.ready || !this.el.results) return;
    const F = this.deck.format;
    const f = {
      ...this.f,
      searchIn: this.ui.searchIn,
      identity: this.ui.identity ? this.deck.identity() : -1,
      commanders: this.commandersOnly ? (this.deck.src === 'oathbreaker' ? CMD.OATHBREAKER | CMD.SIGNATURE : F.flag || CMD.COMMANDER) : 0,
      hideAiBad: this.ui.hideAiBad,
      paperOnly: this.ui.paperOnly,
    };
    this.results = sortCards(filterCards(/** @type {any} */ (f)), this.ui.sort, this.ui.desc);
    if (this.ui.sort === 'name' && !this.ui.desc) this.results = rankByName(this.results, this.f.q);
    const n = this.results.length;
    this.el.count.textContent = `${n.toLocaleString()} card${n === 1 ? '' : 's'}${f.identity >= 0 ? ' · within your commander\'s colors' : ''}`;
    if (!keepScroll) this.el.results.scrollTop = 0;
    this.resetCells();
    this.renderResults();
  }

  resetCells() {
    for (const c of this.cells.values()) this.dropCell(c);
    this.cells.clear();
  }

  /** Removes a catalog cell; clearing the image source cancels a download that is still running. */
  dropCell(cell) {
    const img = cell.querySelector('img');
    if (img) {
      img.removeAttribute('src');
      this.imgBusy.delete(img);
    }
    cell.remove();
  }

  /** Draws only the rows in view (the list can hold all ~34,000 cards). */
  renderResults() {
    const box = this.el.results;
    if (!box || !this.visible || !catalog.ready) return;
    const list = this.results;
    const W = box.clientWidth - 16, H = box.clientHeight, top = box.scrollTop;
    // a cell keeps its spot while it stays in view, unless the geometry changed
    const geo = `${this.ui.view}:${W}:${this.ui.size}`;
    const moved = geo !== this.cellGeo;
    this.cellGeo = geo;
    /** @type {Map<number, {x:number, y:number, w:number, h:number}>} */
    const need = new Map();
    if (this.ui.view === 'grid') {
      const gap = 10, cw = this.ui.size, ch = Math.round(cw / CARD_RATIO);
      const cols = Math.max(1, Math.floor((W + gap) / (cw + gap)));
      const rowH = ch + gap;
      const rows = Math.ceil(list.length / cols);
      const pad = Math.max(0, Math.floor((W - (cols * cw + (cols - 1) * gap)) / 2));
      this.el.space.style.height = rows * rowH + 8 + 'px';
      const rv = Math.floor(top / rowH), r1 = Math.min(rows, Math.ceil((top + H) / rowH) + 1);
      // rows in view first (their images are requested first), then the spare rows below and above
      const order = [];
      for (let r = rv; r < r1; r++) order.push(r);
      if (rv > 0) order.push(rv - 1);
      for (const r of order) {
        for (let k = 0; k < cols; k++) {
          const i = r * cols + k;
          if (i >= list.length) break;
          need.set(i, { x: 8 + pad + k * (cw + gap), y: 8 + r * rowH, w: cw, h: ch });
        }
      }
    } else {
      this.el.space.style.height = list.length * LIST_ROW + 'px';
      const r0 = Math.max(0, Math.floor(top / LIST_ROW) - 4), r1 = Math.min(list.length, Math.ceil((top + H) / LIST_ROW) + 4);
      for (let i = r0; i < r1; i++) need.set(i, { x: 0, y: i * LIST_ROW, w: W + 8, h: LIST_ROW });
    }
    const next = new Map();
    const fresh = [];
    for (const [i, p] of need) {
      const card = list[i];
      const key = this.ui.view + ':' + card.i;
      let cell = this.cells.get(key);
      if (!cell) {
        cell = this.ui.view === 'grid' ? cardTile(card, { lazy: true, cls: 'cc' }) : this.listRow(card);
        cell.dataset.ci = String(card.i);
        fresh.push(cell);
      } else if (!moved) {
        next.set(key, cell);
        continue;
      }
      cell.style.transform = `translate(${p.x}px, ${p.y}px)`;
      cell.style.width = p.w + 'px';
      cell.style.height = p.h + 'px';
      next.set(key, cell);
    }
    for (const [key, cell] of this.cells) if (!next.has(key)) this.dropCell(cell);
    const frag = document.createDocumentFragment();
    frag.append(...fresh);
    this.el.space.appendChild(frag);
    this.cells = next;
    this.updateBadges(fresh);
    this.pumpImages();
  }

  /**
   * Requests catalog images once scrolling settles (a fast scroll doesn't queue hundreds of downloads),
   * a few at a time and in view order; each finished image starts the next one.
   */
  pumpImages() {
    const wait = IMG_DELAY - (performance.now() - this.scrolledAt);
    if (wait > 0) {
      if (!this.imgTimer) this.imgTimer = setTimeout(() => { this.imgTimer = 0; this.pumpImages(); }, wait);
      return;
    }
    for (const cell of this.cells.values()) {
      if (this.imgBusy.size >= IMG_PARALLEL) return;
      const src = cell.dataset.src;
      if (!src) continue;
      delete cell.dataset.src;
      const img = cell.querySelector('img');
      if (!img) continue;
      const done = () => { if (this.imgBusy.delete(img)) this.pumpImages(); };
      img.addEventListener('load', done, { once: true });
      img.addEventListener('error', done, { once: true });
      this.imgBusy.add(img);
      img.src = src;
    }
  }

  listRow(card) {
    const r = el(`<div class="crow"><span class="q"></span><span class="nm">${esc(card.n)}</span><span class="mc">${manaHtml(card.mc)}</span>
      <span class="ty">${esc(card.t)}</span><span class="pt">${esc(card.pt || String(card.loy || '').replace(/^D/, 'def '))}</span><span class="rr rar-${esc(card.r)}">${esc(card.r)}</span><span class="st">${esc(card.s)}</span></div>`);
    return r;
  }

  /** "In deck" counts on the visible results (or on just the given cells). */
  updateBadges(cells = this.cells.values()) {
    for (const cell of cells) {
      const card = catalog.cards[Number(cell.dataset.ci)];
      const n = card ? this.deck.copies(card.n) : 0;
      if (cell.classList.contains('crow')) {
        const q = /** @type {HTMLElement} */ (cell.querySelector('.q'));
        q.textContent = n ? String(n) : '';
        cell.classList.toggle('in', n > 0);
        continue;
      }
      let b = /** @type {HTMLElement|null} */ (cell.querySelector('.qty'));
      if (n && !b) { b = el('<span class="qty"></span>'); cell.appendChild(b); }
      if (b) { b.textContent = n ? '×' + n : ''; b.style.display = n ? '' : 'none'; }
      cell.classList.toggle('in', n > 0);
    }
  }

  bindResultEvents() {
    const box = this.el.results;
    const cardAt = (e) => {
      const cell = /** @type {HTMLElement} */ (e.target).closest('[data-ci]');
      return cell ? { cell: /** @type {HTMLElement} */ (cell), card: catalog.cards[Number(/** @type {HTMLElement} */ (cell).dataset.ci)] } : null;
    };
    box.addEventListener('click', (e) => {
      const hit = cardAt(e);
      if (!hit) return;
      if (e.shiftKey) this.addCard(hit.card, 'side', 1, hit.cell);
      else this.addCard(hit.card, 'main', e.ctrlKey || e.metaKey ? 4 : 1, hit.cell);
    });
    const hover = (e) => {
      if (performance.now() - this.scrolledAt < 150) return; // cards sliding under a resting pointer
      const hit = cardAt(e);
      if (!hit) return;
      if (this.focus?.card === hit.card) { // back on the card being shown: forget cards crossed on the way
        if (this.hoverTarget) { clearTimeout(this.hoverTimer); this.hoverTarget = null; }
      } else if (this.hoverTarget !== hit.card) {
        this.hoverCard(hit.card);
      }
    };
    box.addEventListener('mouseover', hover);
    box.addEventListener('mousemove', hover); // the first move after a scroll picks up the card under the pointer
    box.addEventListener('contextmenu', (e) => {
      const hit = cardAt(e);
      if (!hit) return;
      e.preventDefault();
      this.showCard(hit.card);
      this.cardMenu(e.clientX, e.clientY, hit.card, null, null);
    });
  }

  /** Adds copies from the catalog, respecting the format's copy limit. */
  addCard(card, section, qty, cell = null) {
    const printing = this.addPrinting && this.addPrinting.card === card.n ? this.addPrinting : null;
    const added = this.deck.add(section, card, qty, printing);
    if (!added) {
      const max = this.deck.maxCopies(card);
      toast(max === 1 ? `${card.n} is already in the deck (${this.deck.format.t} decks are singleton).` : `The deck already has ${max} copies of ${card.n}.`);
      if (cell) { cell.classList.remove('nope'); void cell.offsetWidth; cell.classList.add('nope'); }
    }
    return added;
  }

  hoverCard(card, entry = null, section = null) {
    clearTimeout(this.hoverTimer);
    this.hoverTarget = card;
    // a short delay, so crossing other cards on the way to the details panel doesn't replace them
    this.hoverTimer = setTimeout(() => this.showCard(card, entry, section), 90);
  }

  // ================================================================= context menus

  closeMenu() {
    this.menuEl?.remove();
    this.menuEl = null;
    if (this.menuAway) window.removeEventListener('pointerdown', this.menuAway, true);
  }

  menu(x, y, items) {
    this.closeMenu();
    const m = el('<div class="popmenu bd-menu"></div>');
    for (const it of items) {
      if (!it) { m.appendChild(el('<div class="sep"></div>')); continue; }
      const [label, fn, off] = it;
      const node = el(`<div class="it ${off ? 'off' : ''}">${esc(label)}</div>`);
      if (!off) node.addEventListener('click', () => { this.closeMenu(); fn(); });
      m.appendChild(node);
    }
    document.body.appendChild(m);
    const r = m.getBoundingClientRect();
    m.style.left = Math.min(window.innerWidth - r.width - 8, x) + 'px';
    m.style.top = Math.min(window.innerHeight - r.height - 8, y) + 'px';
    this.menuEl = m;
    this.menuAway = (ev) => { if (!m.contains(/** @type {Node} */ (ev.target))) this.closeMenu(); };
    setTimeout(() => window.addEventListener('pointerdown', this.menuAway, true), 0);
  }

  /** Commander-slot actions a card qualifies for in the current format. */
  leadActions(card) {
    const d = this.deck, F = d.format;
    if (!F.cmdr) return [];
    if (d.src === 'oathbreaker') {
      return [
        card.cmd & CMD.OATHBREAKER ? ['Set as oathbreaker', () => d.setCommander(card, 'oathbreaker')] : null,
        card.cmd & CMD.SIGNATURE && !(card.tf & T.PW) ? ['Set as signature spell', () => d.setCommander(card, 'signature')] : null,
      ].filter(Boolean);
    }
    if (card.cmd & F.flag || card.cmd & CMD.BACKGROUND) {
      const has = d.sections.commander.length > 0;
      return [[has ? 'Set as commander (or partner)' : 'Set as commander', () => d.setCommander(card)]];
    }
    return [];
  }

  cardMenu(x, y, card, entry, section) {
    const d = this.deck;
    const n = d.copies(card.n);
    const items = [];
    if (!entry) {
      items.push(['Add to the deck', () => this.addCard(card, 'main', 1)]);
      if (d.maxCopies(card) > 1) items.push([`Add ${Math.min(4, d.maxCopies(card))} copies`, () => this.addCard(card, 'main', 4)]);
      items.push(['Add to the sideboard', () => this.addCard(card, 'side', 1)]);
      if (n) items.push(null, ['Remove one', () => d.remove(d.sections.main.some((e) => e.n === card.n) ? 'main' : d.sections.side.some((e) => e.n === card.n) ? 'side' : 'commander', card.n, 1)]);
    } else {
      items.push(['Add one', () => d.add(section, card, 1, { set: entry.s, art: entry.a, img: entry.img })]);
      items.push(['Remove one', () => d.setQty(section, entry, entry.q - 1)]);
      if (entry.q > 1) items.push(['Remove all', () => d.setQty(section, entry, 0)]);
      if (section === 'main') items.push(['Move to the sideboard', () => d.move('main', entry, 'side')]);
      if (section === 'side' || section === 'commander') items.push(['Move to the main deck', () => d.move(section, entry, 'main')]);
      items.push(['Change printing…', () => { this.showCard(card, entry, section); this.setTab('card'); setTimeout(() => /** @type {HTMLSelectElement|null} */ (this.el.sbody.querySelector('.print-sel'))?.focus(), 50); }]);
    }
    const lead = section === 'commander' ? [] : this.leadActions(card);
    if (lead.length) items.push(null, ...lead);
    this.menu(x, y, items);
  }

  // ================================================================= deck panel

  bindDeckEvents() {
    const body = this.el.dbody;
    const entryAt = (e) => {
      const row = /** @type {HTMLElement} */ (e.target).closest('[data-sec]');
      if (!row) return null;
      const sec = /** @type {HTMLElement} */ (row).dataset.sec || 'main';
      const entry = this.deck.sections[sec][Number(/** @type {HTMLElement} */ (row).dataset.k)];
      return entry ? { sec, entry, card: findCard(entry.n) || { n: entry.n, mc: '', t: '', o: '', cmd: 0, tf: 0, img: entry.img } } : null;
    };
    body.addEventListener('click', (e) => {
      const hit = entryAt(e);
      if (!hit) return;
      const act = /** @type {HTMLElement} */ (e.target).closest('[data-act]');
      if (act) {
        const d = this.deck;
        if (/** @type {HTMLElement} */ (act).dataset.act === 'dec') d.setQty(hit.sec, hit.entry, hit.entry.q - 1);
        else if (!d.add(hit.sec, hit.card, 1, { set: hit.entry.s, art: hit.entry.a, img: hit.entry.img })) {
          toast(d.maxCopies(hit.card) === 1 ? `${hit.card.n}: singleton format, one copy only.` : `At most ${d.maxCopies(hit.card)} copies of ${hit.card.n}.`);
        }
        return;
      }
      this.showCard(hit.card, hit.entry, hit.sec);
    });
    body.addEventListener('mouseover', (e) => {
      const hit = entryAt(e);
      if (hit && this.focus?.entry !== hit.entry) this.hoverCard(hit.card, hit.entry, hit.sec);
    });
    body.addEventListener('contextmenu', (e) => {
      const hit = entryAt(e);
      if (!hit) return;
      e.preventDefault();
      this.showCard(hit.card, hit.entry, hit.sec);
      this.cardMenu(e.clientX, e.clientY, hit.card, hit.entry, hit.sec);
    });
  }

  renderDeck() {
    const d = this.deck, F = d.format, E = this.el;
    const bad = this.checks.bad;
    const main = d.count('main'), side = d.count('side'), cmd = d.count('commander');
    const total = F.cmdr ? main + cmd : main;
    const sizeOk = F.cmdr ? total === F.size : total >= F.size;
    E.dcount.innerHTML = `<b class="${sizeOk ? 'ok' : ''}">${total}</b>${F.cmdr ? ` / ${F.size}` : ` <small>(min. ${F.size})</small>`} cards${side ? ` · ${side} sideboard` : ''}`;
    /** @type {HTMLSelectElement} */ (E.group).value = this.ui.group;
    E.dview.querySelectorAll('button').forEach((b) => b.classList.toggle('sel', b.dataset.v === this.ui.deckView));
    const images = this.ui.deckView === 'images';
    const frag = document.createDocumentFragment();
    const group = (title, count, rows, cls = '') => {
      const g = el(`<div class="dg ${cls}"><div class="dg-h"><span>${esc(title)}</span><b>${count}</b></div><div class="${images ? 'dg-tiles' : 'dg-rows'}"></div></div>`);
      /** @type {HTMLElement} */ (g.lastElementChild).append(...rows);
      frag.appendChild(g);
    };
    const row = (sec, entry) => {
      const k = d.sections[sec].indexOf(entry);
      const c = findCard(entry.n);
      const note = bad.get(entry.n);
      const selected = this.focus?.entry === entry;
      if (images) {
        const t = cardTile(c || { n: entry.n }, { img: entry.img, qty: entry.q, cls: `dk-tile ${note ? 'bad' : ''} ${selected ? 'sel' : ''}` });
        t.dataset.sec = sec;
        t.dataset.k = String(k);
        if (note) t.title = note;
        return t;
      }
      const r = el(`<div class="dr ${note ? 'bad' : ''} ${selected ? 'sel' : ''}" data-sec="${sec}" data-k="${k}" ${note ? `title="${esc(note)}"` : ''}>
        <span class="q">${entry.q}</span><span class="nm">${esc(entry.n)}</span>${c && entry.s && entry.s !== c.s ? `<span class="set">${esc(entry.s)}</span>` : ''}
        <span class="mc">${manaHtml(c?.mc || '')}</span>
        <span class="acts"><button class="btn ghost small" data-act="dec" title="Remove one">−</button><button class="btn ghost small" data-act="inc" title="Add one">+</button></span></div>`);
      return r;
    };
    const byMv = (a, b) => (findCard(a.n)?.cmc ?? 99) - (findCard(b.n)?.cmc ?? 99) || a.n.localeCompare(b.n);
    // command zone
    if (F.cmdr) {
      const title = d.src === 'oathbreaker' ? 'Oathbreaker & signature spell' : d.sections.commander.length > 1 ? 'Commanders' : 'Commander';
      const rows = d.sections.commander.map((e) => row('commander', e));
      if (!rows.length) {
        rows.push(el(`<div class="dk-hint">${d.src === 'oathbreaker' ? 'Right-click a planeswalker → <b>Set as oathbreaker</b>, and an instant or sorcery → <b>Set as signature spell</b>.'
          : 'Right-click a card → <b>Set as commander</b>. Tick <b>Commanders</b> above the catalog to list the candidates.'}</div>`));
      }
      group(title, d.count('commander'), rows, 'cmd');
    } else if (d.sections.commander.length) {
      group('Command zone (not used in this format)', d.count('commander'), d.sections.commander.map((e) => row('commander', e)), 'cmd');
    }
    // main deck, grouped
    const g = this.ui.group;
    const keyOf = (c) => (g === 'type' ? groupOf(c) : g === 'color' ? colorGroup(c) : g === 'mv'
      ? (!c ? 'Other' : c.tf & T.LAND && !(c.tf & T.CREATURE) ? 'Lands' : `Mana value ${Math.min(7, Math.floor(c.cmc))}${c.cmc >= 7 ? '+' : ''}`) : 'Main deck');
    /** @type {Map<string, any[]>} */
    const groups = new Map();
    for (const e of d.sections.main) {
      const k = keyOf(findCard(e.n));
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k)?.push(e);
    }
    const order = GROUP_ORDER[g] || [];
    const keys = [...groups.keys()].sort((a, b) => (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99));
    for (const k of keys) {
      const list = (groups.get(k) || []).slice().sort(byMv);
      group(k, list.reduce((a, e) => a + e.q, 0), list.map((e) => row('main', e)));
    }
    if (!d.sections.main.length) frag.appendChild(el('<div class="dk-hint">Click cards in the catalog to add them. Ctrl+click adds four, Shift+click adds to the sideboard, right-click for more.</div>'));
    // sideboard
    const sideRows = d.sections.side.slice().sort(byMv).map((e) => row('side', e));
    if (sideRows.length) group('Sideboard', side, sideRows, 'side');
    const scroll = E.dbody.scrollTop;
    E.dbody.replaceChildren(frag);
    E.dbody.scrollTop = scroll;
    // footer summary
    const s = deckStats(d);
    E.dfoot.innerHTML = `<span>${s.lands} lands</span><span>${s.spells} spells</span><span>avg. mana value ${s.avgMv.toFixed(2)}</span>`;
  }

  // ================================================================= side panel

  setTab(tab) {
    this.ui.tab = tab;
    this.saveUi();
    this.renderSide();
  }

  showCard(card, entry = null, section = null) {
    clearTimeout(this.hoverTimer);
    this.hoverTarget = null;
    const same = this.focus && this.focus.card === card && this.focus.entry === entry;
    this.focus = { card, entry, section };
    if (!same) this.addPrinting = null;
    if (this.ui.tab === 'card') this.renderSide();
    this.el.dbody.querySelectorAll('.sel').forEach((n) => n.classList.remove('sel'));
    if (entry) {
      const k = this.deck.sections[section || 'main'].indexOf(entry);
      this.el.dbody.querySelector(`[data-sec="${section}"][data-k="${k}"]`)?.classList.add('sel');
    }
  }

  renderTabs() {
    const errors = this.checks.issues.filter((i) => i.level === 'error').length + (this.forge.problem && !this.forge.pending && !this.checks.issues.length ? 1 : 0);
    this.el.tabs.querySelectorAll('button').forEach((b) => {
      b.classList.toggle('sel', b.dataset.tab === this.ui.tab);
      if (b.dataset.tab === 'checks') b.innerHTML = `Checks${errors ? ` <span class="badge-n">${errors}</span>` : ''}`;
    });
  }

  renderSide() {
    const E = this.el;
    if (!E.tabs) return;
    this.renderTabs();
    const body = E.sbody;
    if (this.ui.tab === 'stats') body.replaceChildren(this.statsView());
    else if (this.ui.tab === 'checks') body.replaceChildren(this.checksView());
    else body.replaceChildren(this.cardView());
  }

  printings(name) {
    let p = this.printCache.get(name);
    if (!p) {
      p = api(`cards/printings?name=${encodeURIComponent(name)}`).catch(() => []);
      this.printCache.set(name, p);
    }
    return p;
  }

  cardView() {
    const f = this.focus;
    if (!f || !f.card) {
      return el(`<div class="note bd-tip">Hover a card to see it here.<br><br><b>Click</b> a catalog card to add it · <b>Ctrl+click</b> adds four · <b>Shift+click</b> adds it to the sideboard · <b>Right-click</b> for more (commander, printings…).<br><br>Press <b>/</b> to search, <b>Enter</b> adds the first result, <b>Ctrl+Z</b> undoes.</div>`);
    }
    const { card: c, entry, section } = f;
    const d = this.deck;
    const box = el('<div class="det"></div>');
    const img = entry?.img || (this.addPrinting && this.addPrinting.card === c.n ? this.addPrinting.img : '') || c.img;
    const tile = cardTile(c, { img, cls: 'det-img' });
    box.appendChild(tile);
    const stat = [c.pt, c.loy && !String(c.loy).startsWith('D') ? `Loyalty ${String(c.loy).replace(/D.*$/, '')}` : '', /D(\d+)/.exec(c.loy || '')?.[1] ? `Defense ${/D(\d+)/.exec(c.loy)?.[1]}` : ''].filter(Boolean).join(' · ');
    box.appendChild(el(`<div class="det-name">${esc(c.n)} <span class="mc">${manaHtml(c.mc || '')}</span></div>`));
    box.appendChild(el(`<div class="det-type">${esc(c.t || '')}${stat ? ` · <b>${esc(stat)}</b>` : ''}</div>`));
    if (c.o) box.appendChild(el(`<div class="det-text">${manaHtml(c.o)}</div>`));
    if (c.b) box.appendChild(el(`<div class="det-back"><div class="det-name">${esc(c.b[0])} <span class="mc">${manaHtml(c.b[3] || '')}</span></div><div class="det-type">${esc(c.b[1])}${c.b[4] ? ` · <b>${esc(c.b[4])}</b>` : ''}</div><div class="det-text">${manaHtml(c.b[2] || '')}</div></div>`));
    if (catalog.ready && c.lg !== undefined) {
      box.appendChild(el(`<div class="det-legal">${catalog.formats.map((n, i) => `<span class="${isLegal(c, i) ? 'ok' : ''}" title="${isLegal(c, i) ? 'Legal' : 'Not legal'} in ${esc(n)}">${esc(n)}</span>`).join('')}</div>`));
    }
    if (c.ai & 1) box.appendChild(el('<div class="det-warn">Forge\'s AI plays this card poorly.</div>'));
    // printing picker: for a deck line it changes that line, for a catalog card it picks what gets added
    const sel = /** @type {HTMLSelectElement} */ (el('<select class="print-sel" title="Printing (set and art)"><option>Loading printings…</option></select>'));
    const pr = el('<div class="det-print"><span>Printing</span></div>');
    pr.appendChild(sel);
    box.appendChild(pr);
    this.printings(c.n).then((list) => {
      if (!sel.isConnected || !list.length) return;
      sel.replaceChildren(...list.map((p, i) => new Option(`${p.set} · ${p.setName}${p.date ? ` (${p.date.slice(0, 4)})` : ''}${p.cn ? ` #${p.cn}` : ''}${list.filter((x) => x.set === p.set).length > 1 ? ` · art ${p.art}` : ''}`, String(i))));
      const cur = entry ? { set: entry.s, art: entry.a } : this.addPrinting && this.addPrinting.card === c.n ? this.addPrinting : { set: c.s, art: 0 };
      const idx = list.findIndex((p) => p.set === cur.set && (!cur.art || p.art === cur.art));
      sel.value = String(Math.max(0, idx));
      sel.addEventListener('change', () => {
        const p = list[Number(sel.value)];
        if (!p) return;
        if (entry && this.findEntry(entry)) {
          d.setPrinting(entry, p);
        } else {
          this.addPrinting = { card: c.n, set: p.set, art: p.art, img: p.img };
          this.renderSide();
        }
      });
    });
    // actions
    const n = d.copies(c.n);
    const acts = el(`<div class="det-acts"><span class="count">In deck: <b>${n}</b></span></div>`);
    const btn = (label, cls, fn, title = '') => {
      const b = el(`<button class="btn small ${cls}" ${title ? `title="${esc(title)}"` : ''}>${esc(label)}</button>`);
      b.addEventListener('click', fn);
      acts.appendChild(b);
    };
    const sec = entry && section ? section : d.sections.main.some((e) => e.n === c.n) ? 'main' : d.sections.side.some((e) => e.n === c.n) ? 'side' : 'main';
    if (n) btn('−', '', () => (entry && this.findEntry(entry) ? d.setQty(section, entry, entry.q - 1) : d.remove(sec, c.n, 1)), 'Remove one');
    btn('+', 'primary', () => (entry && this.findEntry(entry) ? d.add(section, c, 1, { set: entry.s, art: entry.a, img: entry.img }) : this.addCard(c, 'main', 1)), 'Add one');
    btn('+ Sideboard', 'ghost', () => this.addCard(c, 'side', 1));
    for (const [label, fn] of this.leadActions(c)) btn(label, 'ghost', fn);
    box.appendChild(acts);
    return box;
  }

  statsView() {
    const s = deckStats(this.deck);
    const box = el('<div class="stats"></div>');
    box.appendChild(el(`<div class="st-nums"><div><b>${s.total}</b><span>main deck</span></div><div><b>${s.lands}</b><span>lands</span></div>
      <div><b>${s.spells}</b><span>spells</span></div><div><b>${s.avgMv.toFixed(2)}</b><span>avg. mana value</span></div></div>`));
    // mana curve
    const max = Math.max(1, ...s.curve.map((b) => b.creatures + b.other));
    const W = 268, H = 120, bw = 26, gap = (W - bw * 8) / 7;
    const bars = s.curve.map((b, i) => {
      const x = i * (bw + gap);
      const hc = (b.creatures / max) * (H - 30), ho = (b.other / max) * (H - 30);
      const n = b.creatures + b.other;
      return `<rect x="${x}" y="${H - 16 - hc}" width="${bw}" height="${hc}" rx="2" class="cr"/><rect x="${x}" y="${H - 16 - hc - ho}" width="${bw}" height="${ho}" rx="2" class="ot"/>
        ${n ? `<text x="${x + bw / 2}" y="${H - 20 - hc - ho}" class="n">${n}</text>` : ''}<text x="${x + bw / 2}" y="${H - 3}" class="l">${i === 7 ? '7+' : i}</text>`;
    }).join('');
    box.appendChild(el(`<h5>Mana curve <small><i class="sw cr"></i>creatures <i class="sw ot"></i>other spells</small></h5>`));
    box.appendChild(el(`<svg class="curve" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">${bars}</svg>`));
    // colors
    const cols = ['W', 'U', 'B', 'R', 'G'].filter((k) => s.pips[k] || s.sources[k]);
    if (cols.length) {
      const pm = Math.max(1, ...cols.map((k) => s.pips[k])), sm = Math.max(1, s.lands, ...cols.map((k) => s.sources[k]));
      box.appendChild(el('<h5>Colors <small>mana symbols in costs · lands that make the color</small></h5>'));
      box.appendChild(el(`<div class="st-colors">${cols.map((k) => `<span class="ms ${k}"></span>
        <div class="bar"><i style="width:${(s.pips[k] / pm) * 100}%" class="c-${k}"></i></div><span class="v">${s.pips[k]}</span>
        <div class="bar"><i style="width:${(s.sources[k] / sm) * 100}%" class="c-${k} src"></i></div><span class="v">${s.sources[k]}</span>`).join('')}</div>`));
    }
    // types
    const types = Object.entries(s.types).sort((a, b) => b[1] - a[1]);
    if (types.length) {
      const tm = Math.max(1, ...types.map((t) => t[1]));
      box.appendChild(el('<h5>Card types</h5>'));
      box.appendChild(el(`<div class="st-types">${types.map(([t, n]) => `<span>${esc(t)}</span><div class="bar"><i style="width:${(n / tm) * 100}%"></i></div><span class="v">${n}</span>`).join('')}</div>`));
    }
    // odds
    if (s.total >= 7) {
      box.appendChild(el('<h5>Lands in your opening hand</h5>'));
      box.appendChild(el(`<div class="st-hand">${s.opening.map((p, k) => `<div class="${k >= 2 && k <= 4 ? 'good' : ''}" title="${k} land${k === 1 ? '' : 's'}: ${(p * 100).toFixed(1)}%"><i style="height:${Math.round(p * 100)}%"></i><span>${k}</span></div>`).join('')}</div>`));
      const keep = s.opening.slice(2, 5).reduce((a, b) => a + b, 0);
      box.appendChild(el(`<div class="note">2–4 lands in ${(keep * 100).toFixed(0)}% of opening hands.</div>`));
      box.appendChild(el('<h5>Making every land drop</h5>'));
      box.appendChild(el(`<table class="st-odds"><tr><th>Turn</th>${s.drops.map((x) => `<th>${x.turn}</th>`).join('')}</tr>
        <tr><td>On the play</td>${s.drops.map((x) => `<td>${Math.round(x.play * 100)}%</td>`).join('')}</tr>
        <tr><td>On the draw</td>${s.drops.map((x) => `<td>${Math.round(x.draw * 100)}%</td>`).join('')}</tr></table>`));
    }
    return box;
  }

  checksView() {
    const d = this.deck;
    const box = el('<div class="checks"></div>');
    const f = this.forge;
    const verdict = f.pending ? 'Checking with Forge…' : f.error ? `Forge couldn't check the deck: ${f.error}`
      : f.problem ? `Forge: this deck ${f.problem}` : `Forge: a legal ${d.format.t} deck.`;
    box.appendChild(el(`<div class="ck-forge ${f.problem ? 'bad' : f.pending ? '' : 'ok'}">${esc(verdict)}</div>`));
    if (f.legal?.length && !f.pending) box.appendChild(el(`<div class="note">Cards also legal in: ${esc(f.legal.join(', '))}</div>`));
    const list = el('<ul class="ck-list"></ul>');
    for (const i of this.checks.issues) list.appendChild(el(`<li class="${i.level}">${esc(i.text)}</li>`));
    if (this.checks.issues.length) box.appendChild(list);
    else box.appendChild(el('<div class="note">No problems found.</div>'));
    return box;
  }

  // ================================================================= Forge's verdict

  scheduleForgeCheck() {
    clearTimeout(this.checkTimer);
    this.forge.pending = true;
    const seq = ++this.checkSeq;
    this.checkTimer = setTimeout(async () => {
      try {
        const r = await api('deck/check', { src: this.deck.src, sections: this.deck.toApi() });
        if (seq !== this.checkSeq) return;
        this.forge = { problem: r.problem || null, legal: r.legal || [], pending: false, error: '' };
      } catch (e) {
        if (seq !== this.checkSeq) return;
        this.forge = { problem: null, legal: [], pending: false, error: e.message };
      }
      this.updateToolbar();
      this.renderTabs();
      if (this.ui.tab === 'checks') this.renderSide();
    }, 600);
  }
}
