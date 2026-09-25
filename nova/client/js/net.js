// @ts-check
// Connections: this computer's Forge Nova host (API, decks, card images) and the game socket.
// Playing online in a friend's room, the game socket goes to the friend's host instead: either this
// page came from there (a friend who opened an invite link in a browser), or our own Nova joined the
// room from its lobby and keeps using our own decks and card images.

const params = new URLSearchParams(location.search);
export const TOKEN = params.get('token') || sessionStorage.getItem('nova-token') || '';
if (TOKEN) sessionStorage.setItem('nova-token', TOKEN);

/** version of the online room messages; a host of another Nova version refuses the connection */
export const PROTO = 1;

/** WebSocket close codes after which reconnecting makes no sense */
export const CLOSE = { REPLACED: 4000, ROOM_CLOSED: 4001, KICKED: 4002, FULL: 4003, REJECTED: 4004, VERSION: 4010, FLOOD: 4020 };
const FINAL = new Set(Object.values(CLOSE));

const seatKeyOf = (code) => { try { return localStorage.getItem('nova-seat:' + code) || ''; } catch { return ''; } };
const hashJoin = new URLSearchParams(location.hash.replace(/^#/, '')).get('join') || '';

/**
 * The friend's room this window plays in (null: playing on our own host). `served`: this page was
 * loaded from the room's host through an invite link.
 * @type {{code:string, base:string, served:boolean, key:string, rtt:number}|null}
 */
let room = !TOKEN && hashJoin ? { code: hashJoin, base: location.origin, served: true, key: seatKeyOf(hashJoin), rtt: -1 } : null;

/** The room we play in as a friend, or null. */
export function guestRoom() { return room; }
/** This page came from a friend's host (no Nova of our own: no API, no local decks). */
export function isServedGuest() { return !!room?.served; }

/** @type {Map<string, Set<(msg:any)=>void>>} */
const handlers = new Map();

/** Subscribe to a message type ('*' receives everything). */
export function on(type, fn) {
  if (!handlers.has(type)) handlers.set(type, new Set());
  handlers.get(type)?.add(fn);
  return () => handlers.get(type)?.delete(fn);
}

function emit(type, msg) {
  handlers.get(type)?.forEach((fn) => {
    try { fn(msg); } catch (e) { console.error('handler failed', type, e); }
  });
  handlers.get('*')?.forEach((fn) => {
    try { fn(msg); } catch (e) { console.error(e); }
  });
}

const wsBase = (httpBase) => httpBase.replace(/^http/, 'ws');

/** One WebSocket that reconnects by itself (with back-off) until stopped or closed for good. */
class Socket {
  /**
   * @param {'local'|'room'} kind
   * @param {() => string} url
   */
  constructor(kind, url) {
    this.kind = kind;
    this.url = url;
    /** @type {WebSocket|null} */
    this.ws = null;
    this.open = false;
    this.retry = 0;
    this.stopped = false;
    /** @type {any[]} */
    this.outbox = [];
  }

  connect() {
    if (this.stopped) return;
    const ws = new WebSocket(this.url());
    this.ws = ws;
    ws.onopen = () => {
      if (this.ws !== ws) return;
      this.open = true;
      this.retry = 0;
      dispatch(this, { t: '_open' });
      while (this.outbox.length && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(this.outbox.shift()));
    };
    ws.onmessage = (ev) => {
      if (this.ws !== ws) return;
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      dispatch(this, msg);
    };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return;
      const was = this.open;
      this.open = false;
      dispatch(this, { t: '_close', code: ev.code, reason: ev.reason });
      if (this.stopped || FINAL.has(ev.code)) return;
      setTimeout(() => this.connect(), was ? 150 : Math.min(4000, 250 * 2 ** this.retry++));
    };
    ws.onerror = () => { /* onclose follows */ };
  }

  send(msg) {
    if (this.ws && this.open && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
    else if (!this.stopped) this.outbox.push(msg);
  }

  stop() {
    this.stopped = true;
    this.outbox.length = 0;
    try { this.ws?.close(1000); } catch { /* already closed */ }
    this.ws = null;
    this.open = false;
  }
}

/** @type {Socket|null} this computer's host */
let local = null;
/** @type {Socket|null} the friend's room */
let remote = null;

const gameSocket = () => (room ? remote : local);

/**
 * Messages of the game socket reach the handlers; while we play in a friend's room, our own host
 * only matters for its settings (hello) and its connection state.
 */
function dispatch(sock, msg) {
  if (sock.kind === 'room') {
    if (msg.t === 'welcome' && room && msg.key) {
      room.key = msg.key;
      try { localStorage.setItem('nova-seat:' + room.code, msg.key); } catch { /* storage unavailable */ }
    }
    if (msg.t === 'pong' && room && msg.ts) room.rtt = Math.max(0, Date.now() - msg.ts);
  }
  if (sock === gameSocket()) {
    emit(msg.t, msg);
  } else if (sock.kind === 'local') {
    if (msg.t === 'hello') emit('localHello', msg);
    else if (msg.t === '_open' || msg.t === '_close') emit('_local' + msg.t.slice(1), msg);
  }
}

export function isConnected() { return !!gameSocket()?.open; }

/** Opens the connections this page needs (our own host and/or the room of an invite link). */
export function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  if (TOKEN || !room) {
    local = new Socket('local', () => `${proto}://${location.host}/ws?token=${encodeURIComponent(TOKEN)}`);
    local.connect();
  }
  if (room) openRoomSocket();
}

function openRoomSocket() {
  const r = /** @type {NonNullable<typeof room>} */ (room);
  remote = new Socket('room', () => `${wsBase(r.base)}/ws?room=${encodeURIComponent(r.code)}&v=${PROTO}${r.key ? '&key=' + encodeURIComponent(r.key) : ''}`);
  remote.connect();
}

/** Sends a message on the game socket; queued while reconnecting. */
export function send(msg) {
  gameSocket()?.send(msg);
}

/** Reads a friend's invite link ("http://1.2.3.4:36743/#join=…"); null if it isn't one. */
export function parseInvite(text) {
  let u;
  try { u = new URL(String(text || '').trim()); } catch { return null; }
  const code = new URLSearchParams(u.hash.replace(/^#/, '')).get('join');
  if (!code || !/^https?:$/.test(u.protocol)) return null;
  return { base: u.origin, code };
}

/** Our own Nova joins a friend's room (the game socket moves there). */
export function joinRoom(inv) {
  if (room?.served) return;
  leaveRoom(true);
  room = { code: inv.code, base: inv.base, served: false, key: seatKeyOf(inv.code), rtt: -1 };
  openRoomSocket();
}

/** Stops playing in a friend's room (only possible when our own Nova joined it). */
export function leaveRoom(silent = false) {
  if (!room || room.served) return;
  remote?.stop();
  remote = null;
  room = null;
  if (!silent) emit('_roomLeft', {});
}

/** Forget our seat in the room (after leaving it on purpose). */
export function forgetSeat() {
  if (!room) return;
  room.key = '';
  try { localStorage.removeItem('nova-seat:' + room.code); } catch { /* storage unavailable */ }
}

/** Reconnects to the room from scratch (e.g. after "the room is full", to try again). */
export function reconnectRoom() {
  if (!room) return;
  remote?.stop();
  openRoomSocket();
}

// keep-alives: our host every 20 s; the room every 5 s, which also measures the round-trip time
setInterval(() => { if (local?.open) local.send({ t: 'ping' }); }, 20000);
setInterval(() => { if (room && remote?.open) remote.send({ t: 'ping', ts: Date.now(), rtt: room.rtt }); }, 5000);

/** JSON API call to our own host (token-authenticated). */
export async function api(path, body) {
  if (room?.served) throw new Error('Not available in a browser invite.');
  const res = await fetch(`/api/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-Nova-Token': TOKEN, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || `HTTP ${res.status}`), { status: res.status });
  return data;
}

// encodeURIComponent keeps ' ( ) ! *, and a name like "Glarb, Calamity's Augur" would end a CSS url('…') early
const enc = (s) => encodeURIComponent(s).replace(/['()!*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());

/** URL for a card image key (served from Forge's image cache, downloaded on demand). Safe inside CSS url(). */
export function imgUrl(key) {
  return room?.served ? `/img?key=${enc(key)}&k=${enc(room.code)}` : `/img?key=${enc(key)}`;
}

/** Card image for the board: in a browser invite the host sends a board-sized copy (a quarter of the bytes). */
export function thumbUrl(key) {
  return room?.served ? imgUrl(key) + '&w=256' : imgUrl(key);
}
