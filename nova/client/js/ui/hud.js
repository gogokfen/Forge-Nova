// @ts-check
// DOM chrome around the WebGL board: player panels, phase bar, prompt, detail panel, log, menus.

import { store, PHASES, PHASE_INDEX, isLocal, zoneCount } from '../store.js';
import { on, send, imgUrl, api, guestRoom } from '../net.js';
import { esc, el, manaHtml, toast, showTooltip } from './text.js';
import { faceCanvas } from '../gl/cardface.js';
import { modal, anyDialogOpen, closeAllDialogs, dialogKey } from './dialogs.js';
import { openZone, closeAllZones } from './zones.js';
import { openOptions, isOptionsOpen } from './options.js';
import { settings, setSetting, onSetting } from './settings.js';
import { ChatView } from './online.js';
import { actionFor, keyHint, isListeningForKey } from './keys.js';

/** the host's mana pool order, with Forge's mana type of each (what paying from the pool sends back; colorless is 32) */
const COLOR_BYTES = [['W', 1], ['U', 2], ['B', 4], ['R', 8], ['G', 16], ['C', 32]];
const COUNTER_NAMES = { POISON: 'Poison', ENERGY: 'Energy', EXPERIENCE: 'XP', RAD: 'Rad', TICKET: 'Tickets' };

/** Classic Forge's Dev Mode tab, in its order: [action, label, tooltip, kind] */
const DEV_ACTIONS = [
  ['mana', 'Generate Mana', 'Adds seven mana of each color and seven colorless to the pool of the player who has priority'],
  ['tutor', 'Tutor for Card', 'Puts a card from the library of the player who has priority into their hand'],
  ['viewAll', 'View All Cards', 'Shows every hidden card: hands, libraries, face-down cards', 'toggle'],
  ['lands', 'Play Unlimited Lands', 'No limit on land drops', 'toggle'],
  ['cast', 'Cast Spell/Play Land', 'Casts any card without paying its mana cost, or plays a land'],
  ['toHand', 'Add Card to Hand'],
  ['toLibrary', 'Add Card to Library'],
  ['toGraveyard', 'Add Card to Graveyard'],
  ['toExile', 'Add Card to Exile'],
  ['toBattlefield', 'Add Card to Battlefield', 'Puts a card onto the battlefield without casting it'],
  ['token', 'Add Token to Battlefield'],
  ['remove', 'Remove Card from Game'],
  ['repeat', 'Repeat Last Add Card', 'Adds the last added card to the same place again'],
  ['exileHand', 'Exile Card from Hand'],
  ['exilePlay', 'Exile Card from Play'],
  ['life', 'Set Player Life'],
  ['win', 'Win Game', 'Every other player drops to 0 life (you need priority)'],
  ['addCounters', 'Add Counters to Card'],
  ['subCounters', 'Sub Counters from Card'],
  ['tap', 'Tap Permanents'],
  ['untap', 'Untap Permanents'],
  ['planarRoll', 'Rigged Planar Roll', 'Planechase: roll the planar die with the result you choose', 'planar'],
  ['planeswalk', 'Planeswalk to', 'Planechase: planeswalk to the plane you choose', 'planar'],
  ['askAI', 'Ask AI for suggestion', 'What the AI would play in your place'],
  ['askSimAI', 'Ask Simulation AI for suggestion', 'The same, with the AI simulating the game ahead (slower)'],
  ['loadState', 'Load Game State', 'Sets up the game from a game state file (Forge\'s format)'],
  ['saveState', 'Save Game State', 'Writes the current game to a game state file'],
];

/**
 * One line of Forge's card text. It carries a little HTML: gray spans around abilities that aren't in effect
 * right now (shown dimmed, as classic Forge does); any other markup is dropped and the rest escaped.
 */
function forgeLineHtml(line) {
  const text = line.replace(/<span style="color:\s*gray;?">/gi, '\u0001').replace(/<\/span>/gi, '\u0002').replace(/<[^>]*>/g, '');
  let open = 0;
  const html = manaHtml(text).replace(/[\u0001\u0002]/g, (ch) => {
    if (ch === '\u0001') { open++; return '<span class="ct-dim">'; }
    if (open > 0) { open--; return '</span>'; }
    return '';
  });
  return html + '</span>'.repeat(open);
}

/** Classic Forge marks lines of its card detail text: =attachments=, *attached to*, +controlling+, ^clone, exerted^ */
function detailLineHtml(line) {
  const m = /^([=*+^])(.+)\1$/.exec(line.trim());
  if (m) return `<div class="ct-mark">${forgeLineHtml(m[2])}</div>`;
  return `<div>${forgeLineHtml(line)}</div>`;
}

/** Forge's card detail text as HTML: one element per line, runs of blank lines as one small gap. */
function detailTextHtml(text) {
  const out = [];
  let gap = false;
  for (const raw of String(text).replace(/\r/g, '').replace(/<br\s*\/?>/gi, '\n').split('\n')) {
    const line = raw.trim();
    if (!line) { gap = out.length > 0; continue; }
    if (gap) out.push('<div class="gap"></div>');
    gap = false;
    out.push(detailLineHtml(line));
  }
  return out.join('');
}

export class Hud {
  /** @param {HTMLElement} root */
  constructor(root) {
    this.root = root;
    /** @type {Map<number, HTMLElement>} */
    this.panels = new Map();
    /** @type {Map<number, number>} the player panels' heights, for the layout (see update) */
    this.panelHeights = new Map();
    /** called when a player panel changed height (the board layout depends on it) */
    this.onPanelResize = () => {};
    /** @type {Map<number, any>} */
    this.lastLife = new Map();
    this.layout = null;
    this.detailCard = null;
    this.detailAlt = false;
    /** the host's answer about the card in the detail panel: current rules text and other cards' effects on it */
    this.detailExtra = null;
    this.textTimer = 0;
    this.textAsked = 0;
    this.textVersion = -1;

    this.phase = el('<div class="phasebar"></div>');
    this.prompt = el(`<div class="prompt"><div class="msg"></div><div class="extra"></div>
      <div class="btns"><button class="btn primary" data-b1></button><button class="btn" data-b2></button></div></div>`);
    this.side = el(`<div class="side"><div class="box detail empty"><div class="img"></div><div class="txt">Hover a card to see its details.</div></div>
      <div class="box logbox"><div class="hdr"><div class="side-tabs"><button class="on" data-tab="log">Game log</button><button class="hidden" data-tab="dev" title="Forge's developer mode (Options → Developer mode)">Dev</button></div>
        <button class="btn ghost small" data-hide>Hide ›</button></div>
        <div class="body" data-pane="log"></div><button class="latest hidden" title="Follow the newest entries again">↓ Latest</button>
        <div class="body dev hidden" data-pane="dev"></div></div></div>`);
    this.menu = el(`<div class="menu-btn"><button class="btn icon-btn" data-menu title="Options (Esc)">☰</button>
      <button class="btn icon-btn hidden" data-show>‹</button></div>`);
    this.fps = el(`<div class="perf ${settings.perf ? '' : 'hidden'}"></div>`);
    this.stackLayer = el('<div class="stack-layer"></div>');
    root.append(this.stackLayer, this.phase, this.prompt, this.side, this.menu, this.fps);
    // online matches: chat with the other players under the game log
    this.chatBox = el('<div class="box chatbox hidden"><div class="hdr"><span>Chat</span></div><div class="chat-host"></div></div>');
    this.side.appendChild(this.chatBox);
    /** @type {ChatView|null} */
    this.chatView = null;

    this.b1 = /** @type {HTMLButtonElement} */ (this.prompt.querySelector('[data-b1]'));
    this.b2 = /** @type {HTMLButtonElement} */ (this.prompt.querySelector('[data-b2]'));
    this.b1.addEventListener('click', () => send({ t: 'ok' }));
    this.b2.addEventListener('click', () => send({ t: 'cancel' }));
    this.side.querySelector('[data-hide]')?.addEventListener('click', () => setSetting('side', false));
    this.menu.querySelector('[data-show]')?.addEventListener('click', () => setSetting('side', true));
    this.menu.querySelector('[data-menu]')?.addEventListener('click', () => openOptions());
    window.addEventListener('nova:detail', (e) => this.setDetail(/** @type {CustomEvent} */ (e).detail));
    this.keyTitles();
    onSetting((key) => { if (key === 'keys') this.keyTitles(); });

    // ---- game log and Dev tabs
    this.logBody = /** @type {HTMLElement} */ (this.side.querySelector('[data-pane="log"]'));
    this.devBody = /** @type {HTMLElement} */ (this.side.querySelector('[data-pane="dev"]'));
    this.latestBtn = /** @type {HTMLElement} */ (this.side.querySelector('.latest'));
    this.tab = 'log';
    this.side.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => this.setTab(/** @type {HTMLElement} */ (b).dataset.tab || 'log')));
    /** the game log's entries shown so far (absolute index, see store.logBase) and the log they came from */
    this.logSeen = 0;
    this.logEpoch = -1;
    this.installLogFollow();
    /** @type {any} the host's Dev tab state: {on, lands, viewAll, planar} */
    this.dev = { on: false };
    this.renderDev();
    this.devBody.addEventListener('click', (e) => {
      const b = /** @type {HTMLElement|null} */ (/** @type {HTMLElement} */ (e.target).closest('[data-a]'));
      if (b) send({ t: 'dev', a: b.dataset.a });
    });
    on('dev', (m) => {
      this.dev = m;
      this.renderDev();
    });
    on('cardText', (m) => {
      const c = this.detailCard;
      if (!c || c.id !== m.id || !!m.alt !== !!(this.detailAlt && c.alt)) return;
      this.detailExtra = m;
      this.renderDetailText();
    });
    // a source named under "Effects from other cards" shows that card
    this.side.querySelector('.detail .txt')?.addEventListener('click', (e) => {
      const src = /** @type {HTMLElement|null} */ (/** @type {HTMLElement} */ (e.target).closest('[data-src]'));
      const card = src ? store.cards.get(Number(src.dataset.src)) : null;
      if (card) this.setDetail(card);
    });

    on('prompt', () => this.renderPrompt());
    on('flash', () => this.animatePrompt('flash'));
    on('alert', () => this.animatePrompt('alert'));
    on('abilities', (m) => this.showAbilities(m));
    on('focusCard', (m) => { const c = store.cards.get(m.id); if (c) this.setDetail(c); });
    on('gameOver', (m) => this.showGameOver(m));
    on('toast', (m) => toast(m.msg, m.level));
    this.installKeys();
  }

  setSideVisible(v) {
    this.side.classList.toggle('hidden', !v);
    this.menu.querySelector('[data-show]')?.classList.toggle('hidden', v);
    // a hidden log can't scroll: catch up with the newest entries now
    if (v && this.logFollow) this.scrollLogToEnd();
    if (v && this.detailCard) this.requestCardText(0);
  }

  /** Tooltips that name a key follow the player's own shortcuts. */
  keyTitles() {
    const hide = /** @type {HTMLElement} */ (this.side.querySelector('[data-hide]'));
    const show = /** @type {HTMLElement} */ (this.menu.querySelector('[data-show]'));
    hide.title = 'Hide panel' + keyHint('side');
    show.title = 'Show side panel' + keyHint('side');
  }

  // ---------------------------------------------------------------- side panel tabs: game log, Dev

  setTab(tab) {
    if (tab === 'dev' && !this.dev.on) tab = 'log';
    this.tab = tab;
    this.side.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('on', /** @type {HTMLElement} */ (b).dataset.tab === tab));
    this.logBody.classList.toggle('hidden', tab !== 'log');
    this.devBody.classList.toggle('hidden', tab !== 'dev');
    this.latestBtn.classList.toggle('hidden', tab !== 'log' || this.logFollow);
    if (tab === 'log' && this.logFollow) this.scrollLogToEnd();
  }

  renderDev() {
    const d = this.dev || {};
    this.side.querySelector('[data-tab="dev"]')?.classList.toggle('hidden', !d.on);
    if (!d.on) {
      if (this.tab === 'dev') this.setTab('log');
      this.devBody.replaceChildren();
      return;
    }
    const key = `${!!d.lands}|${!!d.viewAll}|${!!d.planar}`;
    if (this.devBody.dataset.key === key && this.devBody.childElementCount) return;
    this.devBody.dataset.key = key;
    this.devBody.innerHTML = `<div class="dev-grid">${DEV_ACTIONS.filter(([, , , kind]) => kind !== 'planar' || d.planar).map(([a, label, tip, kind]) => {
      const on = kind === 'toggle' && !!d[a];
      return `<button class="dev-btn ${kind === 'toggle' ? 'toggle' : ''} ${on ? 'on' : ''}" data-a="${a}" title="${esc(tip || label)}">${esc(label)}${kind === 'toggle' ? `<i>${on ? 'ON' : 'OFF'}</i>` : ''}</button>`;
    }).join('')}</div><div class="dev-note">Cheats from Forge's developer mode. Most ask which player or card in a dialog.</div>`;
  }

  // ---------------------------------------------------------------- game log following
  //
  // The log follows the newest entries. Scrolling up to read stops that; it resumes when you scroll back
  // down, click "Latest", or a few seconds after the pointer leaves the log.

  installLogFollow() {
    const body = this.logBody;
    this.logFollow = true;
    this.logUserAt = 0;
    this.logResume = 0;
    const byUser = () => { this.logUserAt = performance.now(); };
    for (const ev of ['wheel', 'pointerdown', 'touchstart', 'keydown']) body.addEventListener(ev, byUser, { passive: true });
    body.addEventListener('scroll', () => {
      if (this.logAtEnd()) this.setFollow(true);
      else if (performance.now() - this.logUserAt < 1500) this.setFollow(false);
    }, { passive: true });
    this.latestBtn.addEventListener('click', () => { this.setFollow(true); this.scrollLogToEnd(); });
    const box = /** @type {HTMLElement} */ (this.side.querySelector('.logbox'));
    box.addEventListener('pointerenter', () => { clearTimeout(this.logResume); });
    box.addEventListener('pointerleave', () => {
      clearTimeout(this.logResume);
      if (!this.logFollow) this.logResume = window.setTimeout(() => { this.setFollow(true); this.scrollLogToEnd(); }, 3000);
    });
  }

  logAtEnd() {
    const b = this.logBody;
    return b.scrollTop + b.clientHeight >= b.scrollHeight - 24;
  }

  setFollow(v) {
    this.logFollow = v;
    if (v) clearTimeout(this.logResume);
    this.latestBtn.classList.toggle('hidden', v || this.tab !== 'log');
  }

  scrollLogToEnd() {
    this.logBody.scrollTop = this.logBody.scrollHeight;
  }

  // ---------------------------------------------------------------- per-frame positioning

  /** Called whenever the layout changed. */
  update(layout) {
    this.layout = layout;
    if (store.online && !this.chatView) this.chatView = new ChatView(/** @type {HTMLElement} */ (this.chatBox.querySelector('.chat-host')));
    this.chatBox.classList.toggle('hidden', !store.online);
    const seen = new Set();
    let resized = false;
    for (const reg of layout.hud.regions) {
      seen.add(reg.pid);
      let pnl = this.panels.get(reg.pid);
      if (!pnl) {
        pnl = el('<div class="pp"></div>');
        const pid = reg.pid;
        pnl.addEventListener('click', () => send({ t: 'player', id: pid, btn: 1 }));
        pnl.addEventListener('contextmenu', (e) => { e.preventDefault(); this.showPlayerMenu(pid, e); });
        this.root.appendChild(pnl);
        this.panels.set(reg.pid, pnl);
      }
      this.renderPanel(pnl, reg.pid);
      // a panel at the bottom of its strip grows upward; the board keeps its cards clear of it, so it
      // relayouts when a panel's height changes (more mana, counters, commander damage...)
      const ph = pnl.offsetHeight;
      pnl.style.left = reg.panel.x + 'px';
      pnl.style.top = (reg.panel.anchor === 'bottom' ? reg.panel.edge - ph : reg.panel.edge) + 'px';
      // floating mana comes and goes all the time (every AI turn): the board always leaves room for one line of
      // it, so the cards don't move each time a mana pool fills or empties
      const mana = /** @type {HTMLElement|null} */ (pnl.querySelector('.mana'));
      const forLayout = ph - (mana ? mana.offsetHeight + 4 : 0) + 26;
      if (ph && Math.abs((this.panelHeights.get(reg.pid) || 0) - forLayout) > 1) {
        this.panelHeights.set(reg.pid, forLayout);
        resized = true;
      }
    }
    for (const [pid, pnl] of this.panels) if (!seen.has(pid)) { pnl.remove(); this.panels.delete(pid); this.panelHeights.delete(pid); }
    if (resized) this.onPanelResize();
    this.phase.style.left = layout.hud.phase.x + 'px';
    this.phase.style.top = layout.hud.phase.y - 16 + 'px';
    this.renderPhase();
    const P = layout.hud.prompt;
    this.prompt.style.left = P.x + 'px';
    this.prompt.style.top = P.y + 'px';
    this.prompt.style.width = P.w + 'px';
    this.prompt.style.height = P.h ? P.h + 'px' : '';
    this.prompt.classList.toggle('bar', !!P.bar);
    this.renderStack(layout.hud.stack);
    this.renderPrompt();
    this.renderLog();
  }

  panelCenter(pid) {
    const p = this.panels.get(pid);
    if (!p) return null;
    const r = p.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  renderPanel(pnl, pid) {
    const p = store.players.get(pid);
    if (!p) return;
    const g = store.game;
    pnl.classList.toggle('active', g.ap === pid);
    pnl.classList.toggle('priority', !!p.pri);
    pnl.classList.toggle('hi', store.sel.hiP.has(pid));
    pnl.classList.toggle('lost', !!p.lost);
    pnl.classList.toggle('target', isTargetPrompt());
    const prev = this.lastLife.get(pid);
    const cnt = p.cnt || {};
    const counters = Object.entries(cnt).filter(([k, v]) => v > 0)
      .map(([k, v]) => `<span class="stat ${k === 'POISON' ? 'warn' : ''}" title="${esc(COUNTER_NAMES[k] || k)}">${esc(COUNTER_NAMES[k] || k)} <b>${v}</b></span>`).join('');
    const mana = (p.mana || []).map((n, i) => ({ n, c: COLOR_BYTES[i] })).filter((m) => m.n > 0);
    const payMode = isLocal(pid) && /InputPayMana/.test(store.prompt?.input || '');
    const cmdDmg = p.cmdDmg ? Object.entries(p.cmdDmg).map(([cid, d]) => {
      const c = store.cards.get(Number(cid));
      return `${esc((c?.n || 'Commander').split(',')[0])} <b>${d}</b>`;
    }).join(' · ') : '';
    const hand = zoneCount(p, 'Hand');
    const lib = zoneCount(p, 'Library');
    // online: a friend who lost the connection or left
    const peer = store.peers.get(pid);
    const net = peer && !peer.host && !p.lost ? (peer.gone ? '<span class="tag off">LEFT</span>' : !peer.conn ? '<span class="tag off">OFFLINE</span>' : '') : '';
    const lag = peer && peer.rtt != null && peer.conn ? ` · ${peer.rtt} ms` : '';
    const html = `<div class="av" style="background-image:url(${p.avImg ? imgUrl(p.avImg) : `/avatar/${p.av ?? 0}.png`})"></div>
      <div class="nm" title="${esc(p.n)}${lag}">${esc(p.n)}${p.ai ? '<span class="tag">AI</span>' : ''}${net}${p.xt ? '<span class="tag">EXTRA TURN</span>' : ''}</div>
      <div class="life ${prev !== undefined && prev !== p.life ? (p.life < prev ? 'flash-dn' : 'flash-up') : ''}">${p.life}<small>life</small></div>
      <div class="row"><span class="stat" title="Cards in hand">Hand <b>${hand}</b></span><span class="stat" title="Cards in library">Library <b>${lib}</b></span>${counters}</div>
      ${mana.length ? `<div class="mana">${mana.map((m) => `<span class="m ${payMode ? 'click' : ''}" data-c="${m.c[1]}" title="${payMode ? 'Pay with this mana' : ''}"><span class="ms ${m.c[0]}"></span>${m.n}</span>`).join('')}</div>` : ''}
      ${cmdDmg ? `<div class="cmd-dmg" title="Commander damage taken">Cmdr dmg: ${cmdDmg}</div>` : ''}`;
    if (pnl.dataset.html !== html) {
      pnl.innerHTML = html;
      pnl.dataset.html = html;
      pnl.querySelectorAll('.m.click').forEach((m) => m.addEventListener('click', (e) => {
        e.stopPropagation();
        send({ t: 'mana', c: Number(/** @type {HTMLElement} */ (m).dataset.c) });
      }));
      pnl.title = p.det || '';
    }
    this.lastLife.set(pid, p.life);
  }

  showPlayerMenu(pid, e) {
    const p = store.players.get(pid);
    if (!p) return;
    const items = [['Graveyard', 'Graveyard'], ['Exile', 'Exile'], ['Command zone', 'Command']];
    if (isLocal(pid)) items.push(['Library (if allowed)', 'Library']);
    // someone else's hidden cards, when we may see them (dev mode's View All Cards, some card effects)
    else {
      if (p.z?.Hand?.length) items.push(['Hand', 'Hand']);
      if (p.z?.Library?.length) items.push(['Library', 'Library']);
    }
    const menu = el('<div class="popmenu"></div>');
    for (const [label, zone] of items) {
      const it = el(`<div class="it">${esc(label)} <span class="k">${zoneCount(p, zone)}</span></div>`);
      it.addEventListener('click', () => { menu.remove(); openZone(pid, zone); });
      menu.appendChild(it);
    }
    // the host of an online match can hand a friend's seat to the AI, or take them out of the game
    const peer = store.peers.get(pid);
    if (store.online && !guestRoom() && peer && !peer.host && !p.lost && !store.game.over) {
      menu.appendChild(el('<div class="sep"></div>'));
      const act = (label, path, confirmText) => {
        const it = el(`<div class="it">${esc(label)}</div>`);
        it.addEventListener('click', () => {
          menu.remove();
          if (!window.confirm(confirmText)) return;
          api(path, { id: peer.seat }).catch((x) => toast(x.message, 'error'));
        });
        menu.appendChild(it);
      };
      if (!p.ai) act(`Let the AI play for ${p.n}`, 'room/takeover', `The AI plays ${p.n}'s cards for the rest of this game. ${p.n} can keep watching.`);
      act(`Remove ${p.n} from the game`, 'room/concede', `${p.n} concedes this game.`);
    }
    this.popup(menu, e.clientX, e.clientY);
  }

  popup(menu, x, y) {
    document.querySelectorAll('.popmenu').forEach((m) => m.remove());
    this.root.appendChild(menu);
    const r = menu.getBoundingClientRect();
    menu.style.left = Math.min(window.innerWidth - r.width - 10, x) + 'px';
    menu.style.top = Math.min(window.innerHeight - r.height - 10, y) + 'px';
    const away = (ev) => {
      if (!menu.contains(/** @type {Node} */ (ev.target))) {
        menu.remove();
        window.removeEventListener('pointerdown', away, true);
        menu.dispatchEvent(new Event('dismiss'));
      }
    };
    setTimeout(() => window.addEventListener('pointerdown', away, true), 0);
  }

  // ---------------------------------------------------------------- phase bar

  renderPhase() {
    const g = store.game;
    const ap = store.players.get(g.ap);
    const stops = ap?.stops ?? -1;
    const cur = PHASE_INDEX[g.phase] ?? -1;
    const mk = store.yieldMarker;
    const key = `${g.turn}|${g.phase}|${g.ap}|${stops}|${mk ? mk.p + mk.ph : ''}|${ap?.n}`;
    if (this.phase.dataset.key === key) return;
    this.phase.dataset.key = key;
    const turn = `<span class="turn">Turn <b>${g.turn ?? 0}</b> · ${esc(ap ? ap.n : '')}</span>`;
    const phs = PHASES.slice(1).map(([id, abbr, name], i) => {
      const idx = i + 1;
      const stop = stops < 0 || (stops & (1 << idx)) !== 0;
      const marker = mk && mk.p === g.ap && mk.ph === id;
      return `<span class="ph ${stop ? 'stop' : ''} ${idx === cur ? 'cur' : ''} ${marker ? 'marker' : ''}" data-ph="${id}" data-name="${esc(name)}" data-stop="${stop ? 1 : 0}">${abbr}</span>`;
    }).join('');
    this.phase.innerHTML = turn + phs;
    this.phase.querySelectorAll('.ph').forEach((node) => {
      const n = /** @type {HTMLElement} */ (node);
      n.addEventListener('click', () => send({ t: 'stop', player: g.ap, phase: n.dataset.ph, on: n.dataset.stop !== '1' }));
      n.addEventListener('contextmenu', (e) => { e.preventDefault(); send({ t: 'yieldMarker', player: g.ap, phase: n.dataset.ph }); });
      n.addEventListener('mouseenter', (e) => showTooltip(`${n.dataset.name}\n${n.dataset.stop === '1' ? 'Stops here on ' : 'Auto-passes on '}${ap ? ap.n : 'this player'}'s turn (click to toggle).\nRight-click: pass priority until this phase.`, e.clientX, e.clientY));
      n.addEventListener('mouseleave', () => showTooltip('', 0, 0));
    });
  }

  // ---------------------------------------------------------------- prompt

  renderPrompt() {
    const pr = store.prompt || {};
    const msg = /** @type {HTMLElement} */ (this.prompt.querySelector('.msg'));
    const text = pr.msg || '';
    if (msg.dataset.text !== text) {
      msg.dataset.text = text;
      msg.innerHTML = manaHtml(text);
    }
    this.b1.textContent = pr.b1?.l || 'OK';
    this.b2.textContent = pr.b2?.l || 'Cancel';
    this.b1.disabled = !pr.b1?.on;
    this.b2.disabled = !pr.b2?.on;
    // an unused button keeps its place (invisible), so the other one never moves under the pointer
    this.b1.classList.toggle('gone', !pr.b1?.l && !pr.b1?.on);
    this.b2.classList.toggle('gone', !pr.b2?.l && !pr.b2?.on);
    this.b1.classList.toggle('focus-ring', !!pr.focus1 && !!pr.b1?.on);
    this.prompt.classList.toggle('waiting', !pr.b1?.on && !pr.b2?.on);
    const extra = /** @type {HTMLElement} */ (this.prompt.querySelector('.extra'));
    const ex = [];
    // (attacking with everything is Forge's own Alpha Strike button)
    if (pr.input === 'InputPassPriority' && pr.b1?.on) ex.push(['Pass turn', 'passTurn']);
    const exKey = ex.map((x) => x[1]).join(',');
    if (extra.dataset.key !== exKey) {
      extra.dataset.key = exKey;
      extra.replaceChildren(...ex.map(([label, t]) => {
        const b = el(`<button class="btn small">${esc(label)}</button>`);
        b.addEventListener('click', () => send({ t }));
        return b;
      }));
    }
  }

  animatePrompt(cls) {
    this.prompt.classList.remove(cls);
    void this.prompt.offsetWidth;
    this.prompt.classList.add(cls);
    setTimeout(() => this.prompt.classList.remove(cls), 1300);
  }

  // ---------------------------------------------------------------- stack captions

  renderStack(items) {
    const bottomLimit = this.layout?.hud.fieldH || window.innerHeight;
    const key = items.map((s) => `${s.si.id}:${s.si.txt?.length}@${Math.round(s.x)},${Math.round(s.y)}`).join('|') + '|' + Math.round(bottomLimit);
    if (this.stackLayer.dataset.key === key) return;
    this.stackLayer.dataset.key = key;
    const caps = items.slice(0, 6).map((s, i) => {
      const act = store.players.get(s.si.act);
      const verb = s.si.tr ? 'Trigger' : s.si.ab ? 'Ability' : 'Spell';
      const cap = el(`<div class="stack-cap ${s.top ? 'top' : ''}"><div class="who">${esc(act ? act.n : '')} · ${verb}${s.top ? '<span class="next">resolves first</span>' : ''}</div>
        <div class="tx">${manaHtml((s.si.txt || '').slice(0, 600))}</div></div>`);
      cap.style.left = s.x - 222 + 'px';
      // the first to resolve is never covered by the ones below it
      cap.style.zIndex = String(20 - i);
      return cap;
    });
    this.stackLayer.replaceChildren(...caps);
    // each caption starts at its card, or just below the caption above it (they never overlap)
    let bottom = -Infinity;
    caps.forEach((cap, i) => {
      const s = items[i];
      const top = Math.max(s.y - s.h / 2, bottom + 6);
      cap.style.top = top + 'px';
      bottom = top + cap.offsetHeight;
      if (top > bottomLimit - 40) cap.remove(); // no room left above the hand
    });
  }

  // ---------------------------------------------------------------- detail panel

  setDetail(card, stackItem) {
    const box = /** @type {HTMLElement} */ (this.side.querySelector('.detail'));
    if (!card) return;
    if (this.detailCard && this.detailCard.id === card.id && this.detailCard === card && !this.detailDirty) return;
    const sameCard = !!this.detailCard && this.detailCard.id === card.id;
    // another card: the host's details of the previous one no longer apply (the same card keeps them until updated)
    if (!sameCard) this.detailExtra = null;
    this.detailCard = card;
    this.detailStack = stackItem;
    this.detailDirty = false;
    const face = this.detailAlt && card.alt ? { ...card.alt, id: card.id } : card;
    box.classList.remove('empty');
    const img = /** @type {HTMLElement} */ (box.querySelector('.img'));
    img.replaceChildren();
    img.style.backgroundImage = '';
    if (face.img && !(card.hid && !card.fd)) {
      const url = imgUrl(face.img);
      img.appendChild(faceCanvas(face));
      const probe = new Image();
      probe.onload = () => { if (this.detailCard === card) { img.style.backgroundImage = `url("${url}")`; img.querySelector('canvas')?.remove(); } };
      probe.src = url;
    } else if (card.hid) {
      const owner = store.players.get(card.o ?? card.c);
      img.style.backgroundImage = `url(/sleeve/${owner?.sl ?? 0}.png)`;
    } else {
      img.appendChild(faceCanvas(face));
    }
    if (card.alt) {
      const flip = el('<button class="btn small flip">Flip</button>');
      flip.addEventListener('click', () => { this.detailAlt = !this.detailAlt; this.detailDirty = true; this.detailExtra = null; this.setDetail(card); });
      img.appendChild(flip);
    }
    this.renderDetailText();
    // another card's text starts at its top
    if (!sameCard) /** @type {HTMLElement} */ (box.querySelector('.txt')).scrollTop = 0;
    this.requestCardText(sameCard ? 250 : 60);
  }

  /**
   * The mouse wheel over a card on the board scrolls its text here, so a long text can be read without steering
   * the pointer past other cards to the panel. False when there is nothing to scroll (the text fits, no panel).
   * @param {WheelEvent} e
   */
  scrollDetail(e) {
    const txt = /** @type {HTMLElement} */ (this.side.querySelector('.detail .txt'));
    if (!settings.side || !this.detailCard || txt.scrollHeight <= txt.clientHeight + 1) return false;
    // a mouse notch (100 px) moves about three lines
    txt.scrollTop += e.deltaMode === 1 ? e.deltaY * 18 : e.deltaMode === 2 ? e.deltaY * txt.clientHeight : e.deltaY * 0.55;
    return true;
  }

  /**
   * The detail text. Once the host has answered, it is classic Forge's card detail text: the card's current
   * abilities (with keywords and abilities other cards gave it), attachments, counters and choices, followed by
   * the other cards whose effects apply to it. Until then, the printed text.
   */
  renderDetailText() {
    const card = this.detailCard;
    const box = /** @type {HTMLElement} */ (this.side.querySelector('.detail .txt'));
    if (!card) return;
    const stackItem = this.detailStack;
    const face = this.detailAlt && card.alt ? { ...card.alt, id: card.id } : card;
    const x = this.detailExtra && this.detailExtra.id === card.id && this.detailExtra.text ? this.detailExtra : null;
    const lines = [];
    if (card.hid && !card.fd) {
      lines.push('<div class="nm">Hidden card</div>');
    } else {
      lines.push(`<div class="nm">${esc(face.n || '')} ${face.mc ? manaHtml(face.mc) : ''}</div>`);
      if (face.ty) lines.push(`<div class="ty">${esc(face.ty)}</div>`);
      if (x) lines.push(`<div class="ct">${detailTextHtml(x.text)}</div>`);
      else if (face.tx) lines.push(`<div>${manaHtml(face.tx)}</div>`);
      const stats = [];
      if (card.pow !== undefined) stats.push(`<b>${card.pow}/${card.tou}</b>${card.dmg ? ` <span style="color:var(--red)">(${card.dmg} damage)</span>` : ''}`);
      if (card.loy) stats.push(`Loyalty <b>${card.cnt?.LOYALTY ?? card.loy}</b>`);
      if (card.def) stats.push(`Defense <b>${card.cnt?.DEFENSE ?? card.def}</b>`);
      if (stats.length) lines.push(`<div style="margin-top:6px">${stats.join(' · ')}</div>`);
    }
    const extra = this.detailExtra && this.detailExtra.id === card.id ? this.detailExtra : null;
    if (extra && (extra.fx?.length || extra.pump || extra.gained)) {
      const fx = (extra.fx || []).map((f) => `<div class="fx"><b class="${f.src >= 0 ? 'src' : ''}" ${f.src >= 0 ? `data-src="${f.src}" title="Show this card"` : ''}>${esc(f.n)}</b>${f.d ? ' — ' + manaHtml(f.d) : ''}</div>`);
      const other = [extra.pump, extra.gained && 'gains ' + extra.gained].filter(Boolean).join(', ');
      if (other) fx.push(`<div class="fx"><b>Spells and abilities</b> — ${esc(other)}</div>`);
      lines.push(`<div class="fx-box"><div class="fx-h">Effects from other cards</div>${fx.join('')}</div>`);
    }
    const meta = [];
    // the host's text already lists counters, attachments and choices
    if (!x) {
      if (card.cnt) meta.push('Counters: ' + Object.entries(card.cnt).map(([k, v]) => `${k.toLowerCase()} ×${v}`).join(', '));
      if (card.at != null) meta.push('Attached to ' + (store.cards.get(card.at)?.n || '?'));
      if (card.atp != null) meta.push('Attached to ' + (store.players.get(card.atp)?.n || '?'));
      if (card.chT) meta.push('Chosen type: ' + card.chT);
      if (card.chC) meta.push('Chosen colors: ' + card.chC);
      if (card.chP != null) meta.push('Chosen player: ' + (store.players.get(card.chP)?.n || '?'));
    }
    if (card.exw != null) meta.push('Exiled with ' + (store.cards.get(card.exw)?.n || '?'));
    const flags = [card.tap && 'Tapped', card.sick && 'Summoning sick', card.tok && 'Token', card.cmd && 'Commander', card.fd && 'Face down', card.ph && 'Phased out', card.atk && 'Attacking', card.blk && 'Blocking'].filter(Boolean);
    if (flags.length) meta.push(flags.join(' · '));
    const ctrl = store.players.get(card.c), own = store.players.get(card.o);
    if (ctrl) meta.push(`Controller: ${ctrl.n}${own && own !== ctrl ? ` (owner: ${own.n})` : ''}`);
    if (stackItem) meta.unshift(`<span style="color:var(--gold-2)">On the stack:</span> ${manaHtml(stackItem.txt || '')}`);
    if (meta.length) lines.push(`<div class="dim" style="margin-top:8px">${meta.map((m) => (m.startsWith('<') ? m : esc(m))).join('<br>')}</div>`);
    box.innerHTML = lines.join('');
  }

  /** Asks the host for the detail panel's card text (a card we may see; not while the panel is hidden). */
  requestCardText(delay) {
    const c = this.detailCard;
    if (!c || !(c.id >= 0) || (c.hid && !c.fd) || !settings.side || !store.inMatch) return;
    const key = c.id + (this.detailAlt ? 'a' : '');
    if (this.textTimer && this.textKey === key) return; // already on its way
    clearTimeout(this.textTimer);
    this.textKey = key;
    this.textTimer = window.setTimeout(() => {
      this.textTimer = 0;
      const d = this.detailCard;
      if (!d || !(d.id >= 0)) return;
      this.textAsked = performance.now();
      this.textVersion = store.version;
      send({ t: 'cardText', id: d.id, alt: !!(this.detailAlt && d.alt) });
    }, delay);
  }

  refreshDetail() {
    const c = this.detailCard;
    if (c && c.id >= 0) {
      const fresh = store.cards.get(c.id);
      if (fresh && fresh !== c) { this.detailDirty = true; this.setDetail(fresh, this.detailStack); return; }
      // other cards' effects on this one may have changed: ask again now and then
      if (store.version !== this.textVersion && performance.now() - this.textAsked > 400) this.requestCardText(150);
    }
  }

  // ---------------------------------------------------------------- log

  renderLog() {
    const body = this.logBody;
    // a new game, or the full log again after a reconnect: start over
    if (store.logEpoch !== this.logEpoch) {
      body.replaceChildren();
      this.logEpoch = store.logEpoch;
      this.logSeen = store.logBase;
    }
    const total = store.logBase + store.log.length;
    if (total <= this.logSeen) return;
    const frag = document.createDocumentFragment();
    for (let i = Math.max(this.logSeen, store.logBase); i < total; i++) {
      const e = store.log[i - store.logBase];
      frag.appendChild(el(`<div class="le ${e.ty}">${manaHtml(e.m)}</div>`));
    }
    body.appendChild(frag);
    this.logSeen = total;
    // the oldest entries go; someone reading further up keeps their place
    const extra = body.childElementCount - 600;
    if (extra > 0) {
      const before = body.scrollHeight;
      for (let i = 0; i < extra; i++) body.firstElementChild?.remove();
      if (!this.logFollow) body.scrollTop -= before - body.scrollHeight;
    }
    if (this.logFollow) this.scrollLogToEnd();
    else if (this.tab === 'log') this.latestBtn.classList.remove('hidden');
  }

  // ---------------------------------------------------------------- abilities menu

  showAbilities(m) {
    const menu = el('<div class="popmenu"></div>');
    let chosen = false;
    m.items.forEach((it, i) => {
      const node = el(`<div class="it ${it.on ? '' : 'off'}"><span class="k">${i < 9 ? i + 1 : ''}</span><span>${manaHtml(it.label)}</span></div>`);
      if (it.on) node.addEventListener('click', () => { chosen = true; menu.remove(); send({ t: 'ability', id: it.id }); });
      menu.appendChild(node);
    });
    const keys = (e) => {
      const n = Number(e.key);
      if (n >= 1 && n <= m.items.length && m.items[n - 1].on) {
        chosen = true;
        menu.remove();
        send({ t: 'ability', id: m.items[n - 1].id });
      }
      if (e.key === 'Escape') menu.remove();
    };
    window.addEventListener('keydown', keys);
    menu.addEventListener('dismiss', () => {});
    const obs = new MutationObserver(() => {
      if (!menu.isConnected) {
        window.removeEventListener('keydown', keys);
        obs.disconnect();
        if (!chosen) send({ t: 'abilityCancel' });
      }
    });
    obs.observe(this.root, { childList: true });
    this.popup(menu, m.x || window.innerWidth / 2, m.y || window.innerHeight / 2);
  }

  // ---------------------------------------------------------------- game over

  showGameOver(m) {
    closeAllZones();
    const me = store.local.length ? store.players.get(store.local[0]) : null;
    const iWon = me && m.winner && m.winner === me.n;
    const title = m.spectator ? (m.winner ? `${m.winner} wins` : 'Draw') : m.draw ? 'Draw' : iWon ? 'Victory' : 'Defeat';
    const dlg = modal('Game over', { narrow: true });
    dlg.body.appendChild(el(`<div class="result"><div class="big ${iWon ? 'win' : 'lose'}">${esc(title)}</div>
      <div class="lines">${(m.lines || []).map(esc).join('<br>')}${m.turns ? `<br>Game lasted ${m.turns} turns.` : ''}</div>
      ${m.gamesInMatch > 1 ? `<div class="score">${(m.score || []).map((s) => `<span>${esc(s.n)} <b>${s.won ?? 0}</b></span>`).join('')}</div>` : ''}</div>`));
    const decide = (d) => {
      dlg.close();
      send({ t: 'next', decision: d });
      // online, the next game starts when everyone has decided
      if (m.online && d !== 'QUIT') toast('Waiting for the other players to decide…');
    };
    if (!m.matchOver) dlg.foot.append(Object.assign(el('<button class="btn primary">Next game</button>'), { onclick: () => decide('CONTINUE') }));
    dlg.foot.append(Object.assign(el('<button class="btn">Rematch</button>'), { onclick: () => decide('NEW') }));
    const back = m.online ? 'Back to the room' : 'Back to lobby';
    const quit = Object.assign(el(`<button class="btn ${m.matchOver ? 'primary' : ''}">${back}</button>`), { onclick: () => decide('QUIT') });
    if (m.online) quit.title = 'Ends the match for everyone';
    dlg.foot.append(quit);
  }

  // ---------------------------------------------------------------- keys (Esc is the options menu, see options.js;
  // the others can be changed in Options → Keyboard shortcuts, see keys.js)

  installKeys() {
    window.addEventListener('keydown', (e) => {
      if (!store.inMatch || isOptionsOpen() || isListeningForKey()) return;
      const tag = /** @type {HTMLElement} */ (e.target).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (document.querySelector('.popmenu')) return;
      const action = actionFor(e);
      // the engine waits for an answer in a window: keys go to that window (OK answers it), not to the game
      if (anyDialogOpen() && action !== 'side') {
        if (dialogKey(e, action === 'ok')) e.preventDefault();
        return;
      }
      if (!action) return;
      const pr = store.prompt || {};
      switch (action) {
        case 'ok': if (pr.b1?.on) { e.preventDefault(); send({ t: 'ok' }); } break;
        case 'cancel': if (pr.b2?.on) { e.preventDefault(); send({ t: 'cancel' }); } break;
        case 'passTurn': e.preventDefault(); send({ t: 'passTurn' }); break;
        case 'undo': e.preventDefault(); send({ t: 'undo' }); break;
        case 'attackAll': if (pr.input === 'InputAttack') { e.preventDefault(); send({ t: 'attackAll' }); } break;
        case 'side': e.preventDefault(); setSetting('side', !settings.side); break;
        default: break;
      }
    });
  }

  setPerfVisible(v) {
    this.fps.classList.toggle('hidden', !v);
  }

  setPerf(text) {
    if (settings.perf) this.fps.textContent = text;
  }

  reset() {
    closeAllDialogs();
    closeAllZones();
    this.logBody.replaceChildren();
    this.logEpoch = -1; // rebuilt from the store's log
    this.setFollow(true);
    this.detailCard = null;
    this.detailExtra = null;
  }
}

/** Heuristic: does the current prompt accept players as targets? */
function isTargetPrompt() {
  const input = store.prompt?.input || '';
  return input === 'InputSelectTargets' || input === 'InputSelectEntitiesFromList' || input === 'InputAttack';
}
