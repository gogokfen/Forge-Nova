// @ts-check
// Match setup screen: format, number of opponents, starting life, decks.

import { api } from '../net.js';
import { el, esc, pipsHtml, toast } from './text.js';
import { openOptions } from './options.js';
import { openAchievements } from './achievements.js';
import { HOUSE_RULES, hostCaps } from './house.js';
import { FORMATS, GEN_CMDR, GEN_CONS, deckArt, deckFits, pickDeckDialog } from './decks.js';
import { openMoxfield, moxAuto, moxPending, onMoxState } from './moxfield.js';

const MAX_OPPONENTS = 7; // Forge plays up to 8 players
export const LIFE_PRESETS = [20, 25, 30, 40];

const STORE_KEY = 'nova-lobby-v2';

/** the fixed game modes of earlier builds, as [format, opponents] */
const V1_MODES = { cmdr4: ['commander', 3], cmdr2: ['commander', 1], std2: ['constructed', 1], ffa3: ['constructed', 2], brawl: ['brawl', 1] };

function loadConfig() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
    if (saved) return saved;
    const v1 = JSON.parse(localStorage.getItem('nova-lobby-v1') || 'null');
    if (v1) {
      const [format, opponents] = V1_MODES[v1.fmt] || ['commander', 3];
      return { format, opponents, life: null, games: v1.games, spectate: v1.spectate, slots: v1.slots };
    }
  } catch { /* storage unavailable or unreadable */ }
  return { format: 'commander', opponents: 3, life: null, games: 1, spectate: false, slots: [] };
}

/** deck folders that the deck builder can edit */
const EDITABLE = new Set(['commander', 'constructed', 'brawl', 'oathbreaker', 'tinyLeaders']);

export class Lobby {
  /**
   * @param {HTMLElement} root
   * @param {(deck: {src:string, name:string}|null) => void} onOpenBuilder
   * @param {(lobby: Lobby) => void} onOnline
   */
  constructor(root, onStarted, onOpenBuilder = () => {}, onOnline = () => {}) {
    this.root = root;
    this.onStarted = onStarted;
    this.onOpenBuilder = onOpenBuilder;
    this.onOnline = onOnline;
    this.data = null;
    /** life: null = the format's default */
    this.cfg = loadConfig();
    this.busy = false;
    onMoxState(() => this.showMoxBadge());
  }

  async show() {
    this.root.classList.remove('hidden');
    if (!this.data) {
      this.root.innerHTML = '<div class="lobby-wrap"><div class="note">Loading decks…</div></div>';
      try {
        this.data = await api('decks');
      } catch (e) {
        this.root.innerHTML = `<div class="lobby-wrap"><div class="note">Could not load decks: ${esc(e.message)}</div></div>`;
        setTimeout(() => { this.data = null; this.show(); }, 2000);
        return;
      }
    }
    this.normalize();
    this.render();
    moxAuto(); // with automatic sync on, decks changed on Moxfield meanwhile come in by themselves
  }

  openMoxfield() {
    openMoxfield({ onOpenDeck: (ref) => this.onOpenBuilder(ref) });
  }

  /** The number of Moxfield decks waiting for a sync, on the lobby's Moxfield button. */
  showMoxBadge() {
    const b = this.root.querySelector('.mox-badge');
    if (!b) return;
    const n = moxPending();
    b.textContent = n ? String(n) : '';
    b.classList.toggle('hidden', !n);
    b.parentElement?.setAttribute('title', n ? `${n} Moxfield deck${n === 1 ? '' : 's'} to sync` : 'Sync your decks from Moxfield');
  }

  hide() { this.root.classList.add('hidden'); }

  fmt() { return FORMATS.find((f) => f.id === this.cfg.format) || FORMATS[0]; }
  isCmdr() { return this.fmt().cmdr; }
  playerCount() { return this.cfg.opponents + 1; }
  defaultLife() { return this.fmt().life(this.playerCount()); }
  life() { return this.cfg.life ?? this.defaultLife(); }

  /** Your decks that suit the format, the format's own deck folder first. */
  myDecks() {
    const f = this.fmt();
    const mine = (this.data.user || []).filter((d) => (f.cmdr ? d.cmdrs?.length : d.src === 'constructed'));
    return mine.sort((a, b) => Number(b.src === f.id) - Number(a.src === f.id));
  }

  defaultDeck(i) {
    const mine = this.myDecks();
    if (i === 0 && mine.length) return mine[0];
    return this.isCmdr() ? GEN_CMDR[i === 0 ? 0 : 1] : GEN_CONS[0];
  }

  normalize() {
    const cfg = this.cfg;
    if (!FORMATS.some((f) => f.id === cfg.format)) cfg.format = 'commander';
    cfg.opponents = Math.max(1, Math.min(MAX_OPPONENTS, Math.round(Number(cfg.opponents)) || 1));
    if (cfg.life != null) cfg.life = Math.max(1, Math.min(999, Math.round(Number(cfg.life)) || 1));
    if (![1, 3, 5].includes(cfg.games)) cfg.games = 1;
    cfg.spectate = !!cfg.spectate;
    if (!cfg.house || typeof cfg.house !== 'object') cfg.house = {};
    for (const [k] of HOUSE_RULES) cfg.house[k] = !!cfg.house[k];
    const cmdr = this.isCmdr();
    const all = [...(this.data?.user || []), ...(this.data?.builtin || [])];
    // saved picks refer to decks by folder + name; they may have been renamed or deleted since
    const current = (d) => (d && d.src !== 'gen' ? all.find((x) => x.src === d.src && x.name === d.name) || null : d);
    const fits = (d) => deckFits(d, cmdr);
    const slots = Array.isArray(cfg.slots) ? cfg.slots : [];
    const n = this.playerCount();
    while (slots.length < n) slots.push({ type: 'ai', deck: null, profile: '' });
    slots.length = n;
    slots.forEach((s, i) => {
      s.type = i === 0 && !cfg.spectate ? 'human' : 'ai';
      s.deck = current(s.deck);
      if (!fits(s.deck)) s.deck = this.defaultDeck(i);
    });
    cfg.slots = slots;
    try { localStorage.setItem(STORE_KEY, JSON.stringify(cfg)); } catch { /* storage may be unavailable */ }
  }

  deckArt(d) {
    return deckArt(d);
  }

  slotName(i) {
    if (this.cfg.slots[i]?.type === 'human') return 'You';
    return this.cfg.spectate ? `AI player ${i + 1}` : `AI opponent ${i}`;
  }

  /** Applies a change to the setup, then saves and redraws. */
  update(fn) {
    fn(this.cfg);
    this.normalize();
    this.render();
  }

  render() {
    const f = this.fmt();
    const cfg = this.cfg;
    const life = this.life();
    const profiles = this.data.aiProfiles || ['Default'];
    this.root.innerHTML = `<div class="lobby-wrap">
      <div class="lobby-head">
        <div><div class="logo"><span class="logo-icon"></span>Forge <b>Nova</b></div>
          <div class="sub">GPU-rendered client for the Forge rules engine · ${esc(this.data.playerName || 'Player')}</div></div>
        <div style="display:flex;gap:8px"><button class="btn online-btn" data-online title="Play with friends over the internet or your home network">Play online</button><button class="btn" data-builder title="Build and edit decks">Deck Builder</button><button class="btn mox-btn" data-mox title="Sync your decks from Moxfield">Moxfield<span class="mox-badge hidden"></span></button><button class="btn" data-achv title="Your Forge achievements">Achievements</button><button class="btn ghost" data-options title="Options (Esc)">Options</button><button class="btn ghost" data-quit>Quit</button></div>
      </div>
      <div class="lobby-grid">
        <div class="card-panel"><h3>Game setup</h3>
          <div class="setup-label">Format</div>
          <div class="formats">
            ${FORMATS.map((x) => `<div class="format-opt ${x.id === f.id ? 'sel' : ''}" data-fmt="${x.id}"><div><div class="t">${esc(x.t)}</div><div class="d">${esc(x.d)}</div></div></div>`).join('')}
          </div>
          <div class="field-row"><span>Opponents</span>
            <div class="num-step"><button class="btn small" data-opp="-1" ${cfg.opponents <= 1 ? 'disabled' : ''} title="Fewer opponents">−</button><b>${cfg.opponents}</b><button class="btn small" data-opp="1" ${cfg.opponents >= MAX_OPPONENTS ? 'disabled' : ''} title="More opponents">+</button></div></div>
          <div class="field-row"><span>Starting life</span>
            <div class="life-pick"><div class="seg">${LIFE_PRESETS.map((v) => `<button class="${life === v ? 'sel' : ''}" data-life-preset="${v}">${v}</button>`).join('')}</div>
              <input class="life-in" type="number" min="1" max="999" value="${life}" data-life title="Any starting life for every player"></div></div>
          <div class="life-note">${cfg.life == null ? `${esc(f.t)} default for ${this.playerCount()} players`
            : `Custom · <a href="#" data-life-reset>use the ${esc(f.t)} default (${this.defaultLife()})</a>`}</div>
          <div class="field-row"><span>Games per match</span><div class="seg" data-games>${[1, 3, 5].map((n) => `<button class="${cfg.games === n ? 'sel' : ''}" data-n="${n}">${n}</button>`).join('')}</div></div>
          <div class="field-row"><span>Watch AI vs AI</span><div class="switch ${cfg.spectate ? 'on' : ''}" data-spect></div></div>
          <div class="setup-label house-label">House rules</div>
          ${HOUSE_RULES.map(([k, label, tip]) => `<div class="field-row house-row ${hostCaps.freeMullOk ? '' : 'off'}" title="${esc(tip)}"><span>${esc(label)}</span>
            <div class="switch ${cfg.house[k] && hostCaps.freeMullOk ? 'on' : ''}" data-house="${k}"></div></div>
            <div class="life-note house-note">${hostCaps.freeMullOk ? esc(tip) : 'Needs Nova\'s engine patches, which are off for this Forge version: run nova\\tools\\build.cmd.'}</div>`).join('')}
        </div>
        <div class="card-panel"><h3>Players <span class="sum">${this.playerCount()} players · ${esc(f.t)} · ${life} life each</span></h3><div class="slots">
          ${cfg.slots.map((s, i) => `<div class="slot" data-slot="${i}">
            <div class="art" style="background-image:url('${this.deckArt(s.deck)}')"></div>
            <div><div class="who">${esc(this.slotName(i))}</div>
              <div class="deck">${esc(s.deck?.label || s.deck?.name || 'Choose a deck')} <span class="pips">${s.deck?.colors !== undefined ? pipsHtml(s.deck.colors) : ''}</span></div></div>
            <div class="controls">
              ${s.type === 'ai' ? `<select data-profile title="AI profile">${profiles.map((p) => `<option ${p === s.profile ? 'selected' : ''}>${esc(p)}</option>`).join('')}</select>` : ''}
              <button class="btn small" data-pick>Deck…</button>
              ${s.deck && EDITABLE.has(s.deck.src) ? '<button class="btn small ghost" data-edit title="Edit this deck in the Deck Builder">✎</button>' : ''}
              ${i > 0 && cfg.opponents > 1 ? '<button class="btn small ghost" data-remove title="Remove this opponent">✕</button>' : ''}
            </div></div>`).join('')}
          </div>
          <div class="lobby-actions">
            <div>${cfg.opponents < MAX_OPPONENTS ? '<button class="btn" data-add>+ Add opponent</button>' : ''}
              <span class="note">${f.cmdr ? 'Decks with a commander from your Forge deck folders, plus Forge\'s commander precons.' : 'Decks from your Forge "constructed" folder, plus precons.'}</span></div>
            <button class="btn primary start-btn" data-start ${this.busy ? 'disabled' : ''}>${this.busy ? 'Starting…' : cfg.spectate ? 'Watch' : 'Play'}</button>
          </div>
        </div>
      </div></div>`;

    const $$ = (sel) => this.root.querySelectorAll(sel);
    const data = (n, k) => /** @type {HTMLElement} */ (n).dataset[k] || '';
    $$('[data-fmt]').forEach((n) => n.addEventListener('click', () => this.update((c) => {
      if (c.format !== data(n, 'fmt')) c.life = null; // starting life follows the format unless set again
      c.format = data(n, 'fmt');
    })));
    $$('[data-opp]').forEach((b) => b.addEventListener('click', () => this.update((c) => { c.opponents += Number(data(b, 'opp')); })));
    $$('[data-life-preset]').forEach((b) => b.addEventListener('click', () => this.update((c) => { c.life = Number(data(b, 'lifePreset')); })));
    const lifeIn = /** @type {HTMLInputElement|null} */ (this.root.querySelector('[data-life]'));
    lifeIn?.addEventListener('change', () => {
      const v = Math.round(Number(lifeIn.value));
      if (v >= 1 && v <= 999) this.update((c) => { c.life = v; });
      else lifeIn.value = String(this.life());
    });
    lifeIn?.addEventListener('keydown', (e) => { if (e.key === 'Enter') lifeIn.blur(); });
    this.root.querySelector('[data-life-reset]')?.addEventListener('click', (e) => { e.preventDefault(); this.update((c) => { c.life = null; }); });
    $$('[data-games] button').forEach((b) => b.addEventListener('click', () => this.update((c) => { c.games = Number(data(b, 'n')); })));
    this.root.querySelector('[data-spect]')?.addEventListener('click', () => this.update((c) => { c.spectate = !c.spectate; }));
    $$('[data-house]').forEach((n) => n.addEventListener('click', () => {
      if (!hostCaps.freeMullOk) return;
      this.update((c) => { const k = data(n, 'house'); c.house[k] = !c.house[k]; });
    }));
    this.root.querySelector('[data-achv]')?.addEventListener('click', () => openAchievements());
    $$('[data-slot]').forEach((n) => {
      const i = Number(data(n, 'slot'));
      n.querySelector('[data-pick]')?.addEventListener('click', () => this.pickDeck(i));
      n.querySelector('[data-edit]')?.addEventListener('click', () => {
        const d = this.cfg.slots[i].deck;
        if (d) this.onOpenBuilder({ src: d.src, name: d.name });
      });
      n.querySelector('.art')?.addEventListener('click', () => this.pickDeck(i));
      n.querySelector('[data-remove]')?.addEventListener('click', () => this.update((c) => { c.slots.splice(i, 1); c.opponents -= 1; }));
      n.querySelector('[data-profile]')?.addEventListener('change', (e) => {
        this.cfg.slots[i].profile = /** @type {HTMLSelectElement} */ (e.target).value;
        this.normalize();
      });
    });
    this.root.querySelector('[data-add]')?.addEventListener('click', () => this.update((c) => { c.opponents += 1; }));
    this.root.querySelector('[data-start]')?.addEventListener('click', () => this.start());
    this.root.querySelector('[data-options]')?.addEventListener('click', () => openOptions());
    this.root.querySelector('[data-builder]')?.addEventListener('click', () => this.onOpenBuilder(null));
    this.root.querySelector('[data-mox]')?.addEventListener('click', () => this.openMoxfield());
    this.showMoxBadge();
    this.root.querySelector('[data-online]')?.addEventListener('click', () => this.onOnline(this));
    this.root.querySelector('[data-quit]')?.addEventListener('click', async () => {
      try { await api('quit', {}); } catch { /* host is going away */ }
      window.close();
      document.body.innerHTML = '<div class="boot"><div class="boot-card"><div class="logo"><span class="logo-icon"></span>Forge <b>Nova</b></div><div class="boot-msg">Closed. You can close this window.</div></div></div>';
    });
  }

  pickDeck(slotIndex) {
    const cmdr = this.isCmdr();
    const mox = el('<button class="btn small ghost" title="Sync your decks from Moxfield">Moxfield…</button>');
    mox.addEventListener('click', () => { picker.close(); this.openMoxfield(); });
    const picker = pickDeckDialog({
      title: `Choose a deck — ${this.slotName(slotIndex).replace(/^You$/, 'you')}`,
      initial: this.myDecks().length ? 'mine' : 'builtin',
      tools: [mox],
      tabs: [
        { key: 'mine', label: 'My decks', list: () => this.myDecks(), empty: 'No decks here. Build decks in the Deck Builder or classic Forge, or sync them from Moxfield.' },
        { key: 'builtin', label: cmdr ? 'Commander precons' : 'Precons', list: () => (this.data.builtin || []).filter((d) => (cmdr ? d.cmdrs?.length : d.src === 'constructed' || d.src === 'precon')) },
        { key: 'random', label: 'Random', list: () => (cmdr ? GEN_CMDR : GEN_CONS).map((g) => ({ ...g })) },
      ],
      onPick: (d) => {
        this.cfg.slots[slotIndex].deck = d;
        this.normalize();
        this.render();
      },
    });
  }

  async start() {
    if (this.busy) return;
    const req = {
      format: this.cfg.format,
      games: this.cfg.games,
      life: this.life(),
      houseRules: { ...this.cfg.house },
      players: this.cfg.slots.map((s) => ({ type: s.type, deck: { src: s.deck?.src || 'gen', name: s.deck?.name || 'randomColors' }, profile: s.profile || '' })),
    };
    this.busy = true;
    this.render();
    try {
      await api('match/start', req);
      this.onStarted();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      this.busy = false;
      if (!this.root.classList.contains('hidden')) this.render();
    }
  }
}
