// @ts-check
// DOM chrome around the WebGL board: player panels, phase bar, prompt, detail panel, log, menus.

import { store, PHASES, PHASE_INDEX, isLocal, zoneCount } from '../store.js';
import { on, send, imgUrl, api, guestRoom } from '../net.js';
import { esc, el, manaHtml, toast, showTooltip } from './text.js';
import { faceCanvas } from '../gl/cardface.js';
import { modal, anyDialogOpen, closeAllDialogs } from './dialogs.js';
import { openZone, closeAllZones } from './zones.js';
import { openOptions, isOptionsOpen } from './options.js';
import { settings, setSetting } from './settings.js';
import { ChatView } from './online.js';

const COLOR_BYTES = [['W', 1], ['U', 2], ['B', 4], ['R', 8], ['G', 16], ['C', 0]];
const COUNTER_NAMES = { POISON: 'Poison', ENERGY: 'Energy', EXPERIENCE: 'XP', RAD: 'Rad', TICKET: 'Tickets' };

export class Hud {
  /** @param {HTMLElement} root */
  constructor(root) {
    this.root = root;
    /** @type {Map<number, HTMLElement>} */
    this.panels = new Map();
    /** @type {Map<number, any>} */
    this.lastLife = new Map();
    this.layout = null;
    this.logCount = 0;
    this.detailCard = null;
    this.detailAlt = false;

    this.phase = el('<div class="phasebar"></div>');
    this.prompt = el(`<div class="prompt"><div class="msg"></div><div class="extra"></div>
      <div class="btns"><button class="btn primary" data-b1></button><button class="btn" data-b2></button></div></div>`);
    this.side = el(`<div class="side"><div class="box detail empty"><div class="img"></div><div class="txt">Hover a card to see its details.</div></div>
      <div class="box logbox"><div class="hdr"><span>Game log</span><button class="btn ghost small" data-hide title="Hide panel (Tab)">Hide ›</button></div><div class="body"></div></div></div>`);
    this.menu = el(`<div class="menu-btn"><button class="btn icon-btn" data-menu title="Options (Esc)">☰</button>
      <button class="btn icon-btn hidden" data-show title="Show side panel (Tab)">‹</button></div>`);
    this.fps = el(`<div class="perf ${settings.perf ? '' : 'hidden'}"></div>`);
    this.stackLayer = el('<div></div>');
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
  }

  // ---------------------------------------------------------------- per-frame positioning

  /** Called whenever the layout changed. */
  update(layout) {
    this.layout = layout;
    if (store.online && !this.chatView) this.chatView = new ChatView(/** @type {HTMLElement} */ (this.chatBox.querySelector('.chat-host')));
    this.chatBox.classList.toggle('hidden', !store.online);
    const seen = new Set();
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
      pnl.style.left = reg.panel.x + 'px';
      pnl.style.top = reg.panel.y + 'px';
      this.renderPanel(pnl, reg.pid);
    }
    for (const [pid, pnl] of this.panels) if (!seen.has(pid)) { pnl.remove(); this.panels.delete(pid); }
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
    if (pr.input === 'InputAttack') ex.push(['Attack with all', 'attackAll']);
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
    const key = items.map((s) => `${s.si.id}@${Math.round(s.x)},${Math.round(s.y)}`).join('|');
    if (this.stackLayer.dataset.key === key) return;
    this.stackLayer.dataset.key = key;
    this.stackLayer.replaceChildren(...items.slice(0, 4).map((s) => {
      const act = store.players.get(s.si.act);
      const verb = s.si.tr ? 'Trigger' : s.si.ab ? 'Ability' : 'Spell';
      const cap = el(`<div class="stack-cap ${s.top ? 'top' : ''}"><div class="who">${esc(act ? act.n : '')} · ${verb}</div>${manaHtml((s.si.txt || '').slice(0, 260))}</div>`);
      cap.style.left = s.x - 190 + 'px';
      cap.style.top = s.y - s.h / 2 + 'px';
      return cap;
    }));
  }

  // ---------------------------------------------------------------- detail panel

  setDetail(card, stackItem) {
    const box = /** @type {HTMLElement} */ (this.side.querySelector('.detail'));
    if (!card) return;
    if (this.detailCard && this.detailCard.id === card.id && this.detailCard === card && !this.detailDirty) return;
    this.detailCard = card;
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
      flip.addEventListener('click', () => { this.detailAlt = !this.detailAlt; this.detailDirty = true; this.setDetail(card); });
      img.appendChild(flip);
    }
    const lines = [];
    if (card.hid && !card.fd) {
      lines.push('<div class="nm">Hidden card</div>');
    } else {
      lines.push(`<div class="nm">${esc(face.n || '')} ${face.mc ? manaHtml(face.mc) : ''}</div>`);
      if (face.ty) lines.push(`<div class="ty">${esc(face.ty)}</div>`);
      if (face.tx) lines.push(`<div>${manaHtml(face.tx)}</div>`);
      const stats = [];
      if (card.pow !== undefined) stats.push(`<b>${card.pow}/${card.tou}</b>${card.dmg ? ` <span style="color:var(--red)">(${card.dmg} damage)</span>` : ''}`);
      if (card.loy) stats.push(`Loyalty <b>${card.cnt?.LOYALTY ?? card.loy}</b>`);
      if (card.def) stats.push(`Defense <b>${card.cnt?.DEFENSE ?? card.def}</b>`);
      if (stats.length) lines.push(`<div style="margin-top:6px">${stats.join(' · ')}</div>`);
    }
    const meta = [];
    if (card.cnt) meta.push('Counters: ' + Object.entries(card.cnt).map(([k, v]) => `${k.toLowerCase()} ×${v}`).join(', '));
    if (card.at != null) meta.push('Attached to ' + (store.cards.get(card.at)?.n || '?'));
    if (card.atp != null) meta.push('Attached to ' + (store.players.get(card.atp)?.n || '?'));
    if (card.exw != null) meta.push('Exiled with ' + (store.cards.get(card.exw)?.n || '?'));
    if (card.chT) meta.push('Chosen type: ' + card.chT);
    if (card.chC) meta.push('Chosen colors: ' + card.chC);
    if (card.chP != null) meta.push('Chosen player: ' + (store.players.get(card.chP)?.n || '?'));
    const flags = [card.tap && 'Tapped', card.sick && 'Summoning sick', card.tok && 'Token', card.cmd && 'Commander', card.fd && 'Face down', card.ph && 'Phased out', card.atk && 'Attacking', card.blk && 'Blocking'].filter(Boolean);
    if (flags.length) meta.push(flags.join(' · '));
    const ctrl = store.players.get(card.c), own = store.players.get(card.o);
    if (ctrl) meta.push(`Controller: ${ctrl.n}${own && own !== ctrl ? ` (owner: ${own.n})` : ''}`);
    if (stackItem) meta.unshift(`<span style="color:var(--gold-2)">On the stack:</span> ${manaHtml(stackItem.txt || '')}`);
    if (meta.length) lines.push(`<div class="dim" style="margin-top:8px">${meta.map((m) => (m.startsWith('<') ? m : esc(m))).join('<br>')}</div>`);
    /** @type {HTMLElement} */ (box.querySelector('.txt')).innerHTML = lines.join('');
  }

  refreshDetail() {
    const c = this.detailCard;
    if (c && c.id >= 0) {
      const fresh = store.cards.get(c.id);
      if (fresh && fresh !== c) { this.detailDirty = true; this.setDetail(fresh); }
    }
  }

  // ---------------------------------------------------------------- log

  renderLog() {
    const body = /** @type {HTMLElement} */ (this.side.querySelector('.logbox .body'));
    if (store.log.length < this.logCount) { body.replaceChildren(); this.logCount = 0; }
    if (store.log.length === this.logCount) return;
    const stick = body.scrollTop + body.clientHeight >= body.scrollHeight - 30;
    const frag = document.createDocumentFragment();
    for (let i = this.logCount; i < store.log.length; i++) {
      const e = store.log[i];
      frag.appendChild(el(`<div class="le ${e.ty}">${manaHtml(e.m)}</div>`));
    }
    body.appendChild(frag);
    while (body.childElementCount > 600) body.firstElementChild?.remove();
    this.logCount = store.log.length;
    if (stick) body.scrollTop = body.scrollHeight;
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

  // ---------------------------------------------------------------- keys (Esc is the options menu, see options.js)

  installKeys() {
    window.addEventListener('keydown', (e) => {
      if (!store.inMatch || isOptionsOpen()) return;
      const tag = /** @type {HTMLElement} */ (e.target).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (document.querySelector('.popmenu')) return;
      if (anyDialogOpen() && e.key !== 'Tab') return;
      const pr = store.prompt || {};
      if ((e.key === ' ' || e.key === 'Enter') && pr.b1?.on) { e.preventDefault(); send({ t: 'ok' }); }
      else if (e.key === 'Backspace' && pr.b2?.on) { e.preventDefault(); send({ t: 'cancel' }); }
      else if (e.key === 'F2') { e.preventDefault(); send({ t: 'passTurn' }); }
      else if ((e.key === 'z' || e.key === 'Z') && e.ctrlKey) { e.preventDefault(); send({ t: 'undo' }); }
      else if ((e.key === 'a' || e.key === 'A') && pr.input === 'InputAttack') { e.preventDefault(); send({ t: 'attackAll' }); }
      else if (e.key === 'Tab') { e.preventDefault(); setSetting('side', !settings.side); }
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
    this.logCount = 0;
    /** @type {HTMLElement} */ (this.side.querySelector('.logbox .body')).replaceChildren();
    this.detailCard = null;
  }
}

/** Heuristic: does the current prompt accept players as targets? */
function isTargetPrompt() {
  const input = store.prompt?.input || '';
  return input === 'InputSelectTargets' || input === 'InputSelectEntitiesFromList' || input === 'InputAttack';
}
