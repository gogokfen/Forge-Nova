// @ts-check
// Online play: the "Play online" window (host a room / join one), the join screen of a friend who
// opened an invite link, the room itself (invite links, seats, decks, ready, chat) and deck import.

import { api, send, on, guestRoom, isServedGuest, parseInvite, joinRoom, leaveRoom, forgetSeat, reconnectRoom } from '../net.js';
import { esc, el, pipsHtml, toast } from './text.js';
import { modal } from './dialogs.js';
import { FORMATS, formatOf, GEN_CMDR, GEN_CONS, deckArt, deckFits, pickDeckDialog } from './decks.js';

const LIFE_PRESETS = [20, 25, 30, 40];
const MAX_PLAYERS = 8;
/** our own Nova's player name (when it joins a friend's room from the lobby) */
let ownName = '';

const load = (key, fallback) => { try { return JSON.parse(localStorage.getItem(key) || 'null') ?? fallback; } catch { return fallback; } };
const save = (key, v) => { try { localStorage.setItem(key, JSON.stringify(v)); } catch { /* storage unavailable */ } };

function button(label, cls, fn, title = '') {
  const b = el(`<button class="btn ${cls}" ${title ? `title="${esc(title)}"` : ''}>${esc(label)}</button>`);
  b.addEventListener('click', fn);
  return b;
}

/** Resolves true when confirmed. */
function confirmBox(title, message, okLabel, danger = true) {
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

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // http pages of a friend's host are no "secure context": fall back to a hidden text field
    const ta = /** @type {HTMLTextAreaElement} */ (el('<textarea style="position:fixed;opacity:0"></textarea>'));
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch { /* nothing more we can do */ }
    ta.remove();
  }
  toast('Link copied. Send it to your friends.');
}

// =================================================================================== chat

/** @type {any[]} the room's chat, kept across screens (room → match → room) */
const chatLog = [];
/** @type {Set<ChatView>} */
const chatViews = new Set();

on('chat', (m) => {
  if (m.id && chatLog.some((c) => c.id === m.id)) return; // re-sent after a reconnect
  chatLog.push(m);
  if (chatLog.length > 200) chatLog.splice(0, chatLog.length - 150);
  let seen = false;
  chatViews.forEach((v) => { v.append(m); if (v.isVisible()) seen = true; });
  if (!seen && !m.sys && m.from) toast(`${m.from}: ${m.msg}`);
});

/** Forgets the chat (the room was closed or left). */
export function resetChat() {
  chatLog.length = 0;
  chatViews.forEach((v) => v.clear());
}

const SEAT_COLORS = ['#f4c979', '#7cc4ff', '#9fe0b1', '#ff9b73', '#c9a3ff', '#ffd36b', '#80e0d8', '#f59ac0'];

/** A chat box: the messages and an input line. */
export class ChatView {
  /** @param {HTMLElement} host */
  constructor(host) {
    this.host = host;
    this.el = el(`<div class="chat"><div class="chat-log"></div>
      <form class="chat-in"><input maxlength="300" placeholder="Message everyone…" aria-label="Chat message"><button class="btn small" type="submit">Send</button></form></div>`);
    this.log = /** @type {HTMLElement} */ (this.el.querySelector('.chat-log'));
    const input = /** @type {HTMLInputElement} */ (this.el.querySelector('input'));
    this.el.querySelector('form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const msg = input.value.trim();
      if (!msg) return;
      send({ t: 'chat', msg });
      input.value = '';
    });
    // keys typed here are chat, not game shortcuts
    input.addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'Escape') input.blur(); });
    host.appendChild(this.el);
    for (const m of chatLog) this.append(m);
    chatViews.add(this);
  }

  isVisible() {
    return this.el.isConnected && this.el.offsetParent !== null;
  }

  append(m) {
    const stick = this.log.scrollTop + this.log.clientHeight >= this.log.scrollHeight - 30;
    const time = new Date(m.ts || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const line = m.sys
      ? el(`<div class="cm sys"><span class="tm">${time}</span>${esc(m.msg)}</div>`)
      : el(`<div class="cm"><span class="tm">${time}</span><b style="color:${SEAT_COLORS[(m.seat || 0) % SEAT_COLORS.length]}">${esc(m.from)}</b> ${esc(m.msg)}</div>`);
    this.log.appendChild(line);
    while (this.log.childElementCount > 200) this.log.firstElementChild?.remove();
    if (stick) this.log.scrollTop = this.log.scrollHeight;
  }

  clear() {
    this.log.replaceChildren();
  }

  destroy() {
    chatViews.delete(this);
    this.el.remove();
  }
}

// =================================================================================== "Play online" window

/**
 * Host a room (with the lobby's current setup) or join a friend's room.
 * @param {any} lobby the Lobby: its setup (format, life, games, decks) seeds the room
 */
export async function openOnlineDialog(lobby) {
  const dlg = modal('Play online', { wide: true, peek: false, onEsc: () => dlg.close() });
  dlg.modal.classList.add('online-dlg');
  let info = { port: 36743 };
  try { info = { ...info, ...(await api('room')) }; } catch { /* defaults */ }
  const cfg = lobby.cfg;
  const f = formatOf(cfg.format);
  const hostDeck = cfg.slots?.[0]?.deck;
  const aiSlots = (cfg.slots || []).slice(1);
  const saved = load('nova-host-setup', { friends: 1, ai: 0, upnp: true });
  let friends = Math.max(1, Math.min(7, saved.friends || 1));
  let ai = Math.max(0, Math.min(MAX_PLAYERS - 1 - friends, saved.ai || 0));
  const name = lobby.data?.playerName && !/human/i.test(lobby.data.playerName) ? lobby.data.playerName : '';
  ownName = name;

  const body = el(`<div class="online-grid">
    <div class="card-panel online-card">
      <h3>Host a game</h3>
      <p class="online-lead">Your friends join from any web browser, so they don't need Forge. The rules engine runs on this computer.</p>
      <label class="field-row"><span>Your name</span><input class="life-in wide-in" data-name maxlength="20" value="${esc(name)}" placeholder="Name"></label>
      <div class="field-row"><span>Friends</span><div class="num-step"><button class="btn small" data-f="-1">−</button><b data-fv></b><button class="btn small" data-f="1">+</button></div></div>
      <div class="field-row"><span>AI players</span><div class="num-step"><button class="btn small" data-a="-1">−</button><b data-av></b><button class="btn small" data-a="1">+</button></div></div>
      <div class="online-setup">${esc(f.t)} · ${esc(String(lobby.life()))} life · ${cfg.games} game${cfg.games > 1 ? 's' : ''} per match · your deck: <b>${esc(hostDeck?.label || hostDeck?.name || 'choose in the room')}</b>
        <div class="note">From the lobby setup; you can change it in the room.</div></div>
      <label class="field-row online-upnp" title="Like classic Forge's network lobby: the router forwards the port while the room is open"><span>Open the port on my router automatically (UPnP)</span><input type="checkbox" data-upnp ${saved.upnp !== false ? 'checked' : ''}></label>
      <details class="online-adv"><summary>Network port</summary>
        <label class="field-row"><span>Port (TCP)</span><input class="life-in" data-port type="number" min="1024" max="65535" value="${info.port}"></label>
      </details>
      <div class="online-err hidden"></div>
      <button class="btn primary start-btn" data-open>Open a room</button>
    </div>
    <div class="card-panel online-card">
      <h3>Join a friend's game</h3>
      <p class="online-lead">Paste the invite link your friend sent you. You play with your own decks and card images.</p>
      <input class="text-input" data-link placeholder="http://…/#join=…" spellcheck="false">
      <div class="online-err hidden" data-jerr></div>
      <button class="btn primary start-btn" data-join>Join</button>
      <p class="note" style="margin-top:14px">Friends without Forge Nova just open the invite link in Chrome, Edge or Firefox.</p>
    </div></div>`);
  dlg.body.appendChild(body);
  dlg.foot.append(button('Close', '', () => dlg.close()));
  const $ = (sel) => /** @type {HTMLElement} */ (body.querySelector(sel));
  const renderCounts = () => {
    $('[data-fv]').textContent = String(friends);
    $('[data-av]').textContent = String(ai);
    body.querySelectorAll('[data-f]').forEach((b) => { /** @type {HTMLButtonElement} */ (b).disabled = Number(/** @type {HTMLElement} */ (b).dataset.f) < 0 ? friends <= 1 : friends + ai >= MAX_PLAYERS - 1; });
    body.querySelectorAll('[data-a]').forEach((b) => { /** @type {HTMLButtonElement} */ (b).disabled = Number(/** @type {HTMLElement} */ (b).dataset.a) < 0 ? ai <= 0 : friends + ai >= MAX_PLAYERS - 1; });
  };
  body.querySelectorAll('[data-f]').forEach((b) => b.addEventListener('click', () => { friends += Number(/** @type {HTMLElement} */ (b).dataset.f); renderCounts(); }));
  body.querySelectorAll('[data-a]').forEach((b) => b.addEventListener('click', () => { ai += Number(/** @type {HTMLElement} */ (b).dataset.a); renderCounts(); }));
  renderCounts();

  const openBtn = /** @type {HTMLButtonElement} */ ($('[data-open]'));
  openBtn.addEventListener('click', async () => {
    const err = $('.online-err');
    err.classList.add('hidden');
    const nm = /** @type {HTMLInputElement} */ ($('[data-name]')).value.trim();
    const port = Number(/** @type {HTMLInputElement} */ ($('[data-port]')).value);
    const upnp = /** @type {HTMLInputElement} */ ($('[data-upnp]')).checked;
    save('nova-host-setup', { friends, ai, upnp });
    const seats = [];
    for (let i = 0; i < friends; i++) seats.push({ kind: 'friend' });
    for (let i = 0; i < ai; i++) {
      const s = aiSlots[i];
      seats.push(s?.deck ? { kind: 'ai', deck: { src: s.deck.src, name: s.deck.name }, profile: s.profile || '' } : { kind: 'ai' });
    }
    openBtn.disabled = true;
    openBtn.textContent = 'Opening…';
    resetChat();
    try {
      await api('room/open', {
        name: nm, port, upnp, format: cfg.format, life: cfg.life ?? null, games: cfg.games, shareDecks: true,
        hostDeck: hostDeck ? { src: hostDeck.src, name: hostDeck.name } : undefined, seats,
      });
      dlg.close();
    } catch (e) {
      err.textContent = /** @type {Error} */ (e).message;
      err.classList.remove('hidden');
      openBtn.disabled = false;
      openBtn.textContent = 'Open a room';
    }
  });

  const linkIn = /** @type {HTMLInputElement} */ ($('[data-link]'));
  const doJoin = () => {
    const inv = parseInvite(linkIn.value);
    const err = $('[data-jerr]');
    if (!inv) {
      err.textContent = 'That doesn\'t look like an invite link. It ends with #join=… (ask your friend to copy it with the Copy button).';
      err.classList.remove('hidden');
      return;
    }
    dlg.close();
    resetChat();
    joinRoom(inv);
    window.dispatchEvent(new CustomEvent('nova:joining', { detail: inv }));
  };
  $('[data-join]').addEventListener('click', doJoin);
  linkIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') doJoin(); });
  // a link on the clipboard is probably the one to paste
  setTimeout(() => linkIn.focus(), 30);
}

// =================================================================================== join screen

/** A friend who hasn't taken a seat yet: their name, then "Join". */
export class JoinScreen {
  /** @param {HTMLElement} root */
  constructor(root) {
    this.root = root;
    this.info = null;
    this.busy = false;
  }

  /** @param {any} info the room preview ({hostName, format, players, open, inGame}) */
  show(info) {
    this.info = info;
    this.busy = false;
    this.root.classList.remove('hidden');
    this.render();
  }

  hide() { this.root.classList.add('hidden'); }

  error(msg) {
    this.busy = false;
    this.render();
    const e = this.root.querySelector('.online-err');
    if (e) { e.textContent = msg; e.classList.remove('hidden'); }
  }

  render() {
    const i = this.info || {};
    const f = formatOf(i.format);
    const name = load('nova-guest-name', '') || ownName;
    const full = !i.inGame && i.open === 0;
    this.root.innerHTML = `<div class="join-wrap"><div class="card-panel join-card">
      <div class="logo"><span class="logo-gem"></span>Forge <b>Nova</b></div>
      <h2>${esc(i.hostName || 'A friend')} invites you to play</h2>
      <div class="sub">${esc(f.t)} · ${i.players || '?'} players${i.inGame ? ' · a game is running' : ` · ${i.open} open seat${i.open === 1 ? '' : 's'}`}</div>
      ${i.inGame ? '<p class="note">A game is in progress. You can join as soon as it ends.</p>' : full ? '<p class="note">All seats are taken right now.</p>' : `
      <label class="join-name"><span>Your name</span><input maxlength="20" value="${esc(name)}" placeholder="Your name" autocomplete="nickname"></label>`}
      <div class="online-err hidden"></div>
      <div class="join-acts">${i.inGame || full ? '<button class="btn" data-retry>Try again</button>' : `<button class="btn primary start-btn" data-join ${this.busy ? 'disabled' : ''}>${this.busy ? 'Joining…' : 'Join the game'}</button>`}</div>
      <p class="note join-foot">${isServedGuest() ? 'Nothing to install: the game runs in this browser tab. Keep it open while you play.' : 'You play with your own decks and card images.'}</p>
    </div></div>`;
    const input = /** @type {HTMLInputElement|null} */ (this.root.querySelector('input'));
    const go = () => {
      const n = (input?.value || '').trim();
      if (!n) { this.error('Please enter a name.'); return; }
      save('nova-guest-name', n);
      this.busy = true;
      this.render();
      send({ t: 'join', name: n });
    };
    this.root.querySelector('[data-join]')?.addEventListener('click', go);
    input?.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    this.root.querySelector('[data-retry]')?.addEventListener('click', () => reconnectRoom());
    setTimeout(() => input?.focus(), 30);
  }
}

// =================================================================================== deck import (friends)

/** Decks a friend imported, remembered in this browser: {name, text, info}. */
const importedDecks = () => /** @type {any[]} */ (load('nova-imported-decks', []));
function rememberDeck(name, text, info) {
  const list = importedDecks().filter((d) => d.name !== name);
  list.unshift({ name, text, info, at: Date.now() });
  save('nova-imported-decks', list.slice(0, 60));
}
function forgetDeck(name) {
  save('nova-imported-decks', importedDecks().filter((d) => d.name !== name));
}

/** @type {Map<string, (m:any) => void>} replies to deck messages, by reference */
const deckReplies = new Map();
let deckRef = 0;
on('deckResult', (m) => {
  const fn = m.ref && deckReplies.get(m.ref);
  if (fn) { deckReplies.delete(m.ref); fn(m); }
});

/** Sends a deck to the room's host: {mode:'text'|'host'|'gen', ...}; resolves with the host's verdict. */
function sendDeck(msg) {
  return new Promise((resolve) => {
    const ref = 'd' + ++deckRef;
    const timer = setTimeout(() => { deckReplies.delete(ref); resolve({ ok: false, error: 'The host did not answer.' }); }, 20000);
    deckReplies.set(ref, (m) => { clearTimeout(timer); resolve(m); });
    send({ ...msg, t: 'deck', ref });
  });
}

/** The same deck as Forge .dck text (from our own Nova's deck files). */
function dckText(deck) {
  const leaf = String(deck.name || 'Deck').split('/').pop();
  const lines = ['[metadata]', `Name=${leaf}`];
  const sec = (title, rows) => { if (rows?.length) lines.push(`[${title}]`, ...rows.map((r) => `${r[3]} ${r[0]}|${r[1]}${r[2] ? '|' + r[2] : ''}`)); };
  sec('Commander', deck.sections?.commander);
  sec('Main', deck.sections?.main);
  sec('Sideboard', deck.sections?.side);
  return lines.join('\n');
}

function reportImport(m, name) {
  if (m.unknown?.length) {
    toast(`${name}: ${m.unknown.length} line${m.unknown.length === 1 ? '' : 's'} not recognized (${m.unknown.slice(0, 3).join(', ')}${m.unknown.length > 3 ? '…' : ''}).`, 'error');
  }
}

/** Paste a deck list or upload deck files. `onDone` runs after a deck was taken. */
function openImport(onDone) {
  const dlg = modal('Import a deck', { peek: false, onEsc: () => dlg.close() });
  dlg.body.innerHTML = `<div class="imp">
    <p class="note" style="margin:0 0 10px">Paste a list from Moxfield, Archidekt, MTG Arena or MTGO, or the contents of a Forge <b>.dck</b> file.
      Mark the commander with a "Commander" heading, or pick it afterwards.</p>
    <input class="text-input" data-name placeholder="Deck name (optional)" maxlength="60" style="margin-bottom:8px">
    <textarea class="text-input bd-import" data-text placeholder="1 Sol Ring&#10;1 Command Tower&#10;…" spellcheck="false"></textarea>
    <div class="imp-files"><label class="btn small">Upload deck files…<input type="file" data-files multiple accept=".dck,.txt,.dec" hidden></label>
      <span class="note">Forge keeps its decks in %APPDATA%\\Forge\\decks. Several files at once are added to your list.</span></div>
    <div class="online-err hidden"></div></div>`;
  const $ = (s) => /** @type {any} */ (dlg.body.querySelector(s));
  const err = $('.online-err');
  const fail = (msg) => { err.textContent = msg; err.classList.remove('hidden'); };
  const use = button('Import and use', 'primary', async () => {
    const text = $('[data-text]').value;
    const name = $('[data-name]').value.trim();
    if (!text.trim()) { fail('Paste a deck list first.'); return; }
    use.disabled = true;
    const m = await sendDeck({ mode: 'text', text, name });
    use.disabled = false;
    if (!m.ok) { fail(m.error || 'The deck could not be read.'); return; }
    rememberDeck(m.deck?.name || name || 'Imported deck', text, m.deck);
    reportImport(m, m.deck?.name || name);
    dlg.close();
    onDone?.();
  });
  $('[data-files]').addEventListener('change', async (e) => {
    const files = [...(e.target.files || [])];
    if (!files.length) return;
    if (files.length === 1) {
      $('[data-text]').value = await files[0].text();
      $('[data-name]').value = files[0].name.replace(/\.(dck|txt|dec)$/i, '');
      return;
    }
    let added = 0;
    for (const file of files.slice(0, 60)) {
      const text = await file.text();
      const name = file.name.replace(/\.(dck|txt|dec)$/i, '');
      const m = await sendDeck({ mode: 'text', text, name, check: true });
      if (m.ok) { rememberDeck(m.deck?.name || name, text, m.deck); added++; } else toast(`${name}: ${m.error}`, 'error');
    }
    toast(`Added ${added} deck${added === 1 ? '' : 's'} to your list.`);
    dlg.close();
    onDone?.();
  });
  dlg.foot.append(button('Cancel', '', () => dlg.close()), use);
  setTimeout(() => $('[data-text]').focus(), 30);
}

/** An imported deck without a marked commander: the player picks one (or two partners). */
function chooseCommander(candidates) {
  const dlg = modal('Which card is your commander?', { narrow: true, peek: false, onEsc: () => dlg.close() });
  const picked = new Set();
  const list = el('<div class="choice-list"></div>');
  const ok = button('OK', 'primary', () => { send({ t: 'commander', names: [...picked] }); dlg.close(); });
  ok.disabled = true;
  for (const c of candidates) {
    const row = el(`<div class="choice">${esc(c)}</div>`);
    row.addEventListener('click', () => {
      if (picked.has(c)) picked.delete(c);
      else if (picked.size < 2) picked.add(c);
      row.classList.toggle('sel', picked.has(c));
      ok.disabled = picked.size === 0;
    });
    list.appendChild(row);
  }
  dlg.body.append(el('<p class="note" style="margin:0 0 10px">Pick one card, or two that can lead together (partners, a background…).</p>'), list);
  dlg.foot.append(button('Cancel', '', () => dlg.close()), ok);
}

// =================================================================================== the room

/**
 * The online room, for the host and for friends. Rebuilt piecewise from the host's {t:'room'} state
 * (sent on every change and every few seconds), so inputs keep their focus while it updates.
 */
export class RoomScreen {
  /**
   * @param {HTMLElement} root
   * @param {{onBuilder: (deck: any) => void, onOptions: () => void}} hooks
   */
  constructor(root, hooks) {
    this.root = root;
    this.hooks = hooks;
    /** @type {any} */
    this.state = null;
    /** host: our deck lists (api 'decks'); friends: the host's shared lists */
    this.decks = null;
    /** a friend with their own Nova: its deck lists */
    this.localDecks = null;
    this.hostDecks = null;
    /** @type {Record<string, string>} last HTML per section */
    this.parts = {};
    this.chat = null;
    this.askedCommander = '';
    on('hostDecks', (m) => { this.hostDecks = m; });
  }

  get isHost() { return !!this.state?.isHost; }
  get me() { return this.state?.seats?.find((s) => s.id === this.state.you) || null; }

  show() {
    this.root.classList.remove('hidden');
    this.build();
    this.render();
    if (this.isHost) {
      if (!this.decks) api('decks').then((d) => { this.decks = d; this.render(); }).catch(() => {});
    } else {
      if (!this.hostDecks) send({ t: 'hostDecks' });
      if (!isServedGuest() && !this.localDecks) api('decks').then((d) => { this.localDecks = d; }).catch(() => {});
    }
  }

  hide() { this.root.classList.add('hidden'); }

  /** Forget everything of the last room (closed or left). */
  reset() {
    this.state = null;
    this.hostDecks = null;
    this.parts = {};
    this.chat?.destroy();
    this.chat = null;
    this.root.replaceChildren();
  }

  setState(m) {
    const first = !this.state;
    this.state = m;
    if (first) this.parts = {};
    if (!this.root.classList.contains('hidden')) this.render();
    const me = this.me;
    // an imported deck without a marked commander: ask once per deck
    if (me?.candidates?.length && this.askedCommander !== me.deck?.name) {
      this.askedCommander = me.deck?.name || '';
      chooseCommander(me.candidates);
    }
  }

  /** The fixed skeleton: sections are filled in by render(). */
  build() {
    if (this.root.querySelector('.room-wrap')) return;
    this.root.innerHTML = `<div class="lobby-wrap room-wrap">
      <div class="lobby-head" data-part="head"></div>
      <div class="room-grid">
        <div class="room-col"><div class="card-panel" data-part="invite"></div><div class="card-panel" data-part="settings"></div></div>
        <div class="card-panel" data-part="seats"></div>
        <div class="card-panel room-chat"><h3>Chat</h3><div class="chat-host"></div></div>
      </div></div>`;
    this.parts = {};
    this.chat?.destroy();
    this.chat = new ChatView(/** @type {HTMLElement} */ (this.root.querySelector('.chat-host')));
  }

  /** Replaces a section only when its content changed; `bind` wires its controls. */
  part(name, html, bind) {
    const box = /** @type {HTMLElement|null} */ (this.root.querySelector(`[data-part="${name}"]`));
    if (!box) return;
    box.classList.toggle('hidden', html === '');
    if (this.parts[name] === html) return;
    // don't pull a text field away from someone typing in it
    const active = document.activeElement;
    if (active && box.contains(active) && active.tagName === 'INPUT' && /** @type {HTMLInputElement} */ (active).type !== 'checkbox') return;
    this.parts[name] = html;
    box.innerHTML = html;
    bind?.(box);
  }

  render() {
    const st = this.state;
    if (!st) return;
    this.build();
    const f = formatOf(st.format);
    const lifeDefault = f.life(st.seats.length);
    const life = st.life ?? lifeDefault;
    const host = this.isHost;

    // ---- header
    const title = host ? 'Your online room' : `${esc(st.hostName)}'s room`;
    this.part('head', `<div><div class="logo"><span class="logo-gem"></span>Forge <b>Nova</b><span class="room-tag">Online</span></div>
        <div class="sub">${title} · ${esc(f.t)} · ${st.seats.length} players · ${life} life</div></div>
      <div style="display:flex;gap:8px">${!isServedGuest() ? '<button class="btn" data-builder title="Build and edit your decks">Deck Builder</button>' : ''}
        <button class="btn ghost" data-options title="Options (Esc)">Options</button>
        <button class="btn danger" data-leave>${host ? 'Close room' : 'Leave room'}</button></div>`, (box) => {
      box.querySelector('[data-builder]')?.addEventListener('click', () => this.hooks.onBuilder(null));
      box.querySelector('[data-options]')?.addEventListener('click', () => this.hooks.onOptions());
      box.querySelector('[data-leave]')?.addEventListener('click', () => this.leave());
    });

    // ---- invite (host) / connection (friend)
    this.part('invite', host ? this.inviteHtml(st.invite) : this.guestInfoHtml(st), (box) => {
      box.querySelectorAll('[data-copy]').forEach((b) => b.addEventListener('click', () => copyText(/** @type {HTMLElement} */ (b).dataset.copy || '')));
      box.querySelector('[data-tunnel]')?.addEventListener('click', (e) => {
        const on = /** @type {HTMLElement} */ (e.currentTarget).dataset.tunnel === '1';
        api('room/tunnel', { on }).catch((x) => toast(x.message, 'error'));
      });
      box.querySelector('[data-upnp]')?.addEventListener('click', () => api('room/upnp', { on: true }).catch((x) => toast(x.message, 'error')));
    });

    // ---- settings
    this.part('settings', this.settingsHtml(st, f, life, lifeDefault), (box) => this.bindSettings(box, st));

    // ---- seats
    this.part('seats', this.seatsHtml(st, f), (box) => this.bindSeats(box, st));
  }

  // ------------------------------------------------------------------ invite

  inviteHtml(inv) {
    if (!inv) return '';
    const links = inv.links || [];
    const t = inv.tunnel || {};
    const tunnelRow = t.state === 'starting'
      ? '<div class="inv-tunnel"><span class="spin"></span> Creating an internet link with Cloudflare…</div>'
      : t.state === 'on' ? '<div class="inv-tunnel"><button class="btn small ghost" data-tunnel="0">Stop the Cloudflare link</button></div>'
        : t.available ? `<div class="inv-tunnel">${t.state === 'error' ? `<div class="inv-note warn">${esc(t.error || 'The Cloudflare link stopped.')}</div>` : ''}
          <button class="btn small" data-tunnel="1" title="A free https link through Cloudflare: works behind any router, nothing to configure">Create an internet link (Cloudflare)</button></div>`
          : '';
    return `<h3>Invite friends</h3>
      <div class="inv-list">${links.map((l) => `<div class="inv ${l.kind} ${l.warn ? 'warn' : ''}">
        <div class="inv-h"><b>${esc(l.label)}</b><button class="btn small ${l.kind === 'tunnel' || (l.kind === 'public' && !l.warn) ? 'primary' : ''}" data-copy="${esc(l.url)}">Copy link</button></div>
        <div class="inv-url" title="${esc(l.url)}">${esc(l.url)}</div>
        <div class="inv-note">${esc(l.note || '')}</div></div>`).join('')}
        ${!inv.publicIpDone ? '<div class="inv-note"><span class="spin"></span> Looking up your internet address…</div>' : ''}
        ${inv.upnp === 'failed' ? '<button class="btn small ghost" data-upnp>Try opening the router port again</button>' : ''}
      </div>
      ${tunnelRow}
      <details class="inv-help"><summary>How do friends connect?</summary>
        <p><b>Same Wi-Fi / home network:</b> send the "Local network" link.</p>
        <p><b>Over the internet:</b> the "Internet" link works when your router forwards port ${inv.port} (TCP) to this computer.
          Nova asks the router to do that automatically (UPnP); if it can't, set up a port forward in the router's settings.</p>
        <p><b>No router access, or it still doesn't work?</b> ${t.available ? 'Create a Cloudflare link above: it works from anywhere.'
          : 'Install Cloudflare\'s free <b>cloudflared</b> program (winget install Cloudflare.cloudflared, or put cloudflared.exe in nova\\tools) and restart Nova: a button for a link that works from anywhere appears here.'}
          A VPN such as Tailscale, ZeroTier or Radmin VPN works too: everyone joins it and uses its address.</p>
        <p><b>Windows Firewall:</b> if Windows asks whether Java may accept connections, choose Allow.</p>
        <p><b>Testing:</b> most routers can't open your own Internet link from inside your home, so ask a friend to try it
          (or use a phone on mobile data).</p>
        <p>Friends open the link in Chrome, Edge or Firefox. Friends with Forge Nova can also paste it in <i>Play online → Join</i> to use their own decks.</p>
      </details>`;
  }

  guestInfoHtml(st) {
    const r = guestRoom();
    const rtt = r && r.rtt >= 0 ? `${r.rtt} ms` : '…';
    return `<h3>Connection</h3>
      <div class="inv-note">Connected to ${esc(st.hostName)}'s Forge Nova · <span title="Round trip to the host">${rtt}</span></div>
      <p class="note">${isServedGuest() ? 'Keep this tab open while you play. If the connection drops, this page reconnects by itself (reloading it is fine too).' : 'If the connection drops, Nova reconnects by itself.'}</p>`;
  }

  // ------------------------------------------------------------------ settings

  settingsHtml(st, f, life, lifeDefault) {
    const host = this.isHost;
    if (!host) {
      return `<h3>Game</h3><div class="settings-grid room-facts">
        <span>Format</span><b>${esc(f.t)}</b><span>Starting life</span><b>${life}</b>
        <span>Games per match</span><b>${st.games}</b><span>Host's decks</span><b>${st.shareDecks ? 'shared' : 'not shared'}</b></div>`;
    }
    return `<h3>Game</h3>
      <div class="setup-label">Format</div>
      <select class="room-sel" data-format>${FORMATS.map((x) => `<option value="${x.id}" ${x.id === f.id ? 'selected' : ''}>${esc(x.t)}</option>`).join('')}</select>
      <div class="setup-label room-life-label">Starting life</div>
      <div class="life-pick"><div class="seg">${LIFE_PRESETS.map((v) => `<button class="${life === v ? 'sel' : ''}" data-life-preset="${v}">${v}</button>`).join('')}</div>
        <input class="life-in" type="number" min="1" max="999" value="${life}" data-life title="Any starting life for every player"></div>
      <div class="life-note">${st.life == null ? `${esc(f.t)} default for ${st.seats.length} players` : `Custom · <a href="#" data-life-reset>use the default (${lifeDefault})</a>`}</div>
      <div class="field-row"><span>Games per match</span><div class="seg">${[1, 3, 5].map((n) => `<button class="${st.games === n ? 'sel' : ''}" data-games="${n}">${n}</button>`).join('')}</div></div>
      <div class="field-row"><span title="Friends may play one of your decks">Let friends use my decks</span><div class="switch ${st.shareDecks ? 'on' : ''}" data-share></div></div>`;
  }

  bindSettings(box, st) {
    const set = (body) => api('room/settings', body).catch((e) => toast(e.message, 'error'));
    box.querySelector('[data-format]')?.addEventListener('change', (e) => set({ format: /** @type {HTMLSelectElement} */ (e.target).value, life: null }));
    box.querySelectorAll('[data-life-preset]').forEach((b) => b.addEventListener('click', () => set({ life: Number(/** @type {HTMLElement} */ (b).dataset.lifePreset) })));
    const lifeIn = /** @type {HTMLInputElement|null} */ (box.querySelector('[data-life]'));
    lifeIn?.addEventListener('change', () => {
      const v = Math.round(Number(lifeIn.value));
      if (v >= 1 && v <= 999) set({ life: v });
    });
    lifeIn?.addEventListener('keydown', (e) => { if (e.key === 'Enter') lifeIn.blur(); });
    box.querySelector('[data-life-reset]')?.addEventListener('click', (e) => { e.preventDefault(); set({ life: null }); });
    box.querySelectorAll('[data-games]').forEach((b) => b.addEventListener('click', () => set({ games: Number(/** @type {HTMLElement} */ (b).dataset.games) })));
    box.querySelector('[data-share]')?.addEventListener('click', () => set({ shareDecks: !st.shareDecks }));
  }

  // ------------------------------------------------------------------ seats

  seatsHtml(st, f) {
    const host = this.isHost;
    const profiles = this.decks?.aiProfiles || ['Default'];
    const rows = st.seats.map((s) => {
      const mine = s.id === st.you;
      const d = s.deck;
      const art = deckArt(d);
      const kind = s.kind === 'host' ? 'Host' : s.kind === 'ai' ? 'AI player' : 'Friend';
      const name = s.kind === 'friend' && s.open ? 'Open seat' : s.name || (s.kind === 'ai' ? 'AI' : '');
      const status = s.kind === 'friend' && !s.open
        ? `<span class="rs ${s.conn ? (s.ready ? 'ok' : '') : 'off'}">${!s.conn ? 'disconnected' : s.ready ? '✓ ready' : 'not ready'}</span>${s.conn && s.rtt != null ? `<span class="rtt" title="Round trip to the host">${s.rtt} ms</span>` : ''}`
        : '';
      const deckLine = s.kind === 'friend' && s.open
        ? '<span class="note">Waiting for a friend to join with an invite link…</span>'
        : d ? `${esc(d.label || d.name)} <span class="pips">${d.colors !== undefined ? pipsHtml(d.colors) : ''}</span>`
          : `<span class="note">${mine ? 'Choose a deck' : 'No deck yet'}</span>`;
      const ctl = [];
      if (mine || (host && s.kind === 'ai')) ctl.push(`<button class="btn small" data-deck="${s.id}">Deck…</button>`);
      if (host && s.kind === 'host' && d && ['commander', 'constructed', 'brawl', 'oathbreaker', 'tinyLeaders'].includes(d.src)) ctl.push(`<button class="btn small ghost" data-edit="${s.id}" title="Edit this deck in the Deck Builder">✎</button>`);
      if (host && s.kind === 'ai') ctl.push(`<select data-profile="${s.id}" title="AI profile">${profiles.map((p) => `<option ${p === s.profile ? 'selected' : ''}>${esc(p)}</option>`).join('')}</select>`);
      if (host && s.kind !== 'host') {
        ctl.push(`<select data-kind="${s.id}" title="Who plays in this seat"><option value="friend" ${s.kind === 'friend' ? 'selected' : ''}>Friend</option><option value="ai" ${s.kind === 'ai' ? 'selected' : ''}>AI</option></select>`);
        if (s.kind === 'friend' && !s.open) ctl.push(`<button class="btn small ghost" data-kick="${s.id}" title="Send ${esc(s.name)} away (the seat stays open)">Kick</button>`);
        if (st.seats.length > 2) ctl.push(`<button class="btn small ghost" data-remove="${s.id}" title="Remove this seat">✕</button>`);
      }
      if (mine && s.kind === 'friend') ctl.push(`<button class="btn small ${s.ready ? '' : 'primary'}" data-ready="${s.ready ? 0 : 1}" ${!s.ready && !d ? 'disabled title="Choose a deck first"' : ''}>${s.ready ? 'Not ready' : 'Ready'}</button>`);
      return `<div class="slot seat ${mine ? 'me' : ''} ${s.kind === 'friend' && s.open ? 'open' : ''}">
        <div class="art" style="background-image:url('${art}')" ${mine || (host && s.kind === 'ai') ? `data-deck="${s.id}"` : ''}></div>
        <div class="seat-main"><div class="who">${esc(kind)}${mine ? ' · you' : ''} ${status}</div>
          <div class="deck">${esc(name)}</div>
          <div class="seat-deck">${deckLine}${s.problem ? `<div class="seat-prob">⚠ ${esc(s.problem)}</div>` : ''}</div></div>
        <div class="controls">${ctl.join('')}</div></div>`;
    }).join('');
    const full = st.seats.length >= MAX_PLAYERS;
    const acts = host
      ? `<div class="lobby-actions"><div>${full ? '' : '<button class="btn small" data-add="friend">+ Friend seat</button> <button class="btn small" data-add="ai">+ AI player</button>'}</div>
          <div class="start-box"><span class="note start-why">${esc(st.why || 'Everyone is ready.')}</span>
          <button class="btn primary start-btn" data-start ${st.why ? 'disabled' : ''}>Start game</button></div></div>`
      : `<div class="lobby-actions"><span class="note">${this.me?.ready ? `Waiting for ${esc(st.hostName)} to start the game…` : 'Choose a deck, then click Ready.'}</span></div>`;
    return `<h3>Players <span class="sum">${st.seats.length} of ${MAX_PLAYERS} · ${esc(f.t)}</span></h3><div class="slots">${rows}</div>${acts}`;
  }

  bindSeats(box, st) {
    const post = (path, body) => api(path, body).catch((e) => toast(e.message, 'error'));
    box.querySelectorAll('[data-deck]').forEach((b) => b.addEventListener('click', () => this.pickDeck(Number(/** @type {HTMLElement} */ (b).dataset.deck))));
    box.querySelectorAll('[data-edit]').forEach((b) => b.addEventListener('click', () => {
      const s = st.seats.find((x) => x.id === Number(/** @type {HTMLElement} */ (b).dataset.edit));
      if (s?.deck) this.hooks.onBuilder({ src: s.deck.src, name: s.deck.name });
    }));
    box.querySelectorAll('[data-profile]').forEach((sel) => sel.addEventListener('change', () => post('room/seat', { id: Number(/** @type {HTMLElement} */ (sel).dataset.profile), profile: /** @type {HTMLSelectElement} */ (sel).value })));
    box.querySelectorAll('[data-kind]').forEach((sel) => sel.addEventListener('change', async () => {
      const id = Number(/** @type {HTMLElement} */ (sel).dataset.kind);
      const s = st.seats.find((x) => x.id === id);
      const kind = /** @type {HTMLSelectElement} */ (sel).value;
      if (s?.kind === 'friend' && !s.open && !await confirmBox(`Give ${s.name}'s seat to an AI player?`, `${s.name} will be sent away from the room.`, 'Replace')) {
        /** @type {HTMLSelectElement} */ (sel).value = 'friend';
        return;
      }
      post('room/seat', { id, kind });
    }));
    box.querySelectorAll('[data-kick]').forEach((b) => b.addEventListener('click', async () => {
      const id = Number(/** @type {HTMLElement} */ (b).dataset.kick);
      const s = st.seats.find((x) => x.id === id);
      if (await confirmBox(`Kick ${s?.name}?`, 'They leave the room; the seat stays open for someone else.', 'Kick')) post('room/kick', { id });
    }));
    box.querySelectorAll('[data-remove]').forEach((b) => b.addEventListener('click', async () => {
      const id = Number(/** @type {HTMLElement} */ (b).dataset.remove);
      const s = st.seats.find((x) => x.id === id);
      if (s?.kind === 'friend' && !s.open && !await confirmBox(`Remove ${s.name}'s seat?`, `${s.name} will be sent away from the room.`, 'Remove')) return;
      post('room/seat/remove', { id });
    }));
    box.querySelectorAll('[data-add]').forEach((b) => b.addEventListener('click', () => post('room/seat/add', { kind: /** @type {HTMLElement} */ (b).dataset.add })));
    box.querySelector('[data-start]')?.addEventListener('click', async (e) => {
      const btn = /** @type {HTMLButtonElement} */ (e.currentTarget);
      btn.disabled = true;
      btn.textContent = 'Starting…';
      try { await api('room/start', {}); } catch (x) { toast(/** @type {Error} */ (x).message, 'error'); btn.disabled = false; btn.textContent = 'Start game'; }
    });
    box.querySelector('[data-ready]')?.addEventListener('click', (e) => send({ t: 'ready', on: /** @type {HTMLElement} */ (e.currentTarget).dataset.ready === '1' }));
  }

  // ------------------------------------------------------------------ decks

  pickDeck(seatId) {
    const st = this.state;
    const seat = st?.seats.find((s) => s.id === seatId);
    if (!st || !seat) return;
    const cmdr = formatOf(st.format).cmdr;
    const fits = (d) => deckFits(d, cmdr);
    const gen = () => (cmdr ? GEN_CMDR : GEN_CONS).map((g) => ({ ...g }));
    if (this.isHost) {
      const data = this.decks;
      if (!data) { toast('Your decks are still loading…'); return; }
      pickDeckDialog({
        title: seat.kind === 'host' ? 'Choose your deck' : `Choose a deck for ${seat.name || 'the AI player'}`,
        initial: (data.user || []).some(fits) ? 'mine' : 'builtin',
        tabs: [
          { key: 'mine', label: 'My decks', list: () => (data.user || []).filter(fits), empty: 'No decks for this format in your Forge deck folders.' },
          { key: 'builtin', label: cmdr ? 'Commander precons' : 'Precons', list: () => (data.builtin || []).filter(fits) },
          { key: 'random', label: 'Random', list: gen },
        ],
        onPick: (d) => api('room/seat', { id: seatId, deck: { src: d.src, name: d.name } }).catch((e) => toast(e.message, 'error')),
      });
      return;
    }
    // a friend: decks imported in this browser, our own Nova's decks, the host's decks
    const hd = this.hostDecks;
    const shared = !!hd?.shared;
    const mine = () => {
      const imported = importedDecks().map((d) => ({ ...(d.info || {}), name: d.name, label: d.name, src: 'import', sub: d.info?.count ? `${d.info.count} cards · imported` : 'imported', _text: d.text }));
      const own = (this.localDecks?.user || []).filter(fits).map((d) => ({ ...d, _local: true }));
      return [...own, ...imported];
    };
    const importBtn = button('Import a deck…', 'small', () => { picker.close(); openImport(() => {}); });
    const tabs = [
      { key: 'mine', label: 'My decks', list: mine, empty: 'No decks yet. Import a deck list (Moxfield, Archidekt, Arena…) or Forge .dck files with "Import a deck…".' },
    ];
    if (shared) tabs.push({ key: 'host', label: `${st.hostName}'s decks`, list: () => (hd.data?.user || []).filter(fits) });
    tabs.push({ key: 'builtin', label: cmdr ? 'Commander precons' : 'Precons', list: () => (hd?.data?.builtin || []).filter(fits), empty: hd ? 'No precons.' : 'Loading…' });
    tabs.push({ key: 'random', label: 'Random', list: () => gen().filter((g) => g.name !== 'randomUser') });
    const picker = pickDeckDialog({
      title: 'Choose your deck',
      initial: mine().length ? 'mine' : shared ? 'host' : 'builtin',
      tools: [importBtn],
      tabs,
      onPick: async (d) => {
        let m;
        if (d._local) {
          // a deck of our own Nova: send its card list
          try {
            const full = await api(`deck?src=${encodeURIComponent(d.src)}&name=${encodeURIComponent(d.name)}`);
            m = await sendDeck({ mode: 'text', text: dckText(full), name: String(d.name).split('/').pop() });
          } catch (e) { m = { ok: false, error: /** @type {Error} */ (e).message }; }
        } else if (d.src === 'import') {
          m = await sendDeck({ mode: 'text', text: d._text, name: d.name });
          if (!m.ok && /not recognized|could not be read/i.test(m.error || '')) forgetDeck(d.name);
        } else if (d.src === 'gen') {
          m = await sendDeck({ mode: 'gen', name: d.name });
        } else {
          m = await sendDeck({ mode: 'host', src: d.src, name: d.name });
        }
        if (!m.ok) toast(m.error || 'That deck could not be used.', 'error');
        else reportImport(m, d.label || d.name);
      },
    });
  }

  // ------------------------------------------------------------------ leaving

  async leave() {
    if (this.isHost) {
      if (!await confirmBox('Close the room?', 'Your friends are disconnected and the invite links stop working until you open a room again.', 'Close room')) return;
      api('room/close', {}).catch((e) => toast(e.message, 'error'));
      return;
    }
    if (!await confirmBox('Leave the room?', 'Your seat opens up for someone else.', 'Leave')) return;
    leaveAsGuest();
  }
}

/** A friend leaves the room for good (conceding a running game). */
export function leaveAsGuest() {
  send({ t: 'leave' });
  forgetSeat();
  window.dispatchEvent(new CustomEvent('nova:leftRoom'));
  if (!isServedGuest()) setTimeout(() => leaveRoom(), 150);
}
